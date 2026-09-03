import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Inserts a call to GamlResourceServices.discardValidationContext(GamlResource)
 * into GamlModelBuilder.buildModelDescription() right after the model resource
 * is loaded, so that a re-compilation of the same URI starts from a clean
 * validation context instead of reusing the statically cached (stale) one.
 *
 * The static per-URI context in GamlResourceServices accumulates issues from the
 * previous compilation of the same file; reusing it makes a re-compiled model
 * fail with random leftover "Redefinition ...", "No operator found ..." and NPE
 * errors. Restarting the app clears it, which matches the reported behavior.
 */
public class GamlModelBuilderPatcher {
    static final String TARGET = "gaml/compiler/validation/GamlModelBuilder.class";
    static final String METHOD_NAME = "buildModelDescription";
    static final String METHOD_DESC =
            "(Lorg/eclipse/emf/common/util/URI;Ljava/util/List;)Lgama/api/compilation/descriptions/IModelDescription;";
    static final String RESOURCE_CLASS = "gaml/compiler/resource/GamlResource";
    static final String SERVICES_CLASS = "gaml/compiler/resource/GamlResourceServices";
    static final String DISCARD_DESC = "(L" + RESOURCE_CLASS + ";)V";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Usage: GamlModelBuilderPatcher <jar>"); System.exit(1); }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found: " + jarFile); System.exit(1); }

        ZipFile zipIn = new ZipFile(jarFile);
        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));
        boolean patched = false;

        Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            byte[] data;
            try (InputStream is = zipIn.getInputStream(entry)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                data = baos.toByteArray();
            }

            if (entry.getName().equals(TARGET)) {
                ClassReader cr = new ClassReader(data);
                ClassNode cn = new ClassNode();
                cr.accept(cn, 0);

                for (MethodNode mn : cn.methods) {
                    if (!mn.name.equals(METHOD_NAME) || !mn.desc.equals(METHOD_DESC)) continue;
                    if (patch(mn)) {
                        patched = true;
                        System.out.println("Patched " + TARGET + "." + METHOD_NAME
                                + ": insert discardValidationContext(resource) after getResource");
                    }
                }

                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                cn.accept(cw);
                data = cw.toByteArray();
            }

            ZipEntry outEntry = new ZipEntry(entry.getName());
            zipOut.putNextEntry(outEntry);
            zipOut.write(data);
            zipOut.closeEntry();
        }
        zipIn.close();
        zipOut.close();

        if (patched) {
            if (!jarFile.delete()) { System.err.println("Failed to delete original JAR"); System.exit(1); }
            if (!tmpJar.renameTo(jarFile)) { System.err.println("Failed to rename temp JAR"); System.exit(1); }
            System.out.println("JAR updated: " + jarFile.getName());
        } else {
            tmpJar.delete();
            System.err.println("WARNING: Target not found or not patched, JAR unchanged");
        }
    }

    static boolean patch(MethodNode mn) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (!call.name.equals("getResource")) continue;

            AbstractInsnNode n = skipPseudo(call.getNext());
            if (!(n instanceof TypeInsnNode cast) || cast.getOpcode() != Opcodes.CHECKCAST
                    || !cast.desc.equals(RESOURCE_CLASS)) continue;

            n = skipPseudo(cast.getNext());
            if (!(n instanceof VarInsnNode store) || store.getOpcode() != Opcodes.ASTORE) continue;

            InsnList seq = new InsnList();
            seq.add(new VarInsnNode(Opcodes.ALOAD, store.var));
            seq.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SERVICES_CLASS, "discardValidationContext",
                    DISCARD_DESC, false));
            mn.instructions.insert(store, seq);
            return true;
        }
        return false;
    }

    static AbstractInsnNode skipPseudo(AbstractInsnNode n) {
        while (n != null && (n instanceof LabelNode || n instanceof LineNumberNode || n instanceof FrameNode)) {
            n = n.getNext();
        }
        return n;
    }
}
