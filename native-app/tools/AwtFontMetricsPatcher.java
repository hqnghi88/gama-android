import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.jar.*;

/**
 * The bundled awt-stubs.jar ships a java.awt.Font whose getLineMetrics(...)
 * methods all return null. JFreeChart's TextUtils.deriveTextBoundsAnchorOffsets
 * calls Font.getLineMetrics(...) and then metrics.getAscent(), which throws an
 * NPE - blanketing every chart render on Android (the NPE is swallowed by
 * ChartJFreeChartOutput.getImage, so charts render blank).
 *
 * This patcher rewrites every getLineMetrics*(...) method on java.awt.Font in
 * awt-stubs.jar so it returns a real LineMetrics whose ascent/descent/leading
 * are proportioned from the font size, so text measurement succeeds and chart.draw
 * completes (and fires its DRAWING_FINISHED progress event -> front/back swap).
 */
public class AwtFontMetricsPatcher {
    static final String TARGET = "java/awt/Font.class";

    public static void main(String[] args) throws Exception {
        File jarFile = new File(args[0]);
        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        boolean patched = false;

        try (JarFile jar = new JarFile(jarFile);
             JarOutputStream jos = new JarOutputStream(new FileOutputStream(tmpJar))) {

            java.util.Enumeration<? extends JarEntry> entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                jos.putNextEntry(new JarEntry(entry.getName()));
                byte[] data;
                try (InputStream is = jar.getInputStream(entry)) {
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
                    int n = patchFont(cn);
                    if (n > 0) {
                        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
                            @Override
                            protected String getCommonSuperClass(String t1, String t2) {
                                try { return super.getCommonSuperClass(t1, t2); }
                                catch (Throwable t) { return "java/lang/Object"; }
                            }
                        };
                        cn.accept(cw);
                        data = cw.toByteArray();
                        System.out.println("Patched java.awt.Font.getLineMetrics x" + n
                                + " (" + data.length + " bytes)");
                        patched = true;
                    }
                }
                jos.write(data);
                jos.closeEntry();
            }
        }

        if (patched) {
            jarFile.delete();
            tmpJar.renameTo(jarFile);
            System.out.println("JAR updated: " + jarFile.getName());
        } else {
            tmpJar.delete();
            System.err.println("WARNING: java/awt/Font.class not found in " + jarFile.getName());
        }
    }

    /** Replaces every getLineMetrics method on java.awt.Font with a body that
     *  returns new LineMetrics(size*0.8f, size*0.2f, 0f). */
    static int patchFont(ClassNode cn) {
        int count = 0;
        for (MethodNode mn : cn.methods) {
            if (mn.name.startsWith("getLineMetrics") && mn.desc.endsWith("Ljava/awt/font/LineMetrics;")) {
                mn.instructions = lineMetricsBody();
                mn.maxLocals = 0;
                mn.maxStack = 0;
                count++;
            }
        }
        return count;
    }

    /** InsnList: return new LineMetrics(getSize2D()*0.8f, getSize2D()*0.2f, 0f) */
    static InsnList lineMetricsBody() {
        InsnList il = new InsnList();
        il.add(new TypeInsnNode(Opcodes.NEW, "java/awt/font/LineMetrics"));
        il.add(new InsnNode(Opcodes.DUP));
        // ascent = getSize2D() * 0.8f
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/awt/Font",
                "getSize2D", "()F", false));
        il.add(new LdcInsnNode(0.8f));
        il.add(new InsnNode(Opcodes.FMUL));
        // descent = getSize2D() * 0.2f
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/awt/Font",
                "getSize2D", "()F", false));
        il.add(new LdcInsnNode(0.2f));
        il.add(new InsnNode(Opcodes.FMUL));
        // leading = 0f
        il.add(new LdcInsnNode(0.0f));
        // construct LineMetrics(ascent, descent, leading)
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/awt/font/LineMetrics",
                "<init>", "(FFF)V", false));
        il.add(new InsnNode(Opcodes.ARETURN));
        return il;
    }
}
