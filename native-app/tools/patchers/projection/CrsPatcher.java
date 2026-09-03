import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches ProjectionFactory.<clinit> to catch Exception instead of just FactoryException
 * in the CRS.decode("EPSG:3857") static initializer.
 *
 * On Android, CRS.decode throws InvalidParameterValueException (not a FactoryException),
 * causing ExceptionInInitializerError which prevents SimulationAgent creation.
 */
public class CrsPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: CrsPatcher <gama.core.jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

        String targetClass = "gama/core/metamodel/topology/projection/ProjectionFactory.class";

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

                    System.out.println("Found <clinit> in ProjectionFactory");

                    if (mn.tryCatchBlocks == null) {
                        System.out.println("WARNING: No try-catch blocks found, inspecting instructions...");
                        for (AbstractInsnNode insn : mn.instructions) {
                            if (insn instanceof TypeInsnNode) {
                                System.out.println("  TypeInsn: " + ((TypeInsnNode)insn).desc);
                            }
                        }
                        continue;
                    }

                    System.out.println("Found " + mn.tryCatchBlocks.size() + " try-catch block(s)");
                    for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                        System.out.println("  catch: " + tcb.type + " handler=" + tcb.handler);
                        if (tcb.type != null && (tcb.type.equals("org/opengis/referencing/FactoryException")
                                || tcb.type.equals("org/geotools/util/factory/FactoryException"))) {
                            System.out.println("Changing catch from FactoryException to java/lang/Exception");
                            tcb.type = "java/lang/Exception";
                            patched = true;
                        }
                    }
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
                    System.out.println("Patched ProjectionFactory.<clinit>: catch widened to Exception");
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
