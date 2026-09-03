import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Makes the Android guava variant behave like the desktop guava-jre variant for the
 * com.google.common.base functional interfaces, so D8-desugared lambdas (created against
 * guava-jre on the desktop toolchain) are usable where java.util.function.* is expected.
 *
 * In guava-jre these interfaces extend their java.util.function counterparts; in guava-android
 * they do not. We add the missing super-interfaces (and, for Predicate, the default test()
 * method that delegates to apply(), exactly as in guava-jre).
 *
 * Patches:
 *   com/google/common/base/Predicate : + implements java/util/function/Predicate
 *                                      + default boolean test(Object) { return apply(Object); }
 *   com/google/common/base/Function  : + implements java/util/function/Function
 *   com/google/common/base/Supplier  : + implements java/util/function/Supplier
 */
public class GuavaJreCompatPatcher {
    private static final String PREDICATE = "com/google/common/base/Predicate";
    private static final String FUNCTION = "com/google/common/base/Function";
    private static final String SUPPLIER = "com/google/common/base/Supplier";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: GuavaJreCompatPatcher <jar>");
            System.exit(1);
        }
        for (String jarPath : args) {
            File jarFile = new File(jarPath);
            if (!jarFile.exists()) { System.err.println("Not found: " + jarPath); continue; }
            processJar(jarFile);
        }
    }

    static void processJar(File jarFile) throws Exception {
        System.out.println("Processing: " + jarFile.getName());
        ZipFile zipIn = new ZipFile(jarFile);
        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));

        int patched = 0;
        Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            byte[] data;
            try (InputStream is = zipIn.getInputStream(entry)) { data = is.readAllBytes(); }

            if (entry.getName().endsWith(".class")) {
                byte[] result = patchClass(entry.getName(), data);
                if (result != null) { data = result; patched++; }
            }

            zipOut.putNextEntry(new ZipEntry(entry.getName()));
            zipOut.write(data);
            zipOut.closeEntry();
        }
        zipIn.close();
        zipOut.close();

        jarFile.delete();
        tmpJar.renameTo(jarFile);
        System.out.println("  Patched " + patched + " classes");
    }

    static byte[] patchClass(String className, byte[] classBytes) {
        try {
            ClassReader cr = new ClassReader(classBytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, ClassReader.SKIP_FRAMES);
            boolean changed = false;
            boolean hasSuper = false;
            String iface = null;

            switch (cn.name) {
                case PREDICATE:
                    iface = "java/util/function/Predicate";
                    for (String i : cn.interfaces) if (i.equals(iface)) hasSuper = true;
                    if (!hasSuper) {
                        cn.interfaces.add(iface);
                        changed = true;
                    }
                    boolean hasTest = false;
                    for (MethodNode mn : cn.methods) if (mn.name.equals("test")) hasTest = true;
                    if (!hasTest) {
                        MethodNode test = new MethodNode(Opcodes.ACC_PUBLIC,
                            "test", "(Ljava/lang/Object;)Z", null, null);
                        test.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        test.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        test.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                            PREDICATE, "apply", "(Ljava/lang/Object;)Z", true));
                        test.instructions.add(new InsnNode(Opcodes.IRETURN));
                        cn.methods.add(test);
                        changed = true;
                    }
                    break;
                case FUNCTION:
                    iface = "java/util/function/Function";
                    for (String i : cn.interfaces) if (i.equals(iface)) hasSuper = true;
                    if (!hasSuper) {
                        cn.interfaces.add(iface);
                        changed = true;
                    }
                    break;
                case SUPPLIER:
                    iface = "java/util/function/Supplier";
                    for (String i : cn.interfaces) if (i.equals(iface)) hasSuper = true;
                    if (!hasSuper) {
                        cn.interfaces.add(iface);
                        changed = true;
                    }
                    break;
                default:
                    return null;
            }

            if (changed && cn.signature != null) {
                cn.signature = appendSuperInterface(cn.signature, iface);
            }

            if (!changed) return null;

            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS) {
                @Override
                protected String getCommonSuperClass(String type1, String type2) {
                    try { return super.getCommonSuperClass(type1, type2); }
                    catch (RuntimeException e) { return "java/lang/Object"; }
                }
            };
            cn.accept(cw);
            System.out.println("  Patched interface: " + cn.name);
            return cw.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    static String appendSuperInterface(String sig, String iface) {
        String params = "";
        int idx = 0;
        if (sig.startsWith("<")) {
            int depth = 0, end = -1;
            for (int i = 0; i < sig.length(); i++) {
                char c = sig.charAt(i);
                if (c == '<') depth++;
                else if (c == '>') { depth--; if (depth == 0) { end = i; break; } }
            }
            if (end >= 0) {
                params = sig.substring(0, end + 1);
                idx = end + 1;
            }
        }
        String typeArgs = "<";
        if (params.length() > 2) {
            for (String p : params.substring(1, params.length() - 1).split(";")) {
                if (p.isEmpty()) continue;
                int colon = p.indexOf(':');
                String name = colon == -1 ? p : p.substring(0, colon);
                typeArgs += "T" + name + ";";
            }
        }
        typeArgs += ">";
        String suffix = "L" + iface + typeArgs + ";";
        return sig + suffix;
    }
}
