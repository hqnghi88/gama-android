import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches LayerManager.<init> to redirect createLayer() calls to
 * LayerManagerHelper.createLayerSafe() which wraps each layer creation
 * in a try-catch, preventing one failing layer from crashing the display.
 */
public class LayerManagerPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: LayerManagerPatcher <jar>");
            System.exit(1);
        }
        processJar(new File(args[0]));
    }

    private static void processJar(File jarFile) throws Exception {
        File tmpJar = new File(jarFile.getAbsolutePath() + ".lm_tmp");

        ZipFile zipIn = new ZipFile(jarFile);
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));

        boolean found = false;
        Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            byte[] data;
            try (InputStream is = zipIn.getInputStream(entry)) {
                data = is.readAllBytes();
            }

            if (entry.getName().equals("gama/core/outputs/display/LayerManager.class")) {
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);

                boolean patched = patchCreateLayerCall(cn);
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
                    found = true;
                    System.out.println("LayerManagerPatcher: redirected createLayer -> createLayerSafe");
                } else {
                    System.out.println("LayerManagerPatcher: WARNING - createLayer call not found");
                }
            }

            zipOut.putNextEntry(new ZipEntry(entry.getName()));
            zipOut.write(data);
            zipOut.closeEntry();
        }

        zipIn.close();
        zipOut.close();

        jarFile.delete();
        tmpJar.renameTo(jarFile);

        if (!found) {
            System.err.println("LayerManagerPatcher: WARNING - LayerManager.class not found in jar");
        }
    }

    private static boolean patchCreateLayerCall(ClassNode cn) {
        boolean patched = false;
        for (MethodNode mn : cn.methods) {
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof MethodInsnNode min && min.getOpcode() == Opcodes.INVOKESTATIC) {
                    boolean isOriginal = "createLayer".equals(min.name)
                        && "gama/core/outputs/display/LayerManager".equals(min.owner);
                    boolean isAlreadyPatched = "createLayerSafe".equals(min.name)
                        && "com/gama/nativeapp/util/LayerManagerHelper".equals(min.owner);
                    if (isOriginal || isAlreadyPatched) {
                        // Redirect to LayerManagerHelper.createLayerSafe.
                        // The descriptor must match the original call site: the first
                        // argument is the IOutput.Display interface (NOT the concrete
                        // LayeredDisplayOutput) and the return type is gama.api.ui.layers.ILayer.
                        // Using the concrete type here produces a VerifyError ("register v8 has
                        // type IOutput$Display but expected LayeredDisplayOutput").
                        // This is idempotent: it also repairs the descriptor of a call that was
                        // already redirected by an earlier (buggy) run of this patcher.
                        min.owner = "com/gama/nativeapp/util/LayerManagerHelper";
                        min.name = "createLayerSafe";
                        min.desc = "(Lgama/api/ui/IOutput$Display;Lgama/api/ui/layers/ILayerStatement;)Lgama/api/ui/layers/ILayer;";
                        patched = true;
                    }
                }
            }
        }
        return patched;
    }
}
