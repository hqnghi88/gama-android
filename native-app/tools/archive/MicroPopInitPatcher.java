package com.gama.nativeapp.asm;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Patches CreateStatement.findPopulation() to lazily initialize micro-populations
 * when getPopulationFor() returns null.
 *
 * The issue: In GAML, the init helper in SpeciesDescription lazily creates
 * micro-populations when a GAML variable is accessed. But CreateStatement.findPopulation()
 * calls executor.getPopulationFor(s) which goes through Java getMicroPopulation() →
 * getAttribute() — bypassing the lazy init helper. So the population isn't registered yet.
 *
 * Fix: After getPopulationFor returns null, if the executor is an IMacroAgent,
 * call initializeMicroPopulation(scope, species.getName()) then retry getPopulationFor.
 */
public class MicroPopInitPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: MicroPopInitPatcher <input-jar> <output-jar>");
            System.exit(1);
        }

        String inputJar = args[0];
        String outputJar = args[1];

        // Read all classes from the JAR
        Map<String, byte[]> classes = new LinkedHashMap<>();
        Map<String, byte[]> resources = new LinkedHashMap<>();

        try (java.util.jar.JarInputStream jis = new java.util.jar.JarInputStream(new FileInputStream(inputJar))) {
            java.util.jar.JarEntry entry;
            while ((entry = jis.getNextJarEntry()) != null) {
                byte[] data = jis.readAllBytes();
                if (entry.getName().endsWith(".class")) {
                    classes.put(entry.getName(), data);
                } else {
                    resources.put(entry.getName(), data);
                }
                jis.closeEntry();
            }
        }

        // Find and patch CreateStatement.class
        String targetPath = "gama/gaml/statements/CreateStatement.class";
        byte[] classBytes = classes.get(targetPath);
        if (classBytes == null) {
            System.err.println("ERROR: " + targetPath + " not found in JAR");
            System.exit(1);
        }

        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            if ("findPopulation".equals(mn.name) && "(Lgama/core/runtime/IScope;)Lgama/core/metamodel/population/IPopulation;".equals(mn.desc)) {
                patched = patchFindPopulation(cn, mn);
                break;
            }
        }

        if (!patched) {
            System.err.println("ERROR: Could not patch CreateStatement.findPopulation()");
            System.exit(1);
        }

        // Strip all existing stack map frames (let D8 recompute)
        for (MethodNode mn : cn.methods) {
            List<AbstractInsnNode> toRemove = new ArrayList<>();
            Iterator<AbstractInsnNode> it = mn.instructions.iterator();
            while (it.hasNext()) {
                AbstractInsnNode insn = it.next();
                if (insn instanceof FrameNode) {
                    toRemove.add(insn);
                }
            }
            for (AbstractInsnNode insn : toRemove) {
                mn.instructions.remove(insn);
            }
        }

        // Write patched class
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        classes.put(targetPath, cw.toByteArray());

        // Rebuild JAR
        try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(new FileOutputStream(outputJar))) {
            // Write manifest from original
            try (java.util.jar.JarInputStream jis = new java.util.jar.JarInputStream(new FileInputStream(inputJar))) {
                java.util.jar.Manifest manifest = jis.getManifest();
                if (manifest != null) {
                    jos.putNextEntry(new java.util.jar.JarEntry("META-INF/MANIFEST.MF"));
                    manifest.write(jos);
                    jos.closeEntry();
                }
            }

            for (Map.Entry<String, byte[]> e : classes.entrySet()) {
                jos.putNextEntry(new java.util.jar.JarEntry(e.getKey()));
                jos.write(e.getValue());
                jos.closeEntry();
            }
            for (Map.Entry<String, byte[]> e : resources.entrySet()) {
                jos.putNextEntry(new java.util.jar.JarEntry(e.getKey()));
                jos.write(e.getValue());
                jos.closeEntry();
            }
        }

        System.out.println("Patched CreateStatement.findPopulation() — lazy micro-pop init added");
        System.out.println("Written patched JAR to: " + outputJar);
    }

    static boolean patchFindPopulation(ClassNode cn, MethodNode mn) {
        AbstractInsnNode[] insns = mn.instructions.toArray();

        // Find the pattern:
        //   aload_2 (executor)
        //   aload_3 (species s)
        //   invokeinterface IAgent.getPopulationFor(ISpecies)
        //   astore 4 (pop)
        //
        // We insert AFTER astore 4:
        //   if (pop == null && executor instanceof IMacroAgent) {
        //     ((IMacroAgent) executor).initializeMicroPopulation(scope, s.getName());
        //     pop = executor.getPopulationFor(s);
        //   }

        int astorePopIdx = -1;
        for (int i = 0; i < insns.length; i++) {
            if (insns[i].getOpcode() == Opcodes.ASTORE) {
                VarInsnNode vin = (VarInsnNode) insns[i];
                if (vin.var == 4) {
                    // Check if the previous instruction is invokeinterface getPopulationFor
                    for (int j = i - 1; j >= Math.max(0, i - 5); j--) {
                        if (insns[j].getOpcode() == Opcodes.INVOKEINTERFACE) {
                            MethodInsnNode min = (MethodInsnNode) insns[j];
                            if ("getPopulationFor".equals(min.name) &&
                                "(Lgama/gaml/species/ISpecies;)Lgama/core/metamodel/population/IPopulation;".equals(min.desc)) {
                                astorePopIdx = i;
                                break;
                            }
                        }
                    }
                    if (astorePopIdx >= 0) break;
                }
            }
        }

        if (astorePopIdx < 0) {
            System.err.println("Could not find astore 4 after getPopulationFor(ISpecies) pattern");
            return false;
        }

        System.out.println("Found getPopulationFor pattern at instruction index " + astorePopIdx);

        // Create the lazy init code
        LabelNode skipInitLabel = new LabelNode();

        InsnList insertCode = new InsnList();

        // if (pop != null) goto skipInit
        insertCode.add(new VarInsnNode(Opcodes.ALOAD, 4));  // pop
        insertCode.add(new JumpInsnNode(Opcodes.IFNONNULL, skipInitLabel));

        // if (!(executor instanceof IMacroAgent)) goto skipInit
        insertCode.add(new VarInsnNode(Opcodes.ALOAD, 2));  // executor
        insertCode.add(new TypeInsnNode(Opcodes.INSTANCEOF, "gama/core/metamodel/agent/IMacroAgent"));
        insertCode.add(new JumpInsnNode(Opcodes.IFEQ, skipInitLabel));

        // ((IMacroAgent) executor).initializeMicroPopulation(scope, s.getName())
        insertCode.add(new VarInsnNode(Opcodes.ALOAD, 2));  // executor
        insertCode.add(new TypeInsnNode(Opcodes.CHECKCAST, "gama/core/metamodel/agent/IMacroAgent"));
        insertCode.add(new VarInsnNode(Opcodes.ALOAD, 1));  // scope
        insertCode.add(new VarInsnNode(Opcodes.ALOAD, 3));  // s
        insertCode.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "gama/gaml/species/ISpecies", "getName", "()Ljava/lang/String;", true));
        insertCode.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "gama/core/metamodel/agent/IMacroAgent", "initializeMicroPopulation",
                "(Lgama/core/runtime/IScope;Ljava/lang/String;)V", true));

        // pop = executor.getPopulationFor(s)
        insertCode.add(new VarInsnNode(Opcodes.ALOAD, 2));  // executor
        insertCode.add(new VarInsnNode(Opcodes.ALOAD, 3));  // s
        insertCode.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                "gama/core/metamodel/agent/IAgent", "getPopulationFor",
                "(Lgama/gaml/species/ISpecies;)Lgama/core/metamodel/population/IPopulation;", true));
        insertCode.add(new VarInsnNode(Opcodes.ASTORE, 4)); // pop = ...

        // skipInit:
        insertCode.add(skipInitLabel);

        // Insert after the astore 4
        mn.instructions.insert(insns[astorePopIdx], insertCode);

        System.out.println("Inserted lazy micro-population initialization code after getPopulationFor");

        return true;
    }
}
