import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

public class ProjectionPatcher {
    static final String TARGET = "gama/core/metamodel/topology/projection/Projection.class";

    public static void main(String[] args) throws Exception {
        if (args.length < 1) { System.err.println("Usage: ProjectionPatcher <jar>"); System.exit(1); }
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
                    if (!mn.name.equals("transform")) continue;
                    if (!mn.desc.equals("(Lorg/locationtech/jts/geom/Geometry;Z)Lorg/locationtech/jts/geom/Geometry;")) continue;

                    System.out.println("Found method: " + mn.name + mn.desc);

                    for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                        if (tcb.type != null && tcb.type.equals("org/opengis/referencing/operation/TransformException")) {
                            // Widen catch from TransformException to Throwable
                            tcb.type = "java/lang/Throwable";
                            patched = true;
                            System.out.println("Patched: widened catch to Throwable in " + mn.name + mn.desc);
                        }
                    }
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
}
