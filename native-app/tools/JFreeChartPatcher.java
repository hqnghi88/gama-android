import org.objectweb.asm.*;
import java.io.*;
import java.util.jar.*;

/**
 * Patches JFreeChart class to wrap constructor body in try-catch(Throwable)
 * after super() call, so missing AWT methods don't crash chart creation on Android.
 */
public class JFreeChartPatcher {
    public static void main(String[] args) throws Exception {
        String jarPath = args[0];
        File jarFile = new File(jarPath);
        File tempJar = new File(jarFile.getParent(), "jfreechart_patched.jar");

        try (JarFile jar = new JarFile(jarFile);
             JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar))) {

            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                InputStream is = jar.getInputStream(entry);
                jos.putNextEntry(new JarEntry(entry.getName()));

                if (entry.getName().equals("org/jfree/chart/JFreeChart.class")) {
                    byte[] originalBytes = readAllBytes(is);
                    ClassReader cr = new ClassReader(originalBytes);
                    ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
                    
                    ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
                        @Override
                        public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
                            MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                            if (name.equals("<init>")) {
                                return new ConstructorPatcher(mv, access, desc);
                            }
                            return mv;
                        }
                    };
                    cr.accept(cv, 0);
                    byte[] patched = cw.toByteArray();
                    jos.write(patched);
                    System.out.println("Patched JFreeChart.class (" + originalBytes.length + " -> " + patched.length + " bytes)");
                } else {
                    byte[] buffer = new byte[4096];
                    int bytesRead;
                    while ((bytesRead = is.read(buffer)) != -1) {
                        jos.write(buffer, 0, bytesRead);
                    }
                }
                jos.closeEntry();
            }
        }

        if (jarFile.delete()) {
            tempJar.renameTo(jarFile);
            System.out.println("JFreeChart JAR patched");
        }
    }

    static byte[] readAllBytes(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    /**
     * Wraps constructor body after super() in try-catch(Throwable).
     * On exception: swallows it, object is partially initialized but usable.
     */
    static class ConstructorPatcher extends MethodVisitor {
        private final Label tryStart = new Label();
        private final Label catchHandler = new Label();
        private int superCallSeen = 0;
        private final int argCount;

        ConstructorPatcher(MethodVisitor mv, int access, String desc) {
            super(Opcodes.ASM9, mv);
            this.argCount = Type.getArgumentTypes(desc).length + 1; // +1 for 'this'
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name, String mdesc, boolean isInterface) {
            super.visitMethodInsn(opcode, owner, name, mdesc, isInterface);
            if (opcode == Opcodes.INVOKESPECIAL && name.equals("<init>") && superCallSeen == 0) {
                superCallSeen = 1;
                // After super() completes, start try block
                visitLabel(tryStart);
            }
        }

        @Override
        public void visitInsn(int opcode) {
            // Before any RETURN instruction, insert catch handler
            if (opcode == Opcodes.RETURN && superCallSeen == 1) {
                superCallSeen = 2;
                // Insert: goto end of catch, then catch handler
                Label afterCatch = new Label();
                super.visitJumpInsn(Opcodes.GOTO, afterCatch);
                // Catch handler: pop the exception and return
                visitLabel(catchHandler);
                visitInsn(Opcodes.POP); // pop the Throwable
                visitInsn(Opcodes.RETURN);
                // Register try-catch block
                try {
                    visitTryCatchBlock(tryStart, catchHandler, catchHandler, null);
                } catch (Exception e) {
                    // null means catch-all in some ASM versions
                }
                visitLabel(afterCatch);
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(int maxStack, int maxLocals) {
            // If super was never called or no return was wrapped, still register the try-catch
            if (superCallSeen == 1) {
                // Catch handler already inserted by visitInsn
            }
            super.visitMaxs(maxStack + 2, maxLocals);
        }
    }
}
