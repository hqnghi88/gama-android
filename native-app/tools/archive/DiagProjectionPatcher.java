import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class DiagProjectionPatcher {
    static final String TARGET = "gama/core/topology/gis/Projection.class";
    static final String SELF = "gama/core/topology/gis/Projection";
    static final String GET_TARGET_CRS = "(Lgama/api/runtime/scope/IScope;)Lgama/api/kernel/topology/ICoordinateReferenceSystem;";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Usage: DiagProjectionPatcher <jar>"); System.exit(1); }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

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

                cn.methods.add(pdiagMethod());

                for (MethodNode mn : cn.methods) {
                    if (mn.name.equals("<init>") && mn.desc.contains("IEnvelope;Lgama/api/kernel/topology/IProjectionFactory;")) {
                        System.out.println("Instrumenting <init>");
                        instrumentConstructor(mn);
                        patched = true;
                    }
                    if (mn.name.equals("transform") && mn.desc.equals("(Lgama/api/utils/geometry/IEnvelope;)Lgama/api/utils/geometry/IEnvelope;")) {
                        System.out.println("Instrumenting transform(IEnvelope)");
                        instrumentTransformEnv(mn);
                        patched = true;
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

    static MethodNode pdiagMethod() {
        MethodNode mn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pdiag",
            "(Ljava/lang/String;)V", null, null);
        mn.instructions.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out",
            "Ljava/io/PrintStream;"));
        mn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        mn.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream",
            "println", "(Ljava/lang/String;)V", false));
        mn.instructions.add(new InsnNode(Opcodes.RETURN));
        mn.maxStack = 2;
        mn.maxLocals = 1;
        return mn;
    }

    static InsnList buildLog(String prefix, int... loaders) {
        InsnList l = new InsnList();
        l.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        l.add(new LdcInsnNode(prefix));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        for (int slot : loaders) {
            l.add(new VarInsnNode(Opcodes.ALOAD, slot));
            l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        }
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
            "()Ljava/lang/String;", false));
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "pdiag",
            "(Ljava/lang/String;)V", false));
        return l;
    }

    static void instrumentConstructor(MethodNode mn) {
        AbstractInsnNode last = mn.instructions.getLast();
        while (last != null && last.getOpcode() != Opcodes.RETURN) last = last.getPrevious();
        if (last == null) return;

        InsnList l = new InsnList();
        l.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        l.add(new LdcInsnNode("PROJ init="));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        l.add(new VarInsnNode(Opcodes.ALOAD, 3));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        l.add(new LdcInsnNode(" target="));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        l.add(new VarInsnNode(Opcodes.ALOAD, 0));
        l.add(new VarInsnNode(Opcodes.ALOAD, 1));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, SELF, "getTargetCRS", GET_TARGET_CRS, false));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        l.add(new LdcInsnNode(" env="));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        l.add(new VarInsnNode(Opcodes.ALOAD, 4));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        l.add(new LdcInsnNode(" proj="));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        l.add(new VarInsnNode(Opcodes.ALOAD, 0));
        l.add(new FieldInsnNode(Opcodes.GETFIELD, SELF, "projectedEnv", "Lgama/api/utils/geometry/IEnvelope;"));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
            "()Ljava/lang/String;", false));
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "pdiag", "(Ljava/lang/String;)V", false));
        mn.instructions.insertBefore(last, l);
    }

    static void instrumentTransformEnv(MethodNode mn) {
        AbstractInsnNode ret = mn.instructions.getLast();
        while (ret != null && ret.getOpcode() != Opcodes.ARETURN) ret = ret.getPrevious();
        if (ret == null) return;

        InsnList l = new InsnList();
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new VarInsnNode(Opcodes.ASTORE, 2));
        l.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        l.add(new InsnNode(Opcodes.DUP));
        l.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        l.add(new LdcInsnNode("PROJTRANS in="));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        l.add(new VarInsnNode(Opcodes.ALOAD, 1));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        l.add(new LdcInsnNode(" out="));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        l.add(new VarInsnNode(Opcodes.ALOAD, 2));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
            "(Ljava/lang/Object;)Ljava/lang/StringBuilder;", false));
        l.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
            "()Ljava/lang/String;", false));
        l.add(new MethodInsnNode(Opcodes.INVOKESTATIC, SELF, "pdiag", "(Ljava/lang/String;)V", false));
        l.add(new VarInsnNode(Opcodes.ALOAD, 2));
        mn.instructions.insertBefore(ret, l);
    }
}
