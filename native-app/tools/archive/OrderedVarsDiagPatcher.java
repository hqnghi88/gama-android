import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Diagnostic patcher: injects logging into AbstractPopulation to reveal why
 * orderAttributes() returns an array containing a null IVariable.
 *
 * 1. In orderAttributes(), right before each areturn, dumps the DAG vertex
 *    names and whether species.getVar(name) resolves them.
 * 2. In <init>(), right after orderedVars is stored, dumps the produced
 *    IVariable[] with nulls marked.
 */
public class OrderedVarsDiagPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: OrderedVarsDiagPatcher <gama.core.jar>");
            System.exit(1);
        }
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

            if (entry.getName().equals("gama/core/population/AbstractPopulation.class")) {
                ClassReader cr = new ClassReader(data);
                ClassNode cn = new ClassNode();
                cr.accept(cn, 0);

                boolean ok = false;
                List<MethodNode> toAdd = new ArrayList<>();
                for (MethodNode mn : new ArrayList<>(cn.methods)) {
                    if ("<init>".equals(mn.name)
                            && "(Lgama/api/kernel/agent/IMacroAgent;Lgama/api/kernel/species/ISpecies;)V"
                                    .equals(mn.desc)) {
                        ok |= patchConstructor(cn, mn, toAdd);
                    }
                    if ("orderAttributes".equals(mn.name)
                            && "(Lgama/api/compilation/descriptions/ITypeDescription;Lcom/google/common/base/Predicate;Ljava/util/Set;)[Lgama/api/gaml/symbols/IVariable;"
                                    .equals(mn.desc)) {
                        ok |= patchOrderAttributes(cn, mn, toAdd);
                    }
                }
                cn.methods.addAll(toAdd);

                if (ok) {
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
                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
                    cn.accept(cw);
                    data = cw.toByteArray();
                    patched = true;
                    System.out.println("Patched AbstractPopulation with orderedVars diagnostics");
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

    static boolean patchConstructor(ClassNode cn, MethodNode mn, List<MethodNode> toAdd) {
        MethodInsnNode getField = new MethodInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out",
                "Ljava/io/PrintStream;", false);
        boolean found = false;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.PUTFIELD) {
                FieldInsnNode fin = (FieldInsnNode) insn;
                if ("orderedVars".equals(fin.name) && "[Lgama/api/gaml/symbols/IVariable;".equals(fin.desc)) {
                    InsnList toInsert = new InsnList();
                    toInsert.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    toInsert.add(new FieldInsnNode(Opcodes.GETFIELD,
                            "gama/core/population/AbstractPopulation", "orderedVars",
                            "[Lgama/api/gaml/symbols/IVariable;"));
                    toInsert.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    toInsert.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                            "gama/core/population/AbstractPopulation", "getSpecies",
                            "()Lgama/api/kernel/species/ISpecies;", false));
                    toInsert.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                            "gama/api/kernel/species/ISpecies", "getName", "()Ljava/lang/String;", true));
                    toInsert.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "gama/core/population/AbstractPopulation", "__diagOrderedVars",
                            "([Lgama/api/gaml/symbols/IVariable;Ljava/lang/String;)V", false));
                    mn.instructions.insert(insn, toInsert);
                    found = true;
                    break;
                }
            }
        }

        if (!found) {
            System.err.println("Could not find putfield orderedVars in constructor");
            return false;
        }

        // Add the static helper method
        MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "__diagOrderedVars",
                "([Lgama/api/gaml/symbols/IVariable;Ljava/lang/String;)V", null, null);
        InsnList il = helper.instructions;

        // StringBuilder sb = new StringBuilder();
        il.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 2));
        // sb.append("POP=")
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new LdcInsnNode("POP="));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        // sb.append(speciesName)
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        // sb.append(" len=")
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new LdcInsnNode(" len="));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        // sb.append(arr.length)
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new InsnNode(Opcodes.ARRAYLENGTH));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(I)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        // sb.append(" [")
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new LdcInsnNode(" ["));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        // int i = 0
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ISTORE, 3));
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        LabelNode hasName = new LabelNode();
        LabelNode afterName = new LabelNode();
        il.add(loopStart);
        // if (i >= arr.length) goto loopEnd
        il.add(new VarInsnNode(Opcodes.ILOAD, 3));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new InsnNode(Opcodes.ARRAYLENGTH));
        il.add(new JumpInsnNode(Opcodes.IF_ICMPGE, loopEnd));
        // sb.append(i)
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new VarInsnNode(Opcodes.ILOAD, 3));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(I)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        // sb.append('=')
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new IntInsnNode(Opcodes.BIPUSH, 61));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(C)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        // IVariable v = arr[i]
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new VarInsnNode(Opcodes.ILOAD, 3));
        il.add(new InsnNode(Opcodes.AALOAD));
        il.add(new VarInsnNode(Opcodes.ASTORE, 4));
        // if (v != null) goto hasName
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new JumpInsnNode(Opcodes.IFNONNULL, hasName));
        // sb.append("<NULL>")
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new LdcInsnNode("<NULL>"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        il.add(new JumpInsnNode(Opcodes.GOTO, afterName));
        il.add(hasName);
        // sb.append(v.getName())
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "gama/api/gaml/symbols/IVariable", "getName",
                "()Ljava/lang/String;", true));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        il.add(afterName);
        // sb.append(' ')
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new IntInsnNode(Opcodes.BIPUSH, 32));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(C)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        // i++
        il.add(new IincInsnNode(3, 1));
        il.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        il.add(loopEnd);
        // sb.append(']')
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new IntInsnNode(Opcodes.BIPUSH, 93));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(C)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        // System.out.println(sb)
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                "(Ljava/lang/Object;)V", false));
        il.add(new InsnNode(Opcodes.RETURN));
        helper.maxStack = 5;
        helper.maxLocals = 5;
        toAdd.add(helper);

        return true;
    }

    static boolean patchOrderAttributes(ClassNode cn, MethodNode mn, List<MethodNode> toAdd) {
        boolean found = false;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn.getOpcode() == Opcodes.ARETURN) {
                InsnList toInsert = new InsnList();
                // this, graph(local 4)
                toInsert.add(new VarInsnNode(Opcodes.ALOAD, 0));
                toInsert.add(new VarInsnNode(Opcodes.ALOAD, 4));
                toInsert.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                        "gama/core/population/AbstractPopulation", "__diagGraph",
                        "(Lgama/core/population/AbstractPopulation;Lorg/jgrapht/graph/DirectedAcyclicGraph;)V",
                        false));
                mn.instructions.insertBefore(insn, toInsert);
                found = true;
            }
        }

        if (!found) {
            System.err.println("Could not find areturn in orderAttributes");
            return false;
        }

        // Add the static helper method
        MethodNode helper = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "__diagGraph",
                "(Lgama/core/population/AbstractPopulation;Lorg/jgrapht/graph/DirectedAcyclicGraph;)V", null, null);
        InsnList il = helper.instructions;

        // StringBuilder sb = new StringBuilder();
        il.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 2));
        // sb.append("DAG species=")
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new LdcInsnNode("DAG species="));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        // sb.append(self.getSpecies().getName())
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "gama/core/population/AbstractPopulation", "getSpecies",
                "()Lgama/api/kernel/species/ISpecies;", false));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "gama/api/kernel/species/ISpecies", "getName",
                "()Ljava/lang/String;", true));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        // sb.append(" [")
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new LdcInsnNode(" ["));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        // Iterator it = graph.iterator()
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/Iterable", "iterator",
                "()Ljava/util/Iterator;", true));
        il.add(new VarInsnNode(Opcodes.ASTORE, 3));
        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();
        LabelNode okName = new LabelNode();
        LabelNode afterName = new LabelNode();
        il.add(loopStart);
        // if (!it.hasNext()) goto loopEnd
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "hasNext", "()Z", true));
        il.add(new JumpInsnNode(Opcodes.IFEQ, loopEnd));
        // String n = (String) it.next()
        il.add(new VarInsnNode(Opcodes.ALOAD, 3));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Iterator", "next", "()Ljava/lang/Object;", true));
        il.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        il.add(new VarInsnNode(Opcodes.ASTORE, 4));
        // sb.append(n)
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        // sb.append('=')
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new IntInsnNode(Opcodes.BIPUSH, 61));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(C)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        // if (self.getSpecies().getVar(n) != null) goto okName
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "gama/core/population/AbstractPopulation", "getSpecies",
                "()Lgama/api/kernel/species/ISpecies;", false));
        il.add(new VarInsnNode(Opcodes.ALOAD, 4));
        il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "gama/api/kernel/species/ISpecies", "getVar",
                "(Ljava/lang/String;)Lgama/api/gaml/symbols/IVariable;", true));
        il.add(new JumpInsnNode(Opcodes.IFNONNULL, okName));
        // sb.append("MISSING")
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new LdcInsnNode("MISSING"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        il.add(new JumpInsnNode(Opcodes.GOTO, afterName));
        il.add(okName);
        // sb.append("Y")
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new LdcInsnNode("Y"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        il.add(afterName);
        // sb.append(' ')
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new IntInsnNode(Opcodes.BIPUSH, 32));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(C)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        il.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        il.add(loopEnd);
        // sb.append(']')
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new IntInsnNode(Opcodes.BIPUSH, 93));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append",
                "(C)Ljava/lang/StringBuilder;", false));
        il.add(new InsnNode(Opcodes.POP));
        // System.out.println(sb)
        il.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out", "Ljava/io/PrintStream;"));
        il.add(new VarInsnNode(Opcodes.ALOAD, 2));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println",
                "(Ljava/lang/Object;)V", false));
        il.add(new InsnNode(Opcodes.RETURN));
        helper.maxStack = 5;
        helper.maxLocals = 5;
        toAdd.add(helper);

        return true;
    }
}
