import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Replaces XMLInputFactory.newFactory() with newInstance() in jsvg's
 * StaxSVGLoader so StAX works on Android (stax-api 1.0-2 only provides
 * newInstance()). newInstance() still resolves the woodstox provider via
 * META-INF/services.
 */
public class StaxNewFactoryPatcher {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: StaxNewFactoryPatcher <jsvg.jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

        String targetClass = "com/github/weisj/jsvg/parser/impl/StaxSVGLoader.class";

        ZipFile zipIn = new ZipFile(jarFile);
        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));
        boolean anyPatched = false;

        Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            byte[] data;
            try (InputStream is = zipIn.getInputStream(entry)) { data = is.readAllBytes(); }

            if (entry.getName().equals(targetClass)) {
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);

                for (MethodNode mn : cn.methods) {
                    boolean methodChanged = false;
                    for (AbstractInsnNode insn : mn.instructions) {
                        if (insn instanceof MethodInsnNode mi
                                && mi.getOpcode() == Opcodes.INVOKESTATIC
                                && mi.owner.equals("javax/xml/stream/XMLInputFactory")
                                && mi.name.equals("newFactory")
                                && mi.desc.equals("()Ljavax/xml/stream/XMLInputFactory;")) {
                            mi.name = "newInstance";
                            methodChanged = true;
                        }
                    }
                    if (methodChanged) {
                        System.out.println("Patched " + cn.name + "." + mn.name + ": newFactory() -> newInstance()");
                        anyPatched = true;
                    }
                }

                if (anyPatched) {
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

        if (anyPatched) {
            jarFile.delete();
            tmpJar.renameTo(jarFile);
            System.out.println("JAR updated: " + jarFile.getName());
        } else {
            tmpJar.delete();
            System.out.println("No targets found");
        }
    }
}
