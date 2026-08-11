import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class GridFileFallbackPatcher {
    static final String TARGET = "gama/core/util/file/GamaGridFile.class";
    static final String METHOD_NAME = "privateCreateCoverage";
    static final String METHOD_DESC = "(Lgama/api/runtime/scope/IScope;Ljava/io/InputStream;)V";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Usage: GridFileFallbackPatcher <jar>"); System.exit(1); }
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
                    for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                        if (tcb.type == null || !tcb.type.equals("java/lang/Exception")) continue;
                        tcb.type = "java/lang/Throwable";
                        updateHandlerFrame(tcb, mn);
                        insertThrow(tcb, mn);
                        patched = true;
                        System.out.println("Patched " + TARGET + " " + METHOD_NAME
                                + ": catch Exception -> catch Throwable, rethrow as RuntimeException");
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

    static void updateHandlerFrame(TryCatchBlockNode tcb, MethodNode mn) {
        AbstractInsnNode insn = tcb.handler.getNext();
        while (insn != null && (insn instanceof LineNumberNode || insn instanceof LabelNode)) {
            insn = insn.getNext();
        }
        if (insn instanceof FrameNode) {
            FrameNode fn = (FrameNode) insn;
            if (!fn.stack.isEmpty()) { fn.stack.set(fn.stack.size() - 1, "java/lang/Throwable"); }
            for (int i = 0; i < fn.local.size(); i++) {
                Object t = fn.local.get(i);
                if (t != null && t instanceof String && ((String) t).equals("java/lang/Exception")) {
                    fn.local.set(i, "java/lang/Throwable");
                }
            }
        }
    }

    static void insertThrow(TryCatchBlockNode tcb, MethodNode mn) {
        AbstractInsnNode insn = tcb.handler;
        while (insn != null && (insn instanceof LineNumberNode || insn instanceof LabelNode || insn instanceof FrameNode)) {
            insn = insn.getNext();
        }
        if (insn == null || !(insn instanceof VarInsnNode) || insn.getOpcode() != Opcodes.ASTORE) {
            throw new IllegalStateException("Unexpected handler start: "
                    + (insn == null ? "null" : String.valueOf(insn.getOpcode())));
        }
        int var = ((VarInsnNode) insn).var;
        InsnList seq = new InsnList();
        seq.add(new TypeInsnNode(Opcodes.NEW, "java/lang/RuntimeException"));
        seq.add(new InsnNode(Opcodes.DUP));
        seq.add(new VarInsnNode(Opcodes.ALOAD, var));
        seq.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/RuntimeException", "<init>",
                "(Ljava/lang/Throwable;)V", false));
        seq.add(new InsnNode(Opcodes.ATHROW));
        mn.instructions.insert(insn, seq);
    }
}
