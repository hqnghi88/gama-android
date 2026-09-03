import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches org.geotools.measure.Units to wrap its static initializer in try-catch.
 * This prevents NoClassDefFoundError when systems.uom.common.USCustomary is not available.
 */
public class UnitsPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: UnitsPatcher <gt-referencing.jar>");
            System.exit(1);
        }

        File jarFile = new File(args[0]);
        patchJar(jarFile);
        System.out.println("Patched Units.<clinit>: wrapped in try-catch(Exception)");
    }

    static void patchJar(File jarFile) throws Exception {
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

            if (entry.getName().equals("org/geotools/measure/Units.class")) {
                data = patchUnits(data);
                patched = true;
            }

            ZipEntry outEntry = new ZipEntry(entry.getName());
            zipOut.putNextEntry(outEntry);
            zipOut.write(data);
            zipOut.closeEntry();
        }
        zipIn.close();
        zipOut.close();

        if (patched) {
            jarFile.delete();
            tmpJar.renameTo(jarFile);
        } else {
            tmpJar.delete();
            System.err.println("WARNING: Units.class not found in " + jarFile.getName());
        }
    }

    static byte[] patchUnits(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("<clinit>")) {
                InsnList original = mn.instructions;
                LabelNode startLabel = new LabelNode();
                LabelNode endLabel = new LabelNode();
                LabelNode handlerLabel = new LabelNode();

                InsnList wrapper = new InsnList();
                wrapper.add(startLabel);
                for (AbstractInsnNode insn = original.getFirst(); insn != null; insn = insn.getNext()) {
                    wrapper.add(insn.clone(null));
                }
                wrapper.add(endLabel);
                wrapper.add(new JumpInsnNode(Opcodes.GOTO, new LabelNode()));
                wrapper.add(handlerLabel);
                wrapper.add(new VarInsnNode(Opcodes.ASTORE, 0));
                wrapper.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "err", "Ljava/io/PrintStream;"));
                wrapper.add(new LdcInsnNode("Units init failed (USCustomary not available)"));
                wrapper.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false));
                wrapper.add(new LabelNode());
                wrapper.add(new InsnNode(Opcodes.RETURN));

                mn.instructions = wrapper;
                mn.tryCatchBlocks.add(new TryCatchBlockNode(startLabel, endLabel, handlerLabel, "java/lang/Exception"));
                mn.maxStack = Math.max(mn.maxStack, 3);
                System.out.println("Patched Units.<clinit>: wrapped BREWER init in try-catch(Exception)");
                break;
            }
        }

        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }
}
