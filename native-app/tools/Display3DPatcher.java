import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches LayeredDisplayOutput.createSurface() to remove the3D early-return.
 *
 * Replaces:
 *   INVOKEVIRTUAL DisplayData.is3D ()Z
 *   IFEQ <skip>
 *   RETURN
 * With:
 *   POP (consume DisplayData from stack)
 *   GOTO <skip> (always jump past the RETURN)
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
                    if (!mn.name.equals("createSurface") || !mn.desc.equals("(Lgama/core/runtime/IScope;)V")) continue;

                    System.out.println("Found createSurface(IScope)");

                    List<AbstractInsnNode> insns = new ArrayList<>();
                    for (AbstractInsnNode n : mn.instructions) insns.add(n);

                    // Phase 1: Find all targets BEFORE any modification
                    int is3dIdx = -1, ifeqIdx = -1, skipLabelIdx = -1;
                    for (int i = 0; i < insns.size(); i++) {
                        AbstractInsnNode insn = insns.get(i);
                        if (insn instanceof MethodInsnNode mni
                                && mni.name.equals("is3D")
                                && mni.desc.equals("()Z")) {
                            is3dIdx = i;
                            // Find IFEQ after is3D
                            for (int j = i + 1; j < Math.min(insns.size(), i + 4); j++) {
                                if (insns.get(j) instanceof JumpInsnNode jni && jni.getOpcode() == Opcodes.IFEQ) {
                                    ifeqIdx = j;
                                    // The label target of IFEQ is where execution continues if is3D() is false
                                    skipLabelIdx = insns.indexOf(jni.label);
                                    break;
                                }
                            }
                            break;
                        }
                    }

                    if (is3dIdx < 0 || ifeqIdx < 0 || skipLabelIdx < 0) {
                        System.out.println("WARNING: Pattern not found (is3D@" + is3dIdx + " IFEQ@" + ifeqIdx + ")");
                        continue;
                    }

                    System.out.println("Found: is3D=@" + is3dIdx + " IFEQ=@" + ifeqIdx + " skip=@" + skipLabelIdx);

                    // Phase 2: Replace is3D() with POP, and IFEQ with GOTO <skip>
                    AbstractInsnNode is3dInsn = insns.get(is3dIdx);
                    AbstractInsnNode ifeqInsn = insns.get(ifeqIdx);
                    LabelNode skipLabel = ((JumpInsnNode) ifeqInsn).label;

                    // Replace is3D() with POP (consumes DisplayData from stack)
                    mn.instructions.set(is3dInsn, new InsnNode(Opcodes.POP));
                    System.out.println("Replaced is3D() with POP");

                    // Replace IFEQ with GOTO <skip> (always jump to where execution continues)
                    mn.instructions.set(ifeqInsn, new JumpInsnNode(Opcodes.GOTO, skipLabel));
                    System.out.println("Replaced IFEQ with GOTO <skip>");

                    patched = true;
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
                    System.out.println("Patched: is3D() early-return removed");
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
            System.out.println("No targets found");
        }
    }
}
