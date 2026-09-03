import org.objectweb.asm.*;
import java.io.*;
import java.util.zip.*;

/**
 * Patches Containers.class to change invokedynamic descriptors that produce
 * com.google.common.base.Predicate lambdas to produce java.util.function.Predicate instead.
 */
public class ContainersPredicatePatcher {
    private static final String GUAVA_PREDICATE = "com/google/common/base/Predicate";
    private static final String JDK_PREDICATE = "java/util/function/Predicate";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ContainersPredicatePatcher <gama.core.jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }
        processJar(jarFile);
    }

    public static void processJar(File jarFile) throws Exception {
        ZipFile zipIn = new ZipFile(jarFile);
        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));
        boolean patched = false;

        java.util.Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            byte[] data;
            try (InputStream is = zipIn.getInputStream(entry)) { data = is.readAllBytes(); }

            if (entry.getName().equals("gama/gaml/operators/Containers.class")) {
                data = patchContainersClass(data);
                if (data != null) {
                    patched = true;
                    System.out.println("Patched: " + entry.getName());
                }
            }

            zipOut.putNextEntry(new ZipEntry(entry.getName()));
            zipOut.write(data);
            zipOut.closeEntry();
        }

        zipIn.close();
        zipOut.close();

        if (patched) {
            jarFile.delete();
            tmpJar.renameTo(jarFile);
            System.out.println("JAR updated: " + jarFile.getName());
        } else {
            tmpJar.delete();
            System.out.println("No changes needed");
        }
    }

    static byte[] patchContainersClass(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String type1, String type2) {
                try { return super.getCommonSuperClass(type1, type2); }
                catch (RuntimeException e) { return "java/lang/Object"; }
            }
        };

        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                    String signature, String[] exceptions) {
                String newDescriptor = replacePredicate(descriptor);
                String newSignature = replacePredicate(signature);

                return new MethodVisitor(Opcodes.ASM9,
                        super.visitMethod(access, name, newDescriptor, newSignature, exceptions)) {
                    @Override
                    public void visitInvokeDynamicInsn(String indyName, String indyDescriptor,
                            Handle bootstrapMethodHandle, Object... bootstrapMethodArguments) {
                        String newIndyDesc = replacePredicate(indyDescriptor);

                        Object[] newArgs = new Object[bootstrapMethodArguments.length];
                        for (int i = 0; i < bootstrapMethodArguments.length; i++) {
                            Object arg = bootstrapMethodArguments[i];
                            if (arg instanceof String s && s.contains(GUAVA_PREDICATE)) {
                                newArgs[i] = s.replace(GUAVA_PREDICATE, JDK_PREDICATE);
                            } else if (arg instanceof Type t) {
                                String tDesc = t.getDescriptor();
                                if (tDesc.contains(GUAVA_PREDICATE)) {
                                    newArgs[i] = Type.getType(tDesc.replace(GUAVA_PREDICATE, JDK_PREDICATE));
                                } else {
                                    newArgs[i] = arg;
                                }
                            } else {
                                newArgs[i] = arg;
                            }
                        }

                        super.visitInvokeDynamicInsn(indyName, newIndyDesc,
                                bootstrapMethodHandle, newArgs);
                    }
                };
            }
        };

        cr.accept(cv, 0);
        return cw.toByteArray();
    }

    static String replacePredicate(String s) {
        if (s == null) return null;
        return s.replace(GUAVA_PREDICATE, JDK_PREDICATE);
    }
}
