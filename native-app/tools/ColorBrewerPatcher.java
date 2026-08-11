import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches ColorBrewer.load(InputStream, PaletteType) so it works on Android.
 *
 * On the desktop JVM, DOM text nodes implement toString() to return their text
 * content. Android's Harmony DOM (org.apache.harmony.xml.dom.TextImpl) does NOT
 * override toString(), so ColorBrewer.load() sees strings like
 * "org.apache.harmony.xml.dom.TextImpl@1234abcd" instead of "Set1" and throws a
 * NumberFormatException (sample size parsing). As a result ColorBrewer.instance()
 * throws and Colors.BREWER stays null (ColorsPatcher swallows it), which breaks
 * every brewer_colors(...)/palette(...) usage.
 *
 * The fix: rewrite every
 *   invokeinterface org/w3c/dom/Node.getFirstChild()Node
 *   invokevirtual  java/lang/Object.toString()String
 * pair inside load(...) to use
 *   invokeinterface org/w3c/dom/Node.getNodeValue()String
 * which returns the real text content on Android (and is equivalent on the JVM).
 * The stack effect is identical (pop Node, push String), so no frame changes.
 */
public class ColorBrewerPatcher {

    private static AbstractInsnNode previousRealInsn(AbstractInsnNode insn) {
        AbstractInsnNode p = insn.getPrevious();
        while (p != null && (p instanceof LabelNode
                || p instanceof LineNumberNode
                || p instanceof FrameNode)) {
            p = p.getPrevious();
        }
        return p;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ColorBrewerPatcher <gt-brewer.jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

        String targetClass = "org/geotools/brewer/color/ColorBrewer.class";
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
                    if (!mn.name.equals("load")) continue;

                    int count = 0;
                    AbstractInsnNode insn = mn.instructions.getFirst();
                    while (insn != null) {
                        AbstractInsnNode next = insn.getNext();
                        if (insn.getOpcode() != Opcodes.INVOKEVIRTUAL) { insn = next; continue; }
                        MethodInsnNode min = (MethodInsnNode) insn;
                        if (!min.owner.equals("java/lang/Object")) { insn = next; continue; }
                        if (!min.name.equals("toString")) { insn = next; continue; }
                        if (!min.desc.equals("()Ljava/lang/String;")) { insn = next; continue; }

                        AbstractInsnNode prev = previousRealInsn(insn);
                        if (!(prev instanceof MethodInsnNode pmin)) { insn = next; continue; }
                        if (pmin.getOpcode() != Opcodes.INVOKEINTERFACE) { insn = next; continue; }
                        if (!pmin.owner.equals("org/w3c/dom/Node")) { insn = next; continue; }
                        if (!pmin.name.equals("getFirstChild")) { insn = next; continue; }

                        MethodInsnNode repl = new MethodInsnNode(
                                Opcodes.INVOKEINTERFACE,
                                "org/w3c/dom/Node",
                                "getNodeValue",
                                "()Ljava/lang/String;",
                                true);
                        mn.instructions.set(insn, repl);
                        count++;
                        insn = next;
                    }
                    if (count > 0) {
                        patched = true;
                        System.out.println("Patched ColorBrewer.load: rewrote " + count
                                + " Node.toString() -> Node.getNodeValue()");
                    }
                }

                if (patched) {                    ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
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
