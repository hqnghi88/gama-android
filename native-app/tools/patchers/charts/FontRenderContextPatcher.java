import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.jar.*;

/**
 * The bundled awt-stubs.jar ships a minimal java.awt.font.FontRenderContext that only
 * has a no-arg constructor. GAMA's java2d layer / JFreeChart obtain a FontRenderContext
 * via Graphics2D.getFontRenderContext(), which (in the stub) does:
 *     new FontRenderContext(transform, isAntiAliased, isFractionalTextScale)
 * The 3-arg (AffineTransform, boolean, boolean) constructor is therefore looked up at
 * runtime and NoSuchMethodError is thrown - blanketing every text/chart render on the
 * java2d display backend (i.e. every chart).
 *
 * This patcher injects that constructor (and the getTransform / isAntiAliased /
 * isFractionalTextScale accessors) into java/awt/font/FontRenderContext.class
 * inside awt-stubs.jar so chart rendering can construct and inspect a context.
 */
public class FontRenderContextPatcher {
    static final String TARGET = "java/awt/font/FontRenderContext.class";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Usage: FontRenderContextPatcher <jar>"); System.exit(1); }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found: " + jarFile); System.exit(1); }

        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        try (JarFile jar = new JarFile(jarFile);
             JarOutputStream jos = new JarOutputStream(new FileOutputStream(tmpJar))) {

            boolean patched = false;
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
                    patched = patchClass(cn);
                    if (patched) {
                        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
                        cn.accept(cw);
                        data = cw.toByteArray();
                        System.out.println("Added FontRenderContext transform ctor + accessors ("
                                + data.length + " bytes)");
                    }
                }
                jos.write(data);
                jos.closeEntry();
            }

            if (patched) {
                jar.close();
                if (!jarFile.delete()) { System.err.println("Failed to delete original JAR"); System.exit(1); }
                if (!tmpJar.renameTo(jarFile)) { System.err.println("Failed to rename temp JAR"); System.exit(1); }
                System.out.println("JAR updated: " + jarFile.getName());
            } else {
                System.err.println("WARNING: FontRenderContext class not found or already patched, JAR unchanged");
            }
        }
    }

    private static boolean patchClass(ClassNode cn) {
        // Add private fields if absent.
        boolean addedField = false;
        boolean hasTx = false, hasAa = false, hasFm = false;
        for (FieldNode f : cn.fields) {
            if (f.name.equals("frcTransform")) hasTx = true;
            if (f.name.equals("frcAntiAliased")) hasAa = true;
            if (f.name.equals("frcFractional")) hasFm = true;
        }
        if (!hasTx) { cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "frcTransform",
                "Ljava/awt/geom/AffineTransform;", null, null)); addedField = true; }
        if (!hasAa) { cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "frcAntiAliased", "Z", null, null)); addedField = true; }
        if (!hasFm) { cn.fields.add(new FieldNode(Opcodes.ACC_PRIVATE, "frcFractional", "Z", null, null)); addedField = true; }

        // Add the missing constructor and accessors only if not already present.
        boolean hasCtor = false, hasGetTx = false, hasGetAa = false, hasGetFm = false;
        for (MethodNode m : cn.methods) {
            if (m.name.equals("<init>") && m.desc.equals("(Ljava/awt/geom/AffineTransform;ZZ)V")) hasCtor = true;
            if (m.name.equals("getTransform") && m.desc.equals("()Ljava/awt/geom/AffineTransform;")) hasGetTx = true;
            if (m.name.equals("isAntiAliased") && m.desc.equals("()Z")) hasGetAa = true;
            if (m.name.equals("isFractionalTextScale") && m.desc.equals("()Z")) hasGetFm = true;
        }

        if (!hasCtor) {
            MethodNode ctor = new MethodNode(Opcodes.ACC_PUBLIC, "<init>",
                    "(Ljava/awt/geom/AffineTransform;ZZ)V", null, null);
            InsnList il = new InsnList();
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ALOAD, 1));
            il.add(new FieldInsnNode(Opcodes.PUTFIELD, "java/awt/font/FontRenderContext", "frcTransform", "Ljava/awt/geom/AffineTransform;"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 2));
            il.add(new FieldInsnNode(Opcodes.PUTFIELD, "java/awt/font/FontRenderContext", "frcAntiAliased", "Z"));
            il.add(new VarInsnNode(Opcodes.ALOAD, 0));
            il.add(new VarInsnNode(Opcodes.ILOAD, 3));
            il.add(new FieldInsnNode(Opcodes.PUTFIELD, "java/awt/font/FontRenderContext", "frcFractional", "Z"));
            il.add(new InsnNode(Opcodes.RETURN));
            ctor.instructions = il;
            cn.methods.add(ctor);
            System.out.println("  + constructor <init>(AffineTransform, Z, Z)");
        }
        if (!hasGetTx) {
            cn.methods.add(getter("getTransform", "()Ljava/awt/geom/AffineTransform;", "frcTransform", Opcodes.GETFIELD));
            System.out.println("  + getTransform()");
        }
        if (!hasGetAa) {
            cn.methods.add(getter("isAntiAliased", "()Z", "frcAntiAliased", Opcodes.GETFIELD));
            System.out.println("  + isAntiAliased()");
        }
        if (!hasGetFm) {
            cn.methods.add(getter("isFractionalTextScale", "()Z", "frcFractional", Opcodes.GETFIELD));
            System.out.println("  + isFractionalTextScale()");
        }
        return addedField || !hasCtor || !hasGetTx || !hasGetAa || !hasGetFm;
    }

    private static MethodNode getter(String name, String desc, String field, int opcode) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC, name, desc, null, null);
        InsnList il = new InsnList();
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new FieldInsnNode(opcode, "java/awt/font/FontRenderContext", field,
                desc.equals("()Z") ? "Z" : "Ljava/awt/geom/AffineTransform;"));
        il.add(new InsnNode(desc.equals("()Z") ? Opcodes.IRETURN : Opcodes.ARETURN));
        m.instructions = il;
        return m;
    }
}
