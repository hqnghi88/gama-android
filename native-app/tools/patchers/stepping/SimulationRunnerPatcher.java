import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches SimulationPopulation.stepAgents() to use the parent's stepping mechanism
 * instead of SimulationRunner.step() semaphore coordination.
 * 
 * Problem: SimulationPopulation.stepAgents() calls runner.step() which does:
 *   simulationsSemaphore.release(nb); experimentSemaphore.acquire(nb);
 * This deadlocks on Android because the experiment controller calls step() synchronously
 * on the same thread, so experimentSemaphore is never released by the simulation threads.
 * 
 * Fix: Replace runner.step() with super.stepAgents(scope) which uses
 * GamaExecutorService.step() — the normal agent stepping path.
 */
public class SimulationRunnerPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: SimulationRunnerPatcher <gama.core.jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

        String targetClass = "gama/core/kernel/simulation/SimulationPopulation.class";
        String parentClass = "gama/core/metamodel/population/GamaPopulation";

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
                    if (mn.name.equals("stepAgents") && mn.desc.equals("(Lgama/core/runtime/IScope;)Z")) {
                        // Replace body with: return super.stepAgents(scope);
                        mn.instructions.clear();
                        mn.tryCatchBlocks.clear();
                        mn.localVariables.clear();

                        InsnList insns = new InsnList();
                        // ALOAD 0 (this)
                        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        // ALOAD 1 (scope)
                        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        // INVOKESPECIAL GamaPopulation.stepAgents(IScope)Z
                        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                                parentClass, "stepAgents",
                                "(Lgama/core/runtime/IScope;)Z", false));
                        // IRETURN
                        insns.add(new InsnNode(Opcodes.IRETURN));

                        mn.instructions.insert(insns);
                        mn.maxStack = 2;
                        mn.maxLocals = 2;
                        patched = true;
                        System.out.println("Patched: " + targetClass + " -> stepAgents() calls super.stepAgents(scope)");
                    }
                }

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
