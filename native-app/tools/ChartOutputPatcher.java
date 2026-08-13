import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.jar.*;

/**
 * Patches ChartJFreeChartOutput.getImage so that any exception thrown while
 * JFreeChart renders the chart (currently swallowed by the NPE/IOOBE/IAE catch)
 * is logged to System.err and rethrown, exposing the root cause of the blank
 * Android charts.
 *
 * The swallow handler's body is:  pop ; aload g2D ; dispose ; goto return
 * We replace the leading `pop` with:
 *     dup ; getstatic System.err ; invokevirtual Throwable.printStackTrace(PrintStream) ; athrow
 * which prints the stack (to logcat) and rethrows, so the outer finally still
 * disposes g2D.
 */
public class ChartOutputPatcher {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Usage: ChartOutputPatcher <jar>"); System.exit(1); }
        String jarPath = args[0];
        File jarFile = new File(jarPath);
        if (!jarFile.exists()) { System.err.println("JAR not found: " + jarPath); System.exit(1); }

        File tempJar = new File(jarFile.getParent(), jarFile.getName() + ".tmp");
        boolean patched = false;

        try (JarFile jar = new JarFile(jarFile);
             JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar))) {

            java.util.Enumeration<JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                InputStream is = jar.getInputStream(entry);
                jos.putNextEntry(new JarEntry(entry.getName()));

                if (entry.getName().equals("gama/core/outputs/layers/charts/ChartJFreeChartOutput.class")) {
                    byte[] orig = readAll(is);
                    byte[] out = patch(orig);
                    jos.write(out);
                    System.out.println("Patched ChartJFreeChartOutput.getImage: swallow->log+rethrow ("
                            + orig.length + " -> " + out.length + " bytes)");
                    patched = true;
                } else {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) != -1) jos.write(buf, 0, n);
                }
                jos.closeEntry();
            }
        }

        if (patched) {
            jarFile.delete();
            tempJar.renameTo(jarFile);
            System.out.println("ChartOutputPatcher: " + jarFile.getAbsolutePath() + " patched");
        } else {
            tempJar.delete();
            System.out.println("ChartOutputPatcher: target class not found (nothing patched)");
        }
    }

    static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
        return baos.toByteArray();
    }

    static byte[] patch(byte[] bytes) throws Exception {
        ClassReader cr = new ClassReader(bytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String t1, String t2) {
                try { return super.getCommonSuperClass(t1, t2); }
                catch (Throwable t) { return "java/lang/Object"; }
            }
        };
        ClassNode cn = new ClassNode();
        cr.accept(cn, ClassReader.SKIP_DEBUG);
        for (MethodNode mn : cn.methods) {
            if ("getImage".equals(mn.name) && mn.desc.equals("(IIZ)Ljava/awt/image/BufferedImage;")) {
                patchGetImage(mn);
            }
        }
        cn.accept(cw);
        return cw.toByteArray();
    }

    static void patchGetImage(MethodNode mn) {
        // Find the swallow catch handler: one of the IOOBE/IAE/NPE handlers.
        LabelNode handler = null;
        for (TryCatchBlockNode tc : mn.tryCatchBlocks) {
            if (tc.type != null && (tc.type.equals("java/lang/IndexOutOfBoundsException")
                    || tc.type.equals("java/lang/IllegalArgumentException")
                    || tc.type.equals("java/lang/NullPointerException"))) {
                handler = tc.handler;
                break;
            }
        }
        if (handler == null) {
            System.out.println("ChartOutputPatcher: swallow handler not found; skipping");
            return;
        }
        // The first non-frame instruction after the handler label should be the
        // swallow handler's leading POP (discarding the caught exception).
        AbstractInsnNode first = handler.getNext();
        while (first != null && first.getType() == AbstractInsnNode.FRAME) {
            first = first.getNext();
        }
        if (first == null || !(first instanceof InsnNode) || ((InsnNode) first).getOpcode() != Opcodes.POP) {
            System.out.println("ChartOutputPatcher: expected POP at handler; got "
                    + (first == null ? "null" : first.getClass().getSimpleName()));
            return;
        }

        // Replace the swallow (pop; then fall through to dispose+return) with:
        //   dup ; getstatic System.err ; invokevirtual Throwable.printStackTrace(PrintStream) ; athrow
        // (System.err is wired to logcat on Android, so the stack reaches logcat.)
        InsnList repl = new InsnList();
        repl.add(new InsnNode(Opcodes.DUP));
        repl.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System",
                "err", "Ljava/io/PrintStream;"));
        repl.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Throwable",
                "printStackTrace", "(Ljava/io/PrintStream;)V", false));
        repl.add(new InsnNode(Opcodes.ATHROW));

        mn.instructions.insertBefore(first, repl);
        mn.instructions.remove(first);
        System.out.println("ChartOutputPatcher: injected log+rethrow into getImage catch");
    }
}
