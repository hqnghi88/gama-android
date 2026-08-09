import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches GamaGisFile.<clinit> to replace
 *   CacheBuilder.expireAfterAccess(Duration.of(5, ChronoUnit.MINUTES))
 * with
 *   CacheBuilder.expireAfterAccess(5, TimeUnit.MINUTES)
 * to fix NoSuchMethodError on Android (older Guava lacks Duration overload).
 */
public class CacheBuilderPatcher {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Usage: CacheBuilderPatcher <jar>"); System.exit(1); }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

        String targetClass = "gama/core/util/file/GamaGisFile.class";
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
                    if (!mn.name.equals("<clinit>")) continue;

                    // Collect all instructions into a list for indexed access
                    List<AbstractInsnNode> insns = new ArrayList<>();
                    for (AbstractInsnNode n = mn.instructions.getFirst(); n != null; n = n.getNext()) {
                        insns.add(n);
                    }

                    for (int i = 0; i < insns.size(); i++) {
                        AbstractInsnNode insn = insns.get(i);
                        if (!(insn instanceof MethodInsnNode)) continue;
                        MethodInsnNode min = (MethodInsnNode) insn;
                        // Looking for: CacheBuilder.expireAfterAccess(Ljava/time/Duration;)
                        if (!min.name.equals("expireAfterAccess") ||
                            !min.desc.equals("(Ljava/time/Duration;)Lcom/google/common/cache/CacheBuilder;")) continue;

                        // Found at index i. The sequence before it should be:
                        //   i-3: LDC2_W 5
                        //   i-2: GETSTATIC ChronoUnit.MINUTES
                        //   i-1: INVOKESTATIC Duration.of
                        //   i:   INVOKEVIRTUAL CacheBuilder.expireAfterAccess(Duration)
                        // Replace with:
                        //   LDC2_W 5
                        //   GETSTATIC TimeUnit.MINUTES
                        //   INVOKEVIRTUAL CacheBuilder.expireAfterAccess(long, TimeUnit)

                        if (i < 3) { System.err.println("Not enough instructions before expireAfterAccess"); break; }

                        AbstractInsnNode nodeLDC = insns.get(i - 3);
                        AbstractInsnNode nodeChrono = insns.get(i - 2);
                        AbstractInsnNode nodeDurationOf = insns.get(i - 1);

                        // Verify the instructions
                        boolean ok = true;
                        if (nodeLDC instanceof LdcInsnNode) {
                            LdcInsnNode ldc = (LdcInsnNode) nodeLDC;
                            if (!(ldc.cst instanceof Long) || ((Long) ldc.cst) != 5L) ok = false;
                        } else { ok = false; }

                        if (nodeChrono instanceof FieldInsnNode) {
                            FieldInsnNode fin = (FieldInsnNode) nodeChrono;
                            if (!fin.name.equals("MINUTES") || !fin.owner.equals("java/time/temporal/ChronoUnit")) ok = false;
                        } else { ok = false; }

                        if (nodeDurationOf instanceof MethodInsnNode) {
                            MethodInsnNode mof = (MethodInsnNode) nodeDurationOf;
                            if (!mof.name.equals("of") || !mof.owner.equals("java/time/Duration")) ok = false;
                        } else { ok = false; }

                        if (!ok) {
                            System.err.println("Instruction pattern mismatch at index " + i);
                            break;
                        }

                        // Remove old 4 instructions (i-3, i-2, i-1, i)
                        mn.instructions.remove(nodeLDC);
                        mn.instructions.remove(nodeChrono);
                        mn.instructions.remove(nodeDurationOf);
                        mn.instructions.remove(min);

                        // Insert new sequence: LDC2_W(5L), GETSTATIC(TimeUnit.MINUTES), INVOKEVIRTUAL(expireAfterAccess(J,TimeUnit))
                        InsnList il = new InsnList();
                        il.add(new LdcInsnNode(5L));
                        il.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/util/concurrent/TimeUnit", "MINUTES", "Ljava/util/concurrent/TimeUnit;"));
                        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "com/google/common/cache/CacheBuilder", "expireAfterAccess", "(JLjava/util/concurrent/TimeUnit;)Lcom/google/common/cache/CacheBuilder;", false));

                        // Insert at the same position (before the .build() call that follows)
                        // Find the next instruction after where we removed things
                        // Since we removed 4 instructions, find what's now at position i-3
                        if (i - 3 < mn.instructions.size()) {
                            mn.instructions.insertBefore(mn.instructions.get(i - 3), il);
                        } else {
                            mn.instructions.add(il);
                        }

                        patched = true;
                        System.out.println("Patched GamaGisFile.<clinit>: expireAfterAccess(Duration) -> expireAfterAccess(long, TimeUnit)");
                        break;
                    }
                }

                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                cn.accept(cw);
                data = cw.toByteArray();
            }

            ZipEntry outEntry = new ZipEntry(entry.getName());
            zipOut.putNextEntry(outEntry);
            zipOut.write(data);
            zipOut.closeEntry();
        }
        zipIn.close();
        zipOut.close();

        if (patched) {
            if (!jarFile.delete()) { System.err.println("Failed to delete original JAR"); System.exit(1); }
            if (!tmpJar.renameTo(jarFile)) { System.err.println("Failed to rename temp JAR"); System.exit(1); }
            System.out.println("JAR updated: " + jarFile.getName());
        } else {
            tmpJar.delete();
            System.err.println("WARNING: Target class not found or pattern not matched, JAR unchanged");
        }
    }
}
