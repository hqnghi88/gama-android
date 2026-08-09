import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches Colors.<clinit> to wrap BREWER = ColorBrewer.instance() in try-catch
 * so that if the XML-based ColorBrewer initialization fails on Android,
 * the class still loads (BREWER will be null, brewer_colors won't work but
 * basic colors will).
 *
 * Original bytecode in <clinit>:
 *   invokestatic ColorBrewer.instance()ColorBrewer
 *   putstatic Colors.BREWER : ColorBrewer
 *   ... rest of <clinit> ...
 *
 * Patched bytecode:
 *   tryStart:
 *     invokestatic ColorBrewer.instance()ColorBrewer
 *     putstatic Colors.BREWER : ColorBrewer
 *   tryEnd:
 *     goto afterCatch
 *   catchHandler:
 *     pop  // discard exception
 *   afterCatch:
 *     ... rest of <clinit> ...
 */
public class ColorsPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ColorsPatcher <gama.core.jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

        String targetClass = "gama/gaml/operators/Colors.class";
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
                    if (!mn.name.equals("<clinit>")) continue;

                    System.out.println("Found <clinit> in Colors");

                    // Find the invokestatic ColorBrewer.instance() call
                    AbstractInsnNode brewerInit = null;
                    for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                        if (insn instanceof MethodInsnNode min && min.getOpcode() == Opcodes.INVOKESTATIC) {
                            if (min.owner.contains("ColorBrewer") && min.name.equals("instance")) {
                                brewerInit = insn;
                                break;
                            }
                        }
                    }

                    if (brewerInit == null) {
                        System.out.println("WARNING: Could not find ColorBrewer.instance() call in <clinit>");
                        break;
                    }

                    System.out.println("Found ColorBrewer.instance() call, wrapping in try-catch");

                    // Find the putstatic after invokestatic (should be the next instruction)
                    AbstractInsnNode putStatic = brewerInit.getNext();
                    if (putStatic == null || putStatic.getOpcode() != Opcodes.PUTSTATIC) {
                        System.out.println("WARNING: Expected putstatic after ColorBrewer.instance()");
                        break;
                    }

                    // Build labels
                    LabelNode tryStart = new LabelNode();
                    LabelNode tryEnd = new LabelNode();
                    LabelNode catchHandler = new LabelNode();
                    LabelNode afterCatch = new LabelNode();

                    // Insert: tryStart before invokestatic
                    mn.instructions.insertBefore(brewerInit, tryStart);

                    // Insert after putstatic: tryEnd, goto afterCatch, catchHandler, pop, afterCatch
                    mn.instructions.insert(putStatic, tryEnd);

                    InsnList handler = new InsnList();
                    handler.add(new JumpInsnNode(Opcodes.GOTO, afterCatch));
                    handler.add(catchHandler);
                    handler.add(new InsnNode(Opcodes.POP));   // discard exception
                    handler.add(afterCatch);
                    mn.instructions.insert(tryEnd, handler);

                    // Add try-catch block
                    if (mn.tryCatchBlocks == null) mn.tryCatchBlocks = new ArrayList<>();
                    mn.tryCatchBlocks.add(0, new TryCatchBlockNode(tryStart, tryEnd, catchHandler, "java/lang/Exception"));

                    patched = true;
                    System.out.println("Patched Colors.<clinit>: wrapped BREWER init in try-catch(Exception)");
                }

                if (patched) {
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
