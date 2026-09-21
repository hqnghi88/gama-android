import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Diagnostic patcher: makes ExecutionScope.setCurrentError(GamaRuntimeException)
 * echo every recorded GAML runtime error to System.out. The app tees System.out
 * to logcat ([stdout] lines), so any error that silently pauses the simulation
 * through the DefaultExperimentController step() path becomes visible.
 *
 * The scope's current error is transient (cleared right after the failing step),
 * which is why app-side probes keep reading null at stall time. This patch sees
 * the error at the exact moment it is recorded, including the GAML message with
 * the statement context.
 */
public class ErrorEchoPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ErrorEchoPatcher <gama.api.jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

        String targetClass = "gama/api/runtime/scope/ExecutionScope.class";
        String targetMethod = "setCurrentError";
        String targetDesc = "(Lgama/api/exceptions/GamaRuntimeException;)V";

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
                    if (!mn.name.equals(targetMethod) || !mn.desc.equals(targetDesc)) continue;
                    InsnList insns = mn.instructions;
                    if (insns == null || insns.size() == 0) continue;

                    InsnList probe = new InsnList();
                    probe.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/System", "out",
                            "Ljava/io/PrintStream;"));
                    probe.add(new VarInsnNode(Opcodes.ALOAD, 1));
                    probe.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/io/PrintStream",
                            "println", "(Ljava/lang/Object;)V", false));

                    AbstractInsnNode first = insns.getFirst();
                    if (first == null) continue;
                    insns.insertBefore(first, probe);
                    patched = true;
                }
                if (patched) {
                    ClassWriter cw = new ClassWriter(0);
                    cn.accept(cw);
                    data = cw.toByteArray();
                }
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
            System.out.println("ErrorEchoPatcher: patched " + targetClass);
        } else {
            tmpJar.delete();
            System.err.println("ErrorEchoPatcher: method " + targetMethod + " not found in " + targetClass);
            System.exit(1);
        }
    }
}