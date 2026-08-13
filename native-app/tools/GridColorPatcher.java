import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches gama.core GridLayerData.computeImage() so that, on Android, the grid's
 * per-cell colors (the cell species' built-in {@code color} variable, which GAMA
 * writes into IGrid.supportImagePixels() via GridSkill.setColor during step) are
 * copied into the display BufferedImage — mirroring what GridLayer.privateDraw
 * does on desktop (arraycopy of grid.getDisplayData() into the image raster).
 *
 * On Android the per-cell attributes are not reachable (cells are bare proxy
 * MinimalAgents without the species vars when use_regular_agents:false), so we
 * read the canonical color source supportImagePixels[idx] instead, exactly as
 * GridSkill.getColor does for a rectangular grid.
 */
public class GridColorPatcher {

    static final String TARGET = "gama/core/outputs/layers/GridLayerData.class";
    static final String METHOD = "computeImage";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Usage: GridColorPatcher <gama.core.jar>"); System.exit(1); }
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
            try (InputStream is = zipIn.getInputStream(entry)) { data = is.readAllBytes(); }

            if (entry.getName().equals(TARGET)) {
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);

                for (MethodNode mn : cn.methods) {
                    if (!mn.name.equals(METHOD)) continue;
                    InsnList insns = mn.instructions;
                    if (insns == null || insns.size() == 0) continue;

                    boolean alreadyPatched = false;
                    for (AbstractInsnNode n = insns.getFirst(); n != null; n = n.getNext()) {
                        if (n instanceof LdcInsnNode && "GRID_FILL".equals(((LdcInsnNode) n).cst)) {
                            alreadyPatched = true; break;
                        }
                    }
                    if (alreadyPatched) { System.out.println("Already patched: " + TARGET + " (skipped)"); continue; }

                    AbstractInsnNode ret = null;
                    for (AbstractInsnNode n = insns.getLast(); n != null; n = n.getPrevious()) {
                        if (n.getOpcode() == Opcodes.RETURN) { ret = n; break; }
                    }
                    if (ret == null) continue;

                    insns.insertBefore(ret, buildFill());
                    patched = true;
                }

                if (patched) {
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
                        @Override
                        protected String getCommonSuperClass(String t1, String t2) {
                            try { return super.getCommonSuperClass(t1, t2); }
                            catch (Exception e) { return "java/lang/Object"; }
                        }
                    };
                    cn.accept(cw);
                    data = cw.toByteArray();
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
            System.out.println("Patched: " + TARGET + " " + METHOD + " -> copies grid cell colors into display image");
        } else {
            tmpJar.delete();
            System.out.println("No targets found");
        }
    }

    // ---- bytecode builders -------------------------------------------------

    private static void pushString(InsnList il, String s) { il.add(new LdcInsnNode(s)); }

    /** Emits: Log.i("GRID_FILL", "<prefix>" + <int local>); */
    private static void logInt(InsnList il, String prefix, int localVar) {
        pushString(il, "GRID_FILL");
        il.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        pushString(il, prefix);
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        il.add(new VarInsnNode(Opcodes.ILOAD, localVar));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(I)Ljava/lang/StringBuilder;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "toString",
                "()Ljava/lang/String;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "android/util/Log", "i",
                "(Ljava/lang/String;Ljava/lang/String;)I", false));
        il.add(new InsnNode(Opcodes.POP));
    }

    private static InsnList buildFill() {
        InsnList il = new InsnList();
        LabelNode skip = new LabelNode();

        // grid (local 3)
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "gama/core/outputs/layers/GridLayerData",
                "getGrid", "()Lgama/api/kernel/topology/IGrid;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 3));

        // supportImagePixels (local 4)
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "gama/api/kernel/topology/IGrid",
                "supportImagePixels", "()[I", true));
        il.add(new VarInsnNode(Opcodes.ASTORE, 4));

        // BufferedImage image = getImage() (local 6)
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "gama/core/outputs/layers/GridLayerData",
                "getImage", "()Ljava/awt/image/BufferedImage;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 6));

        // null guards -> skip
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new JumpInsnNode(Opcodes.IFNULL, skip));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new JumpInsnNode(Opcodes.IFNULL, skip));
        il.add(new VarInsnNode(Opcodes.ALOAD, 6));
        il.add(new JumpInsnNode(Opcodes.IFNULL, skip));

        // int[] rasterData = ((DataBufferInt) image.getRaster().getDataBuffer()).getData(); (local 7)
        il.add(new VarInsnNode(Opcodes.ALOAD, 6));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/awt/image/BufferedImage", "getRaster",
                "()Ljava/awt/image/WritableRaster;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/awt/image/WritableRaster", "getDataBuffer",
                "()Ljava/awt/image/DataBuffer;", false));
        il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/awt/image/DataBufferInt"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/awt/image/DataBufferInt", "getData",
                "()[I", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 7));

        // diagnostics: pixLen (store length to local 8, log via logInt)
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new InsnNode(Opcodes.ARRAYLENGTH));
        il.add(new VarInsnNode(Opcodes.ISTORE, 8));
        logInt(il, "diag pixLen=", 8);

        // diagnostics: pix[0] when pixels.length > 0
        il.add(new VarInsnNode(Opcodes.ILOAD, 8));
        il.add(new JumpInsnNode(Opcodes.IFLE, skip));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new InsnNode(Opcodes.IALOAD));
        il.add(new VarInsnNode(Opcodes.ISTORE, 9));
        logInt(il, "diag pix[0]=", 9);

        // n = Math.min(pixels.length, rasterData.length)
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new InsnNode(Opcodes.ARRAYLENGTH));
        il.add(new VarInsnNode(Opcodes.ALOAD, 7));
        il.add(new InsnNode(Opcodes.ARRAYLENGTH));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "min", "(II)I", false));
        il.add(new VarInsnNode(Opcodes.ISTORE, 10));

        // System.arraycopy(pixels, 0, rasterData, 0, n)
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 7));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 10));
        il.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "arraycopy",
                "(Ljava/lang/Object;ILjava/lang/Object;II)V", false));

        il.add(skip);
        return il;
    }
}
