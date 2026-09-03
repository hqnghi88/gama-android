import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class AscEnvelopeFixPatcher {
    static final String TARGET = "gama/core/util/file/GamaGridFile.class";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Usage: AscEnvelopeFixPatcher <jar>"); System.exit(1); }
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
                    if (mn.name.equals("customAscReader")
                            && mn.desc.equals("(Lgama/api/runtime/scope/IScope;)V")) {
                        if (patchCallSite(mn)) {
                            patched = true;
                            System.out.println("Patched " + TARGET
                                    + " customAscReader: GamaEnvelopeFactory.of arg order fixed");
                        }
                    }
                }

                if (patched) {
                    addOfAscMethod(cn);
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

    /**
     * GamaGridFile.customAscReader calls
     *   GamaEnvelopeFactory.of(xC, yC, xC + nbCols*dX, ascInfo[3], 0, 0)
     * but GamaEnvelopeFactory.of(a,b,c,d,e,f) -> IEnvelope.init(a,b,c,d) which is
     * JTS Envelope.init(x1,x2,y1,y2). The call passes (x1, y1, x2, y2) so the
     * envelope becomes [xC..yC]x[xC+cols*dX..ascInfo[3]] - the axis order is swapped.
     * Rewrite the invokestatic to GamaGridFile.ofAsc which reorders to (x1, x2, y1, y2).
     * Only affects the customAscReader call site; privateCreateCoverage's call is correct.
     */
    static boolean patchCallSite(MethodNode mn) {
        boolean changed = false;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode min)) continue;
            if (min.getOpcode() != Opcodes.INVOKESTATIC) continue;
            if (min.owner.equals("gama/api/utils/geometry/GamaEnvelopeFactory")
                    && min.name.equals("of")
                    && min.desc.equals("(DDDDDD)Lgama/api/utils/geometry/IEnvelope;")) {
                min.owner = "gama/core/util/file/GamaGridFile";
                min.name = "ofAsc";
                min.desc = "(DDDDDD)Lgama/api/utils/geometry/IEnvelope;";
                changed = true;
            }
        }
        return changed;
    }

    static void addOfAscMethod(ClassNode cn) {
        MethodNode mn = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "ofAsc",
                "(DDDDDD)Lgama/api/utils/geometry/IEnvelope;", null, null);
        InsnList il = mn.instructions;
        // return GamaEnvelopeFactory.of(a, c, b, d, e, f);
        il.add(new VarInsnNode(Opcodes.DLOAD, 0));
        il.add(new VarInsnNode(Opcodes.DLOAD, 4));
        il.add(new VarInsnNode(Opcodes.DLOAD, 2));
        il.add(new VarInsnNode(Opcodes.DLOAD, 6));
        il.add(new VarInsnNode(Opcodes.DLOAD, 8));
        il.add(new VarInsnNode(Opcodes.DLOAD, 10));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "gama/api/utils/geometry/GamaEnvelopeFactory", "of",
                "(DDDDDD)Lgama/api/utils/geometry/IEnvelope;", false));
        il.add(new InsnNode(Opcodes.ARETURN));
        mn.maxLocals = 12;
        mn.maxStack = 12;
        cn.methods.add(mn);
    }
}
