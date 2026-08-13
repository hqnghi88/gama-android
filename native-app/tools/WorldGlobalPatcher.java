import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches GamlAdditions.lambda$86 — the value-getter for the built-in {@code world}/{@code simulation}
 * global variable. Its body is:
 *     ((SimulationAgent) owner).getSimulation()
 *
 * On Android a {@code type: gui} experiment is self-simulating: the owner is an
 * {@code ExperimentAgent}, which does NOT extend {@code gama.core.simulation.SimulationAgent}, so the
 * {@code checkcast SimulationAgent} throws ClassCastException ("wrong casting") on every step. That
 * breaks any model using {@code world} / {@code simulation} (e.g. "Ant Foraging (Charts examples)").
 *
 * {@code getSimulation()} is a *default* method on the root agent interface
 * {@code gama.api.kernel.agent.IAgent}, implemented (overridden) by BOTH
 * {@code ExperimentAgent} and {@code SimulationAgent}. Casting to {@code IAgent} therefore works for
 * both the desktop (SimulationAgent owner) and Android (ExperimentAgent owner) cases.
 *
 * Fix: replace {@code checkcast SimulationAgent} with {@code checkcast IAgent} and redirect the
 * following {@code invokevirtual SimulationAgent.getSimulation()} to {@code invokevirtual IAgent.getSimulation()}.
 */
public class WorldGlobalPatcher {

    static final String SIM_OWNER = "gama/core/simulation/SimulationAgent";
    static final String AGENT_IFACE = "gama/api/kernel/agent/IAgent";
    static final String SIM_METHOD = "getSimulation";
    static final String SIM_DESC = "()Lgama/api/kernel/simulation/ISimulationAgent;";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: WorldGlobalPatcher <gama.core.jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

        String targetClass = "gaml/additions/core/GamlAdditions.class";

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
                    if (!mn.name.equals("lambda$86")) continue;
                    InsnList insns = mn.instructions;
                    if (insns == null || insns.size() == 0) continue;
                    int count = 0;
                    AbstractInsnNode node = insns.getFirst();
                    while (node != null) {
                        AbstractInsnNode next = node.getNext();
                        AbstractInsnNode op = node;
                        boolean isSimCast = op.getType() == AbstractInsnNode.TYPE_INSN
                                && op.getOpcode() == Opcodes.CHECKCAST
                                && (SIM_OWNER.equals(((TypeInsnNode) op).desc)
                                    || AGENT_IFACE.equals(((TypeInsnNode) op).desc));
                        boolean isGetSim = isSimCast && next != null
                                && next.getType() == AbstractInsnNode.METHOD_INSN
                                && (next.getOpcode() == Opcodes.INVOKEVIRTUAL || next.getOpcode() == Opcodes.INVOKEINTERFACE)
                                && (SIM_OWNER.equals(((MethodInsnNode) next).owner)
                                    || AGENT_IFACE.equals(((MethodInsnNode) next).owner))
                                && SIM_METHOD.equals(((MethodInsnNode) next).name);
                        if (isGetSim) {
                            // Cast to the root IAgent interface (both ExperimentAgent and SimulationAgent
                            // implement it; ExperimentAgent is the Android self-simulating owner).
                            ((TypeInsnNode) op).desc = AGENT_IFACE;
                            // getSimulation is a DEFAULT method on IAgent -> must use invokeinterface
                            // (invokevirtual on an interface owner raises IncompatibleClassChangeError).
                            MethodInsnNode m = (MethodInsnNode) next;
                            m.owner = AGENT_IFACE;
                            m.setOpcode(Opcodes.INVOKEINTERFACE);
                            m.itf = true;
                            count++;
                        }
                        node = next;
                    }
                    if (count > 0) {
                        patched = true;
                        System.out.println("Patched: " + targetClass + " lambda$86 -> checkcast/invokevirtual redirected to " + AGENT_IFACE);
                    }
                }

                if (patched) {
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS) {
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
            System.out.println("JAR updated: " + jarFile.getName());
        } else {
            tmpJar.delete();
            System.out.println("No targets found");
        }
    }
}
