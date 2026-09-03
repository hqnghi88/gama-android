import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches all invokedynamic enumSwitch calls (JDK 17+ null-tolerant enum switches)
 * into plain bytecode that Android can run.
 *
 * javac emits `java.lang.runtime.SwitchBootstraps.enumSwitch` (which does not exist
 * on Android/ART) when a `switch` over an enum contains `case null:` (or `case null,
 * default:`) -- e.g.:
 *
 *   switch (rel) {
 *       case OVERLAP: ...
 *       case null:
 *       default:
 *           break;
 *   }
 *
 * Bytecode shape (from JDK 21+ javac):
 *   aload_<sel>            // the enum selector
 *   iconst_<startIdx>      // repeat/start index (0 on first entry)
 *   invokedynamic enumSwitch:(EnumType;I)I
 *   tableswitch { -1: noMatch, 0: case0, 1: case1, ... default: noMatch }
 *
 * Per java.lang.runtime.SwitchBootstraps.enumSwitch, the resolved handle is:
 *   if (selector == null) return -1;
 *   String n = selector.name();
 *   for (int i = startIdx; i < labels.length; i++)
 *       if (n.equals(labels[i])) return i;
 *   return -1;
 *
 * This patcher replaces the invokedynamic with an unrolled chain of
 * `ldc name; aload sel; Enum.name(); String.equals; ifne caseLabel` comparisons
 * (mirroring TypeSwitchPatcher), preserving the surrounding tableswitch and
 * re-targeting any loop-back gotos that re-enter the switch with startIdx > 0.
 */
public class EnumSwitchPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: EnumSwitchPatcher <jar> [jar2 ...]");
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
        File tmpJar = new File(jarFile.getAbsolutePath() + ".enumswitch_tmp");
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
        File origBak = new File(jarFile.getAbsolutePath() + ".enumswitch_bak");
        jarFile.renameTo(origBak);
        tmpJar.renameTo(jarFile);
        origBak.delete();

        if (totalPatched > 0) {
            System.out.println("EnumSwitchPatcher: patched " + totalPatched + " enumSwitch calls in " + jarFile.getName());
        }
    }

    private static int processClass(ClassNode cn) {
        int totalPatched = 0;
        for (MethodNode mn : cn.methods) {
            if (mn.instructions == null) continue;
            List<InvokeDynamicInsnNode> dynNodes = new ArrayList<>();
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof InvokeDynamicInsnNode idn
                        && idn.bsm != null && "enumSwitch".equals(idn.bsm.getName())) {
                    dynNodes.add(idn);
                }
            }
            for (InvokeDynamicInsnNode idn : dynNodes) {
                try {
                    if (patchEnumSwitch(cn, mn, idn)) {
                        totalPatched++;
                    }
                } catch (Exception e) {
                    System.err.println("Warning: failed to patch enumSwitch in "
                            + cn.name + "." + mn.name + ": " + e.getMessage());
                }
            }
        }
        return totalPatched;
    }

    private static boolean patchEnumSwitch(ClassNode cn, MethodNode mn, InvokeDynamicInsnNode idn) {
        // --- Step 1: Collect case labels (String constants in source order) ---
        List<String> names = new ArrayList<>();
        for (Object arg : idn.bsmArgs) {
            if (arg instanceof String s) {
                names.add(s);
            } else {
                System.err.println("Warning: unexpected enumSwitch bootstrap arg type "
                        + arg.getClass() + " in " + cn.name + "." + mn.name + " -- skipped");
                return false;
            }
        }
        if (names.isEmpty()) {
            System.err.println("Warning: no case labels in enumSwitch bootstrap args in "
                    + cn.name + "." + mn.name);
            return false;
        }

        // --- Step 2: Find the preceding aload (selector) + int push (startIdx) ---
        // Pattern: aload temp; {iconst_0|bipush|...}; invokedynamic enumSwitch
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
            System.err.println("Warning: expected int push before enumSwitch in " + cn.name + "." + mn.name);
            return false;
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
            System.err.println("Warning: expected aload before enumSwitch in " + cn.name + "." + mn.name);
            return false;
        }
        int scrutineeLocal = aloadVar.var;

        // --- Step 2b: Find loop-back gotos that re-enter the switch with startIdx > 0 ---
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
                    System.err.println("Warning: unexpected loop-back into enumSwitch in " + cn.name + "." + mn.name);
                    return false;
                }
                AbstractInsnNode ga = gp.getPrevious();
                while (ga != null && (ga instanceof LineNumberNode || ga instanceof LabelNode || ga instanceof FrameNode)) {
                    ga = ga.getPrevious();
                }
                if (!(ga instanceof VarInsnNode gav) || gav.getOpcode() != Opcodes.ALOAD || gav.var != scrutineeLocal) {
                    System.err.println("Warning: unexpected loop-back stack before enumSwitch in " + cn.name + "." + mn.name);
                    return false;
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
            System.err.println("Warning: expected tableswitch after enumSwitch in " + cn.name + "." + mn.name);
            return false;
        }

        int low = tsi.min;
        LabelNode defaultLabel = tsi.dflt;

        // --- Step 4: Build replacement instructions ---
        InsnList insertions = new InsnList();
        Map<Integer, LabelNode> entryLabels = new HashMap<>();

        LabelNode entry0 = new LabelNode();
        entryLabels.put(0, entry0);
        insertions.add(entry0);

        // null selector -> no-match label (javac maps `case null:` here, at tsi.min == -1)
        if (low == -1) {
            LabelNode nullCaseLabel = tsi.labels.get(0);
            insertions.add(new VarInsnNode(Opcodes.ALOAD, scrutineeLocal));
            insertions.add(new JumpInsnNode(Opcodes.IFNULL, nullCaseLabel));
        }

        for (int i = 0; i < names.size(); i++) {
            if (i < low || i > tsi.max) continue;

            LabelNode caseLabel = tsi.labels.get(i - low);
            if (i > 0) {
                entryLabels.computeIfAbsent(i, k -> new LabelNode());
                insertions.add(entryLabels.get(i));
            }

            insertions.add(new LdcInsnNode(names.get(i)));
            insertions.add(new VarInsnNode(Opcodes.ALOAD, scrutineeLocal));
            insertions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Enum",
                    "name", "()Ljava/lang/String;", false));
            insertions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                    "equals", "(Ljava/lang/Object;)Z", false));
            insertions.add(new JumpInsnNode(Opcodes.IFNE, caseLabel));
        }

        // no name matched -> the tableswitch default (== enumSwitch returning -1)
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
        return true;
    }
}
