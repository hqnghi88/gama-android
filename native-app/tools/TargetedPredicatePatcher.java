import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Targeted patcher: Changes Containers.by() and Containers.inContainer() to return
 * java.util.function.Predicate instead of com.google.common.base.Predicate,
 * AND updates all callers across all JARs to match.
 * 
 * Does NOT change any other com.google.common.base.Predicate references.
 */
public class TargetedPredicatePatcher {
    private static final String GUAVA = "com/google/common/base/Predicate";
    private static final String JDK = "java/util/function/Predicate";
    private static final String GUAVA_SLASH = "com.google.common.base.Predicate";
    private static final String JDK_SLASH = "java.util.function.Predicate";
    
    private static final Set<String> TARGET_METHODS = Set.of(
        "by", "inContainer"
    );

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: TargetedPredicatePatcher <jar> [jar2 ...]");
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
                byte[] result = patchClass(data);
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
        System.out.println("  Patched " + patched + " classes in " + jarFile.getName());
    }

    static byte[] patchClass(byte[] classBytes) {
        try {
            ClassReader cr = new ClassReader(classBytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, ClassReader.SKIP_FRAMES);
            boolean changed = false;

            for (MethodNode mn : cn.methods) {
                // 1. Patch Containers.by() and Containers.inContainer() method signatures
                if (cn.name.equals("gama/gaml/operators/Containers") && 
                    TARGET_METHODS.contains(mn.name)) {
                    if (mn.desc.contains(GUAVA)) {
                        mn.desc = mn.desc.replace(GUAVA, JDK);
                        changed = true;
                        System.out.println("  Patched Containers." + mn.name + " signature");
                    }
                    if (mn.signature != null && mn.signature.contains(GUAVA_SLASH)) {
                        mn.signature = mn.signature.replace(GUAVA_SLASH, JDK_SLASH);
                    }
                }

                // 2. Patch invokedynamic bootstrap args (first arg = functional interface type)
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof InvokeDynamicInsnNode idn) {
                        // Change invokedynamic descriptors that produce Guava Predicate
                        if (idn.desc.contains(GUAVA)) {
                            idn.desc = idn.desc.replace(GUAVA, JDK);
                            changed = true;
                        }
                        // Change bootstrap method arguments that reference Guava Predicate
                        if (idn.bsmArgs != null) {
                            for (int i = 0; i < idn.bsmArgs.length; i++) {
                                Object arg = idn.bsmArgs[i];
                                if (arg instanceof String s && s.contains(GUAVA_SLASH)) {
                                    idn.bsmArgs[i] = s.replace(GUAVA_SLASH, JDK_SLASH);
                                    changed = true;
                                } else if (arg instanceof Type t) {
                                    String tDesc = t.getDescriptor();
                                    if (tDesc.contains(GUAVA)) {
                                        idn.bsmArgs[i] = Type.getType(tDesc.replace(GUAVA, JDK));
                                        changed = true;
                                    }
                                }
                            }
                        }
                    }

                    // 3. Patch method calls to Containers.by() and Containers.inContainer()
                    if (insn instanceof MethodInsnNode min) {
                        if (min.owner.equals("gama/gaml/operators/Containers") &&
                            TARGET_METHODS.contains(min.name) &&
                            min.desc.contains(GUAVA)) {
                            min.desc = min.desc.replace(GUAVA, JDK);
                            changed = true;
                            System.out.println("  Patched caller: " + cn.name + "." + mn.name + " -> Containers." + min.name);
                        }
                    }
                }

                // Patch try-catch descriptors
                if (mn.tryCatchBlocks != null) {
                    for (TryCatchBlockNode tc : mn.tryCatchBlocks) {
                        if (tc.type != null && tc.type.equals(GUAVA)) {
                            tc.type = JDK;
                            changed = true;
                        }
                    }
                }
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
            return cw.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }
}
