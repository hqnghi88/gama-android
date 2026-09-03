import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches MeshLayerData.buildValues() to add a null check after GamaFieldType.buildField().
 * When the 'from' parameter is null (e.g. mesh layer source not yet initialized),
 * buildField returns null and the subsequent values.getCols() throws NPE.
 */
public class MeshLayerPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: MeshLayerPatcher <gama.core.jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

        String targetClass = "gama/core/outputs/layers/MeshLayerData.class";

        ZipFile zipIn = new ZipFile(jarFile);
        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));
        boolean patched = false;

        Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
                byte[] data;
                try (InputStream is = zipIn.getInputStream(entry)) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                    data = baos.toByteArray();
                }

            if (entry.getName().equals(targetClass)) {
                ClassReader cr = new ClassReader(data);
                ClassNode cn = new ClassNode();
                cr.accept(cn, 0);

                for (MethodNode mn : cn.methods) {
                    if (!mn.name.equals("buildValues") || !mn.desc.equals("(Lgama/core/runtime/IScope;Ljava/lang/Object;)Lgama/core/util/matrix/IField;")) {
                        continue;
                    }

                    // Found the target method. Replace its instructions with null-safe version:
                    //   if (values == null || shouldComputeValues) {
                    //       IField result = GamaFieldType.buildField(scope, from);
                    //       if (result != null) { values = result; dim.setLocation(...); }
                    //   }
                    //   return values;

                    InsnList il = new InsnList();

                    // --- if (values == null || shouldComputeValues) goto SKIP ---
                    // this.values
                    il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    il.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "values", "Lgama/core/util/matrix/IField;"));
                    LabelNode computeLabel = new LabelNode();
                    il.add(new JumpInsnNode(Opcodes.IFNULL, computeLabel));
                    // this.shouldComputeValues
                    il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    il.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "shouldComputeValues", "Z"));
                    LabelNode skipLabel = new LabelNode();
                    il.add(new JumpInsnNode(Opcodes.IFEQ, skipLabel));

                    // --- values = GamaFieldType.buildField(scope, from) ---
                    il.add(computeLabel);
                    il.add(new VarInsnNode(Opcodes.ALOAD, 1)); // scope
                    il.add(new VarInsnNode(Opcodes.ALOAD, 2)); // from
                    il.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                            "gama/gaml/types/GamaFieldType", "buildField",
                            "(Lgama/core/runtime/IScope;Ljava/lang/Object;)Lgama/core/util/matrix/IField;", false));
                    // Stack: [result]

                    // --- if (result != null) { values = result; dim.setLocation(...) } ---
                    LabelNode afterSet = new LabelNode();
                    il.add(new InsnNode(Opcodes.DUP)); // Stack: [result, result]
                    il.add(new JumpInsnNode(Opcodes.IFNULL, afterSet));

                    // values = result
                    il.add(new VarInsnNode(Opcodes.ALOAD, 0)); // Stack: [result, this]
                    il.add(new InsnNode(Opcodes.SWAP)); // Stack: [this, result]
                    il.add(new FieldInsnNode(Opcodes.PUTFIELD, cn.name, "values", "Lgama/core/util/matrix/IField;"));

                    // dim.setLocation(values.getCols(scope), values.getRows(scope), 0.0)
                    // this.dim
                    il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    il.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "dim", "Lgama/core/metamodel/shape/GamaPoint;"));

                    // values.getCols(scope) → int → double
                    il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    il.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "values", "Lgama/core/util/matrix/IField;"));
                    il.add(new VarInsnNode(Opcodes.ALOAD, 1)); // scope
                    il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                            "gama/core/util/matrix/IField", "getCols",
                            "(Lgama/core/runtime/IScope;)I", true));
                    il.add(new InsnNode(Opcodes.I2D));

                    // values.getRows(scope) → int → double
                    il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    il.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "values", "Lgama/core/util/matrix/IField;"));
                    il.add(new VarInsnNode(Opcodes.ALOAD, 1)); // scope
                    il.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                            "gama/core/util/matrix/IField", "getRows",
                            "(Lgama/core/runtime/IScope;)I", true));
                    il.add(new InsnNode(Opcodes.I2D));

                    // 0.0
                    il.add(new InsnNode(Opcodes.DCONST_0));

                    // dim.setLocation(double, double, double)
                    il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                            "gama/core/metamodel/shape/GamaPoint", "setLocation",
                            "(DDD)Lgama/core/metamodel/shape/GamaPoint;", false));
                    il.add(new InsnNode(Opcodes.POP)); // discard GamaPoint return value

                    il.add(new JumpInsnNode(Opcodes.GOTO, skipLabel));

                    il.add(afterSet);
                    il.add(new InsnNode(Opcodes.POP)); // pop the null result

                    // --- SKIP: return values ---
                    il.add(skipLabel);
                    il.add(new VarInsnNode(Opcodes.ALOAD, 0));
                    il.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "values", "Lgama/core/util/matrix/IField;"));
                    il.add(new InsnNode(Opcodes.ARETURN));

                    mn.instructions.clear();
                    mn.instructions.insert(il);
                    mn.tryCatchBlocks.clear();
                    mn.maxStack = 5;
                    mn.maxLocals = 3;

                    patched = true;
                    System.out.println("Patched: " + targetClass + " -> buildValues() null check added");
                    break;
                }

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
            System.err.println("Target class not found in JAR");
            System.exit(1);
        }
    }
}
