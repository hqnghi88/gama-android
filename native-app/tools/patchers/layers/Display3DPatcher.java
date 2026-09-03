import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches LayeredDisplayOutput.createSurface() to remove the 3D early-return.
 *
 * The pristine jar returns early for 3D displays:
 *     aload_0
 *     getData()          // DisplayData
 *     is3D()Z
 *     IFEQ L
 *     RETURN
 * L:  ... createDisplaySurfaceFor ...
 *
 * so 3D displays never get a surface. We remove those 5 instructions entirely,
 * making 3D displays fall through to createDisplaySurfaceFor like the 2D ones
 * (matching the upstream change that commented the early return out).
 */
public class Display3DPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: Display3DPatcher <gama.core.jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

        String targetClass = "gama/core/outputs/LayeredDisplayOutput.class";

        ZipFile zipIn = new ZipFile(jarFile);
        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));
        boolean patched = false;

        Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            byte[] data;
            try (InputStream is = zipIn.getInputStream(entry)) { data = is.readAllBytes(); }

            if (entry.getName().equals(targetClass)) {
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);

                for (MethodNode mn : cn.methods) {
                    if (!mn.name.equals("createSurface")
                            || !mn.desc.equals("(Lgama/api/runtime/scope/IScope;)V")) continue;

                    List<AbstractInsnNode> insns = new ArrayList<>();
                    for (AbstractInsnNode n : mn.instructions) insns.add(n);

                    for (int i = 0; i < insns.size(); i++) {
                        AbstractInsnNode insn = insns.get(i);
                        if (!(insn instanceof MethodInsnNode mni)
                                || !mni.name.equals("is3D") || !mni.desc.equals("()Z")) continue;

                        // Preceding: getData() then aload_0
                        AbstractInsnNode prev = skipPseudo(insn.getPrevious());
                        AbstractInsnNode prev2 = prev == null ? null : skipPseudo(prev.getPrevious());
                        if (!(prev instanceof MethodInsnNode getData)
                                || !getData.name.equals("getData")) {
                            System.err.println("WARNING: getData() not found before is3D()");
                            continue;
                        }
                        if (!(prev2 instanceof VarInsnNode load) || load.getOpcode() != Opcodes.ALOAD
                                || load.var != 0) {
                            System.err.println("WARNING: aload_0 not found before getData()");
                            continue;
                        }

                        // Following: IFEQ, then RETURN
                        AbstractInsnNode next = skipPseudo(insn.getNext());
                        if (!(next instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.IFEQ) {
                            System.err.println("WARNING: IFEQ not found after is3D()");
                            continue;
                        }
                        AbstractInsnNode after = skipPseudo(jump.getNext());
                        if (!(after instanceof InsnNode ret) || ret.getOpcode() != Opcodes.RETURN) {
                            System.err.println("WARNING: RETURN not found after IFEQ");
                            continue;
                        }

                        // Remove the 5-instruction early-return block.
                        mn.instructions.remove(load);
                        mn.instructions.remove(getData);
                        mn.instructions.remove(insn);
                        mn.instructions.remove(jump);
                        mn.instructions.remove(ret);
                        patched = true;
                        System.out.println("Patched LayeredDisplayOutput.createSurface: "
                                + "3D early-return removed");
                        break;
                    }
                }

                if (patched) {
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                        @Override
                        protected String getCommonSuperClass(String type1, String type2) {
                            try { return super.getCommonSuperClass(type1, type2); }
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
            System.out.println("JAR updated: " + jarFile.getName());
        } else {
            tmpJar.delete();
            System.err.println("FATAL: Display3DPatcher found no targets. "
                    + "Check the createSurface method descriptor in LayeredDisplayOutput.");
            System.exit(1);
        }
    }

    static AbstractInsnNode skipPseudo(AbstractInsnNode n) {
        while (n != null && (n instanceof LabelNode || n instanceof LineNumberNode || n instanceof FrameNode)) {
            n = n.getPrevious();
        }
        return n;
    }
}
