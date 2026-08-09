import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Replaces all references to com.google.common.base.Predicate with
 * java.util.function.Predicate across all classes in a JAR.
 * 
 * Root cause: D8 generates synthetic lambda classes implementing only
 * com.google.common.base.Predicate but StreamEx.filter() expects
 * java.util.function.Predicate. Since D8 doesn't resolve that Guava 33's
 * Predicate extends java.util.function.Predicate, we replace globally.
 */
public class GlobalPredicatePatcher {
    private static final String OLD = "com/google/common/base/Predicate";
    private static final String NEW = "java/util/function/Predicate";
    private static final String OLD_SLASH = "com.google.common.base.Predicate";
    private static final String NEW_SLASH = "java.util.function.Predicate";
    
    private static int classCount = 0;
    private static int changedCount = 0;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: GlobalPredicatePatcher <jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

        ZipFile zipIn = new ZipFile(jarFile);
        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));

        Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            byte[] data;
            try (InputStream is = zipIn.getInputStream(entry)) { data = is.readAllBytes(); }

            if (entry.getName().endsWith(".class")) {
                byte[] patched = patchClass(data);
                if (patched != null) {
                    data = patched;
                    changedCount++;
                }
                classCount++;
            }

            zipOut.putNextEntry(new ZipEntry(entry.getName()));
            zipOut.write(data);
            zipOut.closeEntry();
        }

        zipIn.close();
        zipOut.close();

        jarFile.delete();
        tmpJar.renameTo(jarFile);
        System.out.println("Processed " + classCount + " classes, patched " + changedCount);
    }

    static byte[] patchClass(byte[] classBytes) {
        try {
            ClassReader cr = new ClassReader(classBytes);
            ClassNode cn = new ClassNode();
            cr.accept(cn, ClassReader.SKIP_FRAMES);

            boolean changed = false;

            // Patch class signature
            if (cn.signature != null && cn.signature.contains(OLD_SLASH)) {
                cn.signature = cn.signature.replace(OLD_SLASH, NEW_SLASH);
                changed = true;
            }

            // Patch class interfaces
            for (int i = 0; i < cn.interfaces.size(); i++) {
                if (cn.interfaces.get(i).equals(OLD)) {
                    cn.interfaces.set(i, NEW);
                    changed = true;
                }
            }

            // Patch superclass
            if (cn.superName != null && cn.superName.equals(OLD)) {
                cn.superName = NEW;
                changed = true;
            }

            // Patch all methods
            for (MethodNode mn : cn.methods) {
                if (mn.desc.contains(OLD) || (mn.signature != null && mn.signature.contains(OLD_SLASH))) {
                    mn.desc = mn.desc.replace(OLD, NEW);
                    if (mn.signature != null) mn.signature = mn.signature.replace(OLD_SLASH, NEW_SLASH);
                    changed = true;
                }

                // Patch all instructions
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    // Patch invokedynamic descriptors
                    if (insn instanceof InvokeDynamicInsnNode idn) {
                        if (idn.desc.contains(OLD)) {
                            idn.desc = idn.desc.replace(OLD, NEW);
                            changed = true;
                        }
                        if (idn.bsmArgs != null) {
                            for (int i = 0; i < idn.bsmArgs.length; i++) {
                                Object arg = idn.bsmArgs[i];
                                if (arg instanceof String s && s.contains(OLD_SLASH)) {
                                    idn.bsmArgs[i] = s.replace(OLD_SLASH, NEW_SLASH);
                                    changed = true;
                                } else if (arg instanceof Type t) {
                                    String tDesc = t.getDescriptor();
                                    if (tDesc.contains(OLD)) {
                                        idn.bsmArgs[i] = Type.getType(tDesc.replace(OLD, NEW));
                                        changed = true;
                                    }
                                }
                            }
                        }
                    }

                    // Patch method calls
                    if (insn instanceof MethodInsnNode min) {
                        if (min.desc.contains(OLD)) {
                            min.desc = min.desc.replace(OLD, NEW);
                            changed = true;
                        }
                    }

                    // Patch field references
                    if (insn instanceof FieldInsnNode fin) {
                        if (fin.desc.contains(OLD)) {
                            fin.desc = fin.desc.replace(OLD, NEW);
                            changed = true;
                        }
                    }

                    // Patch type references
                    if (insn instanceof TypeInsnNode tin) {
                        if (tin.desc.equals(OLD)) {
                            tin.desc = NEW;
                            changed = true;
                        }
                    }

                    // Patch method/local variable descriptors
                    if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type t) {
                        String tDesc = t.getDescriptor();
                        if (tDesc.contains(OLD)) {
                            ldc.cst = Type.getType(tDesc.replace(OLD, NEW));
                            changed = true;
                        }
                    }
                }

                // Patch try-catch
                if (mn.tryCatchBlocks != null) {
                    for (TryCatchBlockNode tc : mn.tryCatchBlocks) {
                        if (tc.type != null && tc.type.equals(OLD)) {
                            tc.type = NEW;
                            changed = true;
                        }
                    }
                }

                // Patch local variables
                if (mn.localVariables != null) {
                    for (LocalVariableNode lv : mn.localVariables) {
                        if (lv.desc != null && lv.desc.contains(OLD)) {
                            lv.desc = lv.desc.replace(OLD, NEW);
                            changed = true;
                        }
                        if (lv.signature != null && lv.signature.contains(OLD_SLASH)) {
                            lv.signature = lv.signature.replace(OLD_SLASH, NEW_SLASH);
                            changed = true;
                        }
                    }
                }
            }

            // Patch fields
            for (FieldNode fn : cn.fields) {
                if (fn.desc.contains(OLD)) {
                    fn.desc = fn.desc.replace(OLD, NEW);
                    changed = true;
                }
                if (fn.signature != null && fn.signature.contains(OLD_SLASH)) {
                    fn.signature = fn.signature.replace(OLD_SLASH, NEW_SLASH);
                    changed = true;
                }
            }

            // Patch inner classes
            if (cn.innerClasses != null) {
                for (InnerClassNode ic : cn.innerClasses) {
                    if (ic.name.equals(OLD)) ic.name = NEW;
                    if (ic.outerName != null && ic.outerName.equals(OLD)) ic.outerName = NEW;
                    if (ic.innerName != null && ic.innerName.equals(OLD)) ic.innerName = NEW;
                    // Also handle full-path references
                    if (ic.name.contains(OLD_SLASH)) { ic.name = ic.name.replace(OLD_SLASH, NEW_SLASH); changed = true; }
                    if (ic.outerName != null && ic.outerName.contains(OLD_SLASH)) { ic.outerName = ic.outerName.replace(OLD_SLASH, NEW_SLASH); changed = true; }
                    if (ic.innerName != null && ic.innerName.contains(OLD_SLASH)) { ic.innerName = ic.innerName.replace(OLD_SLASH, NEW_SLASH); changed = true; }
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
            // If we can't process this class, return unchanged
            return null;
        }
    }
}
