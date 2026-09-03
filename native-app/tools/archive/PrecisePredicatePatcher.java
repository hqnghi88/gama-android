import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Precise patcher: Only changes:
 * 1. Containers.by() and Containers.inContainer() method signatures (in Containers.class only)
 * 2. Invokedynamic descriptors WITHIN Containers.class that produce these methods
 * 3. Method call references to Containers.by() and Containers.inContainer() in ALL classes
 * 
 * Does NOT touch any other invokedynamic descriptors or any other classes' lambdas.
 */
public class PrecisePredicatePatcher {
    private static final String GUAVA = "com/google/common/base/Predicate";
    private static final String JDK = "java/util/function/Predicate";
    private static final String GUAVA_SLASH = "com.google.common.base.Predicate";
    private static final String JDK_SLASH = "java.util.function.Predicate";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: PrecisePredicatePatcher <jar> [jar2 ...]");
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
            boolean isContainers = cn.name.equals("gama/gaml/operators/Containers");

            for (MethodNode mn : cn.methods) {
                // 1. ONLY in Containers.class: change by() and inContainer() method signatures
                if (isContainers && (mn.name.equals("by") || mn.name.equals("inContainer"))) {
                    if (mn.desc.contains(GUAVA)) {
                        mn.desc = mn.desc.replace(GUAVA, JDK);
                        changed = true;
                        System.out.println("  Patched Containers." + mn.name + " signature");
                    }
                    if (mn.signature != null && mn.signature.contains(GUAVA_SLASH)) {
                        mn.signature = mn.signature.replace(GUAVA_SLASH, JDK_SLASH);
                    }
                }

                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof InvokeDynamicInsnNode idn) {
                        // 2. ONLY in Containers.class: change invokedynamic descriptors + SAM name
                        if (isContainers && idn.desc.contains(GUAVA)) {
                            idn.desc = idn.desc.replace(GUAVA, JDK);
                            // Change SAM name: apply -> test (Guava uses apply, JDK uses test)
                            if (idn.name.equals("apply")) {
                                idn.name = "test";
                            }
                            changed = true;
                        }
                        // 3. In Containers.class: change bootstrap method args
                        if (isContainers && idn.bsmArgs != null) {
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

                    // 4. In ALL classes: change method calls to Containers.by() and Containers.inContainer()
                    if (insn instanceof MethodInsnNode min) {
                        if (min.owner.equals("gama/gaml/operators/Containers") &&
                            (min.name.equals("by") || min.name.equals("inContainer")) &&
                            min.desc.contains(GUAVA)) {
                            min.desc = min.desc.replace(GUAVA, JDK);
                            changed = true;
                            System.out.println("  Patched caller: " + cn.name + " -> Containers." + min.name);
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
