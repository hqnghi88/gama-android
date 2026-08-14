import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Null-guards the step/isTimeDependent warning in
 * AttributeDeclaration$VarValidator.validate().
 *
 * Upstream GAMA (gama.api/src/gama/api/gaml/variables/AttributeDeclaration.java):
 *   if (STEP.equals(name) && cd.hasFacet(INIT) && !cd.hasFacet(UPDATE)) {
 *       final IExpression expr = cd.getFacetExpr(INIT);
 *       if (expr.isTimeDependent()) { ... warning ... }   // <-- NPE when expr == null
 *   }
 *
 * When the INIT facet expression fails to compile (e.g. "float step <- 1 #s;"
 * when the unit/expression resolution returns null on Android), getFacetExpr
 * returns null and the app crashes with
 * "NullPointerException: gama.api.gaml.expressions.IExpression.isTimeDependent()
 * on a null object reference". Rewrite the bytecode to
 *   if (expr != null && expr.isTimeDependent()) { ... }
 */
public class VarValidatorNullGuardPatcher {
    static final String TARGET = "gama/api/gaml/variables/AttributeDeclaration$VarValidator.class";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Usage: VarValidatorNullGuardPatcher <jar>"); System.exit(1); }
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
                    if (mn.name.equals("validate")
                            && mn.desc.equals("(Lgama/api/compilation/descriptions/IDescription;)V")) {
                        if (patchValidate(mn)) {
                            patched = true;
                            System.out.println("Patched " + TARGET
                                    + " validate: isTimeDependent() call null-guarded");
                        }
                    }
                }

                if (patched) {
                    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                    cn.accept(cw);
                    data = cw.toByteArray();
                }
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

    static boolean patchValidate(MethodNode mn) {
        boolean changed = false;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            if (min.getOpcode() != Opcodes.INVOKEINTERFACE) continue;
            if (!min.name.equals("isTimeDependent")
                    || !min.desc.equals("()Z")) continue;

            AbstractInsnNode prev = min.getPrevious();
            if (prev == null || !(prev instanceof VarInsnNode vn)) continue;
            if (vn.getOpcode() != Opcodes.ALOAD) continue;

            AbstractInsnNode next = min.getNext();
            if (next == null || !(next instanceof JumpInsnNode jin)) continue;
            if (jin.getOpcode() != Opcodes.IFEQ) continue;

            // Insert "ALOAD x; IFNULL <target>" before the existing ALOAD x so a null
            // expression short-circuits to the SAME target as the isTimeDependent()==false
            // branch. No new labels/branch targets are introduced, so the existing stack
            // map frames stay valid under COMPUTE_MAXS (a new branch target would need a
            // frame and break D8).
            InsnList guard = new InsnList();
            guard.add(new VarInsnNode(Opcodes.ALOAD, vn.var));
            guard.add(new JumpInsnNode(Opcodes.IFNULL, (LabelNode) jin.label));
            mn.instructions.insertBefore(vn, guard);

            changed = true;
            break;
        }
        return changed;
    }
}
