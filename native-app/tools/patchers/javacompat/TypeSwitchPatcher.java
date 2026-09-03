import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches all invokedynamic typeSwitch calls (Java 21 pattern matching) to use
 * traditional instanceof + checkcast chains.
 *
 * Java 21's javac compiles chains of `if (obj instanceof Type t) ...` into:
 *   aload obj
 *   iload matchIdx
 *   invokedynamic typeSwitch:(Object, int) -> int
 *   tableswitch { -1: noMatch, 0: case0, 1: case1, ... default: fallthrough }
 *
 * D8/Android cannot handle this invokedynamic. This patcher replaces it with:
 *   aload obj
 *   instanceof Type0
 *   ifne case0_label
 *   aload obj
 *   instanceof Type1
 *   ifne case1_label
 *   ...
 *   goto default_label
 *
 * The case bodies (checkcast + work) and goto return_point are unchanged.
 */
public class TypeSwitchPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: TypeSwitchPatcher <jar> [jar2 ...]");
            System.exit(1);
        }

        for (String arg : args) {
            File jarFile = new File(arg);
            if (!jarFile.exists()) {
                System.err.println("JAR not found: " + arg);
                continue;
            }
            processJar(jarFile);
        }
    }

    private static void processJar(File jarFile) throws Exception {
        File tmpJar = new File(jarFile.getAbsolutePath() + ".typeswitch_tmp");
        int totalPatched = 0;

        ZipFile zipIn = new ZipFile(jarFile);
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));

        Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            byte[] data;
            try (InputStream is = zipIn.getInputStream(entry)) {
                data = is.readAllBytes();
            }

            if (entry.getName().endsWith(".class")) {
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);

                int patched = processClass(cn);
                if (patched > 0) {
                    totalPatched += patched;
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

        // Replace original with patched
        File origBak = new File(jarFile.getAbsolutePath() + ".typeswitch_bak");
        jarFile.renameTo(origBak);
        tmpJar.renameTo(jarFile);
        origBak.delete();

        if (totalPatched > 0) {
            System.out.println("TypeSwitchPatcher: patched " + totalPatched + " typeSwitch calls in " + jarFile.getName());
        }
    }

    private static int processClass(ClassNode cn) {
        int totalPatched = 0;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) continue;
            int patched = processMethod(cn, mn);
            totalPatched += patched;
        }
        return totalPatched;
    }

    private static int processMethod(ClassNode cn, MethodNode mn) {
        int patched = 0;
        List<AbstractInsnNode> toRemove = new ArrayList<>();

        List<InvokeDynamicInsnNode> dynNodes = new ArrayList<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof InvokeDynamicInsnNode idn) {
                if (idn.bsm != null && "typeSwitch".equals(idn.bsm.getName())) {
                    dynNodes.add(idn);
                }
            }
        }

        for (InvokeDynamicInsnNode idn : dynNodes) {
            try {
                patchTypeSwitch(cn, mn, idn);
                patched++;
            } catch (Exception e) {
                System.err.println("Warning: failed to patch typeSwitch in " + cn.name + "." + mn.name + ": " + e.getMessage());
            }
        }
        return patched;
    }

    private static void patchTypeSwitch(ClassNode cn, MethodNode mn, InvokeDynamicInsnNode idn) {
        // --- Step 1: Classify bootstrap args ---
        // typeSwitch bsmArgs can be: Type (class pattern), ConstantDynamic (class ref), or String (string switch)
        List<Type> caseTypes = new ArrayList<>();
        List<String> caseStrings = new ArrayList<>();
        boolean isStringSwitch = false;
        boolean isTypeSwitch = false;

        for (Object arg : idn.bsmArgs) {
            if (arg instanceof Type type) {
                caseTypes.add(type);
                isTypeSwitch = true;
            } else if (arg instanceof ConstantDynamic cd) {
                caseTypes.add(Type.getType(cd.getDescriptor()));
                isTypeSwitch = true;
            } else if (arg instanceof String s) {
                caseStrings.add(s);
                isStringSwitch = true;
            } else {
                System.err.println("Warning: unexpected bootstrap arg type: " + arg.getClass() + " in " + cn.name + "." + mn.name);
                return;
            }
        }

        if (caseTypes.isEmpty() && caseStrings.isEmpty()) {
            System.err.println("Warning: no case types/strings found in typeSwitch bootstrap args in " + cn.name + "." + mn.name);
            return;
        }

        // --- Step 2: Find the preceding aload of the scrutinee ---
        // Pattern: aload temp; {iload idx | iconst_0 | bipush | sipush | ldc Integer}; invokedynamic typeSwitch
        // A LabelNode+FrameNode can sit between the int push and the invokedynamic when a
        // backward `goto` re-enters the switch with a non-zero startIndex (loop-back), so
        // those metadata nodes must be skipped while locating the push + aload.
        AbstractInsnNode intPush = null;
        AbstractInsnNode aloadInsn = null;
        LabelNode loopbackLabel = null;
        List<AbstractInsnNode> metaToRemove = new ArrayList<>();

        {
            AbstractInsnNode scan = idn.getPrevious();
            while (scan != null && (scan instanceof LineNumberNode || scan instanceof LabelNode || scan instanceof FrameNode)) {
                if (scan instanceof LabelNode lab && loopbackLabel == null) {
                    loopbackLabel = lab;
                }
                metaToRemove.add(scan);
                scan = scan.getPrevious();
            }
            intPush = scan;
        }

        boolean intPushOk = false;
        if (intPush != null) {
            int op = intPush.getOpcode();
            intPushOk =
                (intPush instanceof VarInsnNode && op == Opcodes.ILOAD)
                || (intPush instanceof InsnNode && op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5)
                || (intPush instanceof IntInsnNode && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH))
                || (intPush instanceof LdcInsnNode && ((LdcInsnNode) intPush).cst instanceof Integer);
        }
        if (!intPushOk) {
            System.err.println("Warning: expected int push before invokedynamic in " + cn.name + "." + mn.name);
            return;
        }

        {
            AbstractInsnNode scan = intPush.getPrevious();
            while (scan != null && (scan instanceof LineNumberNode || scan instanceof LabelNode || scan instanceof FrameNode)) {
                metaToRemove.add(scan);
                scan = scan.getPrevious();
            }
            aloadInsn = scan;
        }
        if (!(aloadInsn instanceof VarInsnNode aloadVar) || aloadVar.getOpcode() != Opcodes.ALOAD) {
            System.err.println("Warning: expected aload before int push in " + cn.name + "." + mn.name);
            return;
        }
        int scrutineeLocal = aloadVar.var;

        // --- Step 2b: Find loop-back gotos that re-enter the switch with startIndex > 0 ---
        // javac emits `aload scrut; iconst_N; goto <switch label>` to re-run the typeSwitch
        // starting at case N (skipping already-tested cases). These must jump into the
        // replacement chain at the N-th case instead of at the top, and the dead pushes
        // before them are removed.
        List<JumpInsnNode> loopbackGotos = new ArrayList<>();
        List<Integer> loopbackIndices = new ArrayList<>();
        List<AbstractInsnNode> deadLoopbackPushes = new ArrayList<>();
        if (loopbackLabel != null) {
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof JumpInsnNode j) || j.getOpcode() != Opcodes.GOTO || j.label != loopbackLabel) {
                    continue;
                }
                AbstractInsnNode gp = j.getPrevious();
                while (gp != null && (gp instanceof LineNumberNode || gp instanceof LabelNode || gp instanceof FrameNode)) {
                    gp = gp.getPrevious();
                }
                int idxVal = -1;
                boolean ok = false;
                if (gp != null) {
                    int op = gp.getOpcode();
                    if (gp instanceof InsnNode && op >= Opcodes.ICONST_M1 && op <= Opcodes.ICONST_5) {
                        idxVal = op - Opcodes.ICONST_0;
                        ok = true;
                    } else if (gp instanceof IntInsnNode && (op == Opcodes.BIPUSH || op == Opcodes.SIPUSH)) {
                        idxVal = ((IntInsnNode) gp).operand;
                        ok = true;
                    } else if (gp instanceof LdcInsnNode && ((LdcInsnNode) gp).cst instanceof Integer) {
                        idxVal = (Integer) ((LdcInsnNode) gp).cst;
                        ok = true;
                    }
                }
                if (!ok) {
                    System.err.println("Warning: unexpected loop-back into typeSwitch in " + cn.name + "." + mn.name);
                    return;
                }
                AbstractInsnNode ga = gp.getPrevious();
                while (ga != null && (ga instanceof LineNumberNode || ga instanceof LabelNode || ga instanceof FrameNode)) {
                    ga = ga.getPrevious();
                }
                if (!(ga instanceof VarInsnNode gav) || gav.getOpcode() != Opcodes.ALOAD || gav.var != scrutineeLocal) {
                    System.err.println("Warning: unexpected loop-back stack before typeSwitch in " + cn.name + "." + mn.name);
                    return;
                }
                loopbackGotos.add(j);
                loopbackIndices.add(idxVal);
                deadLoopbackPushes.add(gp);
                deadLoopbackPushes.add(ga);
            }
        }

        // --- Step 3: Find the following TableSwitchInsnNode ---
        AbstractInsnNode next = idn.getNext();
        while (next != null && (next instanceof LineNumberNode || next instanceof LabelNode || next instanceof FrameNode)) {
            next = next.getNext();
        }
        if (!(next instanceof TableSwitchInsnNode tsi)) {
            System.err.println("Warning: expected tableswitch after invokedynamic in " + cn.name + "." + mn.name);
            return;
        }

        int low = tsi.min;
        int high = tsi.max;
        LabelNode defaultLabel = tsi.dflt;

        // --- Step 4: Build replacement instructions ---
        InsnList insertions = new InsnList();
        Map<Integer, LabelNode> entryLabels = new HashMap<>();
        int caseCount = Math.max(caseTypes.size(), caseStrings.size());

        // Entry at startIndex 0 is the normal fall-through path.
        LabelNode entry0 = new LabelNode();
        entryLabels.put(0, entry0);
        insertions.add(entry0);

        if (low == -1) {
            LabelNode nullCaseLabel = tsi.labels.get(0);
            insertions.add(new VarInsnNode(Opcodes.ALOAD, scrutineeLocal));
            insertions.add(new JumpInsnNode(Opcodes.IFNULL, nullCaseLabel));
        }

        for (int i = 0; i < caseCount; i++) {
            int caseIndex = i;
            if (caseIndex < low || caseIndex > high) continue;

            LabelNode caseLabel = tsi.labels.get(caseIndex - low);
            if (i > 0) {
                entryLabels.computeIfAbsent(i, k -> new LabelNode());
                insertions.add(entryLabels.get(i));
            }

            if (isStringSwitch && i < caseStrings.size()) {
                // String-based switch: use String.equals() comparisons
                insertions.add(new VarInsnNode(Opcodes.ALOAD, scrutineeLocal));
                insertions.add(new LdcInsnNode(caseStrings.get(i)));
                insertions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "equals", "(Ljava/lang/Object;)Z", false));
                insertions.add(new JumpInsnNode(Opcodes.IFNE, caseLabel));
            } else if (i < caseTypes.size()) {
                // Type-based switch: use instanceof checks
                insertions.add(new VarInsnNode(Opcodes.ALOAD, scrutineeLocal));
                insertions.add(new TypeInsnNode(Opcodes.INSTANCEOF, caseTypes.get(i).getInternalName()));
                insertions.add(new JumpInsnNode(Opcodes.IFNE, caseLabel));
            }
        }

        // goto default (no match)
        insertions.add(new JumpInsnNode(Opcodes.GOTO, defaultLabel));

        // --- Step 5: Redirect loop-back gotos, remove dead code, insert chain ---
        for (int k = 0; k < loopbackGotos.size(); k++) {
            JumpInsnNode j = loopbackGotos.get(k);
            int idx = loopbackIndices.get(k);
            LabelNode target = entryLabels.get(idx);
            if (target == null) {
                target = defaultLabel; // startIndex past the last case -> default
            }
            j.label = target;
        }
        for (AbstractInsnNode dead : deadLoopbackPushes) {
            mn.instructions.remove(dead);
        }

        AbstractInsnNode insertionPoint = next.getNext();

        mn.instructions.remove(aloadInsn);
        mn.instructions.remove(intPush);
        for (AbstractInsnNode meta : metaToRemove) {
            mn.instructions.remove(meta);
        }
        mn.instructions.remove(idn);
        mn.instructions.remove(next);

        if (insertionPoint != null) {
            mn.instructions.insertBefore(insertionPoint, insertions);
        } else {
            mn.instructions.add(insertions);
        }
    }
}
