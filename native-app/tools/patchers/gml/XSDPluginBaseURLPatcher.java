import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Real, loadable XSDPlugin base URL for Android (sweep across org.eclipse.xsd.jar).
 *
 * EMF's DelegatingResourceLocator.getBaseURL() (resolved from the gradle
 * org.eclipse.emf bundle, not a libs jar) throws
 *     NullPointerException: java.net.URL.toString() on a null object reference
 * on Android: its "<Class>.class" resource probe returns null for dexed classes,
 * so the plugin install base URL can never be resolved.
 *
 * Every consumer
 *     XSDPlugin.INSTANCE.getBaseURL().toString()
 * in org/eclipse/xsd is rewritten, at the bytecode level, to a call of the new
 * static helper XSDSchemaImpl.baseURL$android(), which builds a real
 * "jar:file:/.../base.apk!/" URL from a packaged APK resource entry (the cached
 * W3C schema files ship inside the app's own jar so ClassLoader.getResource()
 * resolves them even on Android). Consumers then append
 * "cache/www.w3.org/2001/..." style paths that exist as raw APK entries, so EMF
 * can actually read the cached MagicXMLSchema/XMLSchema schemas.
 *
 * The helper bytecode is emitted without try/catch (simple if-null branch) to
 * keep stack-map frames trivial. Signature files are stripped. The patch is
 * idempotent (a patched site no longer matches the original triple).
 */
public class XSDPluginBaseURLPatcher {

