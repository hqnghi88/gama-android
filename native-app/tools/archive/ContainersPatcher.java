import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Fixes Containers.by() and Containers.inContainer() for Android/D8.
 *
 * Problem: These methods return com.google.common.base.Predicate. When passed to
 * Stream.filter(), D8 generates a broken synthetic lambda that doesn't implement
 * java.util.function.Predicate.
 *
 * Fix: Rename the original methods to by$guava/inContainer$guava (old return type),
 * create new by()/inContainer() returning java.util.function.Predicate, and add
 * bridge methods by$guava()/inContainer$guava() with old signatures that call the new
 * methods. This way:
 * - Stream.filter() calls get java.util.function.Predicate (D8 generates correct lambda)
 * - Other callers compiled against Guava Predicate still work via bridge methods
 */
public class ContainersPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ContainersPatcher <gama.core.jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

        String targetClass = "gama/gaml/operators/Containers";
        String guavaPred = "com/google/common/base/Predicate";
        String utilPred = "java/util/function/Predicate";

        ZipFile zipIn = new ZipFile(jarFile);
        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));
        boolean patched = false;

        Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            byte[] data;
            try (InputStream is = zipIn.getInputStream(entry)) { data = is.readAllBytes(); }

            if (entry.getName().equals(targetClass + ".class")) {
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);

                List<MethodNode> newMethods = new ArrayList<>();

                for (MethodNode mn : cn.methods) {
                    if (!mn.desc.contains(guavaPred)) continue;
                    if (!mn.name.equals("by") && !mn.name.equals("inContainer")) continue;

                    String oldName = mn.name;
                    String oldDesc = mn.desc;
                    String newDesc = oldDesc.replace(guavaPred, utilPred);

                    System.out.println("Processing " + oldName + ": " + oldDesc);

                    // 1. Change the original method to return java.util.function.Predicate
                    mn.desc = newDesc;
                    if (mn.signature != null) {
                        mn.signature = mn.signature.replace(guavaPred, utilPred);
                    }

                    // 2. Create bridge method with OLD name + OLD desc that calls the NEW method
                    // This handles callers compiled against the old Guava Predicate signature
                    MethodNode bridge = new MethodNode(
                        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                        oldName,
                        oldDesc,
                        null,
                        null);
                    InsnList bridgeInsns = new InsnList();
                    Type[] argTypes = Type.getArgumentTypes(oldDesc);
                    int local = 0;
                    for (Type t : argTypes) {
                        bridgeInsns.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), local));
                        local += t.getSize();
                    }
                    bridgeInsns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, targetClass, oldName + "$util", newDesc, false));
                    bridgeInsns.add(new InsnNode(Opcodes.ARETURN));
                    bridge.instructions = bridgeInsns;
                    bridge.maxStack = 5;
                    bridge.maxLocals = local;
                    newMethods.add(bridge);

                    // 3. Rename original method to oldName + "$util"
                    mn.name = oldName + "$util";

                    patched = true;
                    System.out.println("  -> Original renamed to " + mn.name + mn.desc);
                    System.out.println("  -> Bridge method " + oldName + oldDesc + " added");
                }

                cn.methods.addAll(newMethods);

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
