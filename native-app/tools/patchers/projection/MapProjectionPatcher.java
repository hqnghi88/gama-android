import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class MapProjectionPatcher {
    static final String TARGET = "org/geotools/referencing/operation/projection/MapProjection.class";
    static final String X_FIELD = "java/awt/geom/Point2D$Double";
    static final String POINT2D = "java/awt/geom/Point2D";
    // Temp locals for putfield conversion (slots 10-13, slot 9 is used by catch handler)
    static final int X_TEMP = 10;
    static final int Y_TEMP = 12;

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Usage: MapProjectionPatcher <jar>"); System.exit(1); }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

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

            if (entry.getName().equals(TARGET)) {
                ClassReader cr = new ClassReader(data);
                ClassNode cn = new ClassNode();
                cr.accept(cn, 0);

                for (MethodNode mn : cn.methods) {
                    if (!hasFieldAccess(mn)) continue;
                    System.out.println("Patching method: " + mn.name + mn.desc);
                    patchMethod(mn);
                    patched = true;
                }

                ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
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
            System.err.println("WARNING: Target not found or not patched, JAR unchanged");
        }
    }

    static boolean hasFieldAccess(MethodNode mn) {
        for (AbstractInsnNode insn : mn.instructions) {
            if (insn instanceof FieldInsnNode) {
                FieldInsnNode fin = (FieldInsnNode) insn;
                if (fin.owner.equals(X_FIELD) && (fin.name.equals("x") || fin.name.equals("y")))
                    return true;
            }
        }
        return false;
    }

    static void patchMethod(MethodNode mn) {
        // First pass: replace getfield x/y with getX()/getY()
        for (AbstractInsnNode insn : mn.instructions) {
            if (!(insn instanceof FieldInsnNode)) continue;
            FieldInsnNode fin = (FieldInsnNode) insn;
            if (!fin.owner.equals(X_FIELD)) continue;
            if (fin.getOpcode() != Opcodes.GETFIELD) continue;

            if (fin.name.equals("x")) {
                mn.instructions.set(insn, new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, POINT2D, "getX", "()D", false));
            } else if (fin.name.equals("y")) {
                mn.instructions.set(insn, new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL, POINT2D, "getY", "()D", false));
            }
        }

        // Second pass: replace putfield x/y pairs with setLocation()
        // Strategy:
        //   putfield x → dstore X_TEMP + pop
        //   (retain the intervening aload point + y computation)
        //   putfield y → dstore Y_TEMP + dload X_TEMP + dload Y_TEMP + invokevirtual setLocation
        ListIterator<AbstractInsnNode> it = mn.instructions.iterator();
        while (it.hasNext()) {
            AbstractInsnNode insn = it.next();
            if (!(insn instanceof FieldInsnNode)) continue;
            FieldInsnNode fin = (FieldInsnNode) insn;
            if (!fin.owner.equals(X_FIELD) || fin.getOpcode() != Opcodes.PUTFIELD) continue;
            if (!fin.name.equals("x")) continue;

            // Find the matching putfield y that follows
            AbstractInsnNode yPut = findNextPutfieldY(insn);
            if (yPut == null) {
                System.out.println("  WARNING: putfield x without matching putfield y, skipping");
                continue;
            }

            // Replace putfield x → dstore X_TEMP + pop
            InsnList xRepl = new InsnList();
            xRepl.add(new VarInsnNode(Opcodes.DSTORE, X_TEMP));
            xRepl.add(new InsnNode(Opcodes.POP));
            mn.instructions.insertBefore(insn, xRepl);
            mn.instructions.remove(insn);

            // Replace putfield y → dstore Y_TEMP + dload X_TEMP + dload Y_TEMP + invokevirtual setLocation
            InsnList yRepl = new InsnList();
            yRepl.add(new VarInsnNode(Opcodes.DSTORE, Y_TEMP));
            yRepl.add(new VarInsnNode(Opcodes.DLOAD, X_TEMP));
            yRepl.add(new VarInsnNode(Opcodes.DLOAD, Y_TEMP));
            yRepl.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                POINT2D, "setLocation", "(DD)V", false));
            mn.instructions.insertBefore(yPut, yRepl);
            mn.instructions.remove(yPut);
        }
    }

    static AbstractInsnNode findNextPutfieldY(AbstractInsnNode start) {
        AbstractInsnNode cur = start.getNext();
        while (cur != null) {
            if (cur instanceof FieldInsnNode) {
                FieldInsnNode fin = (FieldInsnNode) cur;
                if (fin.owner.equals(X_FIELD) && fin.name.equals("y")
                    && fin.getOpcode() == Opcodes.PUTFIELD) {
                    return cur;
                }
            }
            cur = cur.getNext();
        }
        return null;
    }
}