    private static final String HELPER_OWNER = "org/eclipse/xsd/impl/XSDSchemaImpl";
    private static final String HELPER_NAME = "baseURL$android";
    private static final String HELPER_DESC = "()Ljava/lang/String;";

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode n = insn.getNext();
        while (n != null && (n instanceof LabelNode
                || n instanceof LineNumberNode
                || n instanceof FrameNode)) {
            n = n.getNext();
        }
        return n;
    }

    /** Builds the instructions for {HELPER_NAME} as javac would emit them. */
    private static InsnList buildHelperBody() {
        InsnList il = new InsnList();
        LabelNode nullLbl = new LabelNode();

        il.add(new LdcInsnNode(Type.getObjectType(HELPER_OWNER)));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
                "getClassLoader", "()Ljava/lang/ClassLoader;", false));
        il.add(new LdcInsnNode("cache/www.w3.org/2001/xml.xsd"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/ClassLoader",
                "getResource", "(Ljava/lang/String;)Ljava/net/URL;", false));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new JumpInsnNode(Opcodes.IFNULL, nullLbl));
        il.add(new VarInsnNode(Opcodes.ASTORE, 0));              // URL u
        il.add(new VarInsnNode(Opcodes.ALOAD, 0));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/net/URL",
                "toExternalForm", "()Ljava/lang/String;", false));
        il.add(new VarInsnNode(Opcodes.ASTORE, 1));              // String s
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new InsnNode(Opcodes.ICONST_0));
        il.add(new VarInsnNode(Opcodes.ALOAD, 1));
        il.add(new LdcInsnNode("!/"));
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                "indexOf", "(Ljava/lang/String;)I", false));
        il.add(new InsnNode(Opcodes.ICONST_2));
        il.add(new InsnNode(Opcodes.IADD));                       // i+2
        il.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                "substring", "(II)Ljava/lang/String;", false));
        il.add(new InsnNode(Opcodes.ARETURN));
        il.add(nullLbl);
        il.add(new LdcInsnNode(""));
        il.add(new InsnNode(Opcodes.ARETURN));
        return il;
    }

    private static void addHelper(ClassNode cn) {
        if (cn.name.equals(HELPER_OWNER)) {
            for (MethodNode mn : cn.methods) {
                if (mn.name.equals(HELPER_NAME)) {
                    return; // idempotent: helper already present
                }
            }
            MethodNode helper = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                    HELPER_NAME, HELPER_DESC, null, null);
            helper.instructions.add(buildHelperBody());
            cn.methods.add(helper);
            System.out.println("Added helper " + HELPER_OWNER + "." + HELPER_NAME);
        }
    }

    private static boolean isSignatureEntry(String name) {
        if (!name.startsWith("META-INF/")) return false;
        return name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")
                || name.endsWith(".EC") || name.contains("/SIG-");
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: XSDPluginBaseURLPatcher <org.eclipse.xsd.jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found: " + jarFile); System.exit(1); }

        ZipFile zipIn = new ZipFile(jarFile);
        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));
        boolean anyChanged = false;

        try (zipIn) {
            Enumeration<? extends ZipEntry> entries = zipIn.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] data;
                try (InputStream is = zipIn.getInputStream(entry)) { data = is.readAllBytes(); }

                if (isSignatureEntry(entry.getName())) continue;

                if (entry.getName().endsWith(".class")) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(data).accept(cn, 0);

                    addHelper(cn);
                    boolean classChanged = false;
                    for (MethodNode mn : cn.methods) {
                        boolean methodChanged = false;
AbstractInsnNode insn = mn.instructions.getFirst();
                        while (insn != null) {
                            AbstractInsnNode insnToCheck = insn;
                            insn = insn.getNext();
                            if (insnToCheck instanceof FieldInsnNode fi
                                    && fi.getOpcode() == Opcodes.GETSTATIC
                                    && fi.owner.equals("org/eclipse/xsd/XSDPlugin")
                                    && fi.name.equals("INSTANCE")) {
                                AbstractInsnNode n2 = nextReal(insnToCheck);
                                if (n2 instanceof MethodInsnNode getBase
                                        && getBase.getOpcode() == Opcodes.INVOKEVIRTUAL
                                        && getBase.owner.equals("org/eclipse/xsd/XSDPlugin")
                                        && getBase.name.equals("getBaseURL")) {
                                    AbstractInsnNode n3 = nextReal(n2);
                                    if (n3 instanceof MethodInsnNode toString
                                            && toString.getOpcode() == Opcodes.INVOKEVIRTUAL
                                            && toString.owner.equals("java/net/URL")
                                            && toString.name.equals("toString")) {
                                        MethodInsnNode replacement =
                                                new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        HELPER_OWNER, HELPER_NAME, HELPER_DESC, false);
                                        mn.instructions.set(insnToCheck, replacement);
                                        mn.instructions.remove(n2);
                                        mn.instructions.remove(n3);
                                        insn = replacement.getNext();
                                        methodChanged = true;
                                        System.out.println("Patched " + cn.name + "." + mn.name
                                                + ": XSDPlugin.getBaseURL().toString() -> "
                                                + HELPER_NAME);
                                    }
                                }
                            }
                        }
                        if (methodChanged) classChanged = true;
                    }

                    if (classChanged || (cn.name.equals(HELPER_OWNER)
                            && hasHelper(cn))) {
                        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                            @Override
                            protected String getCommonSuperClass(String type1, String type2) {
                                try { return super.getCommonSuperClass(type1, type2); }
                                catch (Exception e) { return "java/lang/Object"; }
                            }
                        };
                        cn.accept(cw);
                        data = cw.toByteArray();
                        anyChanged = true;
                    }
                }

                zipOut.putNextEntry(new ZipEntry(entry.getName()));
                zipOut.write(data);
                zipOut.closeEntry();
            }
        }

        zipIn.close();
        zipOut.close();

        if (anyChanged) {
            jarFile.delete();
            tmpJar.renameTo(jarFile);
            System.out.println("JAR updated: " + jarFile.getName());
        } else if (tmpJar.exists()) {
            tmpJar.delete();
            System.out.println("No targets found");
        }
    }

    private static boolean hasHelper(ClassNode cn) {
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals(HELPER_NAME)) return true;
        }
        return false;
    }
}