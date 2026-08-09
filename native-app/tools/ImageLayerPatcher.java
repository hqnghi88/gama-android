import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches ImageLayer.computeEnvelope to add a null check for scope.getSimulation().
 * Without this, the ImageLayer constructor throws NPE during LayerManager.<init>
 * because the simulation hasn't started yet.
 */
public class ImageLayerPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ImageLayerPatcher <jar>");
            System.exit(1);
        }
        processJar(new File(args[0]));
    }

    private static void processJar(File jarFile) throws Exception {
        File tmpJar = new File(jarFile.getAbsolutePath() + ".il_tmp");

        ZipFile zipIn = new ZipFile(jarFile);
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));

        boolean found = false;
        Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            byte[] data;
            try (InputStream is = zipIn.getInputStream(entry)) {
                data = is.readAllBytes();
            }

            if (entry.getName().equals("gama/core/outputs/layers/ImageLayer.class")) {
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);

                boolean patched = patchComputeEnvelope(cn);
                if (patched) {
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS) {
                        @Override
                        protected String getCommonSuperClass(String type1, String type2) {
                            try { return super.getCommonSuperClass(type1, type2); }
                            catch (Exception e) { return "java/lang/Object"; }
                        }
                    };
                    cn.accept(cw);
                    data = cw.toByteArray();
                    found = true;
                    System.out.println("ImageLayerPatcher: patched computeEnvelope with null check");
                } else {
                    System.out.println("ImageLayerPatcher: WARNING - computeEnvelope not found");
                }
            }

            zipOut.putNextEntry(new ZipEntry(entry.getName()));
            zipOut.write(data);
            zipOut.closeEntry();
        }

        zipIn.close();
        zipOut.close();

        jarFile.delete();
        tmpJar.renameTo(jarFile);

        if (!found) {
            System.err.println("ImageLayerPatcher: WARNING - ImageLayer.class not found in jar");
        }
    }

    private static boolean patchComputeEnvelope(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            if ("computeEnvelope".equals(mn.name) &&
                "(Lgama/core/runtime/IScope;Lgama/core/common/interfaces/IImageProvider;)Lgama/core/common/geometry/Envelope3D;".equals(mn.desc)) {

                InsnList insns = mn.instructions;
                // Find the pattern:
                //   aload_1 (scope)
                //   invokeinterface IScope.getSimulation()
                //   invokevirtual SimulationAgent.getEnvelope()
                //   areturn
                //
                // Replace with:
                //   aload_1 (scope)
                //   invokeinterface IScope.getSimulation()
                //   dup
                //   ifnonnull L1
                //   pop
                //   aconst_null
                //   areturn
                //   L1:
                //   invokevirtual SimulationAgent.getEnvelope()
                //   areturn

                for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn.getOpcode() == Opcodes.INVOKEINTERFACE) {
                        MethodInsnNode min = (MethodInsnNode) insn;
                        if ("getSimulation".equals(min.name) &&
                            "gama/core/runtime/IScope".equals(min.owner)) {
                            // Check next instruction is invokevirtual getEnvelope
                            AbstractInsnNode next = insn.getNext();
                            if (next != null && next.getOpcode() == Opcodes.INVOKEVIRTUAL) {
                                MethodInsnNode nextMin = (MethodInsnNode) next;
                                if ("getEnvelope".equals(nextMin.name) &&
                                    "gama/core/kernel/simulation/SimulationAgent".equals(nextMin.owner)) {

                                    LabelNode label = new LabelNode();

                                    InsnList patch = new InsnList();
                                    patch.add(new InsnNode(Opcodes.DUP));
                                    patch.add(new JumpInsnNode(Opcodes.IFNONNULL, label));
                                    patch.add(new InsnNode(Opcodes.POP));
                                    patch.add(new InsnNode(Opcodes.ACONST_NULL));
                                    patch.add(new InsnNode(Opcodes.ARETURN));
                                    patch.add(label);

                                    insns.insert(insn, patch);
                                    return true;
                                }
                            }
                        }
                    }
                }
            }
        }
        return false;
    }
}
