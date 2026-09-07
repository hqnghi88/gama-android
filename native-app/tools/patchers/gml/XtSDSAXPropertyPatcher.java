import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Makes org.geotools.xsd.Parser tolerate Android's SAX implementation.
 *
 * Parser.parser(boolean) wires the Apache Xerces-only property
 * "http://apache.org/xml/properties/schema/external-schemaLocation" onto the
 * SAXParser it just built. On the desktop JVM (Xerces) this property exists; on
 * Android the JAXP SAXParser is backed by org.apache.harmony.xml.expat.ExpatReader,
 * whose setProperty() rejects it with SAXNotRecognizedException. That exception is
 * thrown eagerly at parser setup, so any GML/GIS read (GamaGMLFile ->
 * org.geotools.wfs.GML.decodeFeatureCollection -> org.geotools.xsd.Parser) dies with
 * "GamaRuntimeException: Java error: SAXNotRecognizedException" before a single
 * feature is produced.
 *
 * The fix mirrors the fail-soft pattern GeoTools itself already uses in
 * setupEntityExpansionLimit(): wrap the external-schemaLocation setProperty call in
 * a try/catch for SAXNotRecognizedException and ignore the failure. The property is
 * only an optimization hint for schema resolution; skipping it lets the parser fall
 * back to its in-memory configuration bindings.
 *
 * Mechanical approach (to keep bytecode verifier/ART friendly): instead of
 * inserting an inline try/catch into the middle of the method (which disturbs
 * existing stack-map-frame boundaries), the 3-instruction tail of the property
 * wiring
 *     aload 4                 ; StringBuffer xsdLocation
 *     StringBuffer.toString
 *     SAXParser.setProperty(String,Object)
 * is replaced by a single call to a NEW static helper
 *     Parser.setProperty$android(SAXParser, String, StringBuffer)
 * added to the class. The helper performs toString() + setProperty() under a
 * try/catch(SAXNotRecognizedException) that swallows the failure. Stack effects
 * around the call site are identical to the original (net: nothing left on stack),
 * so no existing frame is disturbed. The patch is idempotent (re-running over an
 * already-patched jar is a no-op).
 */
public class XtSDSAXPropertyPatcher {

    private static final String TARGET_URI = "http://apache.org/xml/properties/schema/external-schemaLocation";
    private static final String EXC = "org/xml/sax/SAXNotRecognizedException";
    private static final String HELPER = "setProperty$android";
    private static final String HELPER_DESC =
            "(Ljavax/xml/parsers/SAXParser;Ljava/lang/String;Ljava/lang/StringBuffer;)V";

    private static AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode n = insn.getNext();
        while (n != null && (n instanceof LabelNode
                || n instanceof LineNumberNode
                || n instanceof FrameNode)) {
            n = n.getNext();
        }
        return n;
    }

    private static MethodNode buildHelper() {
        MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                HELPER, HELPER_DESC, null, null);
        InsnList body = new InsnList();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode done = new LabelNode();

        body.add(tryStart);
        body.add(new VarInsnNode(Opcodes.ALOAD, 0));
        body.add(new LdcInsnNode(TARGET_URI));
        body.add(new VarInsnNode(Opcodes.ALOAD, 2));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "java/lang/StringBuffer", "toString", "()Ljava/lang/String;", false));
        body.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                "javax/xml/parsers/SAXParser", "setProperty",
                "(Ljava/lang/String;Ljava/lang/Object;)V", false));
        body.add(tryEnd);
        body.add(new JumpInsnNode(Opcodes.GOTO, done));
        body.add(handler);
        body.add(new InsnNode(Opcodes.POP));
        body.add(done);
        body.add(new InsnNode(Opcodes.RETURN));
        m.instructions.add(body);
        m.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handler, EXC));
        m.maxStack = 4;
        m.maxLocals = 3;
        return m;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: XtSDSAXPropertyPatcher <gt-xsd-core.jar>");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found: " + jarFile); System.exit(1); }

        String targetClass = "org/geotools/xsd/Parser.class";
        ZipFile zipIn = new ZipFile(jarFile);
        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));
        boolean anyPatched = false;

        try (zipIn) {
            Enumeration<? extends ZipEntry> entries = zipIn.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                byte[] data;
                try (InputStream is = zipIn.getInputStream(entry)) { data = is.readAllBytes(); }

                if (entry.getName().equals(targetClass)) {
                    ClassNode cn = new ClassNode();
                    new ClassReader(data).accept(cn, 0);

                    if (cn.methods.stream().anyMatch(m -> m.name.equals(HELPER))) {
                        System.out.println("XtSDSAXPropertyPatcher: already patched, skipping");
                    } else {
                        boolean found = false;
                        for (MethodNode mn : cn.methods) {
                            if (!mn.name.equals("parser") || !mn.desc.equals("(Z)Ljavax/xml/parsers/SAXParser;")) {
                                continue;
                            }
                            AbstractInsnNode insn = mn.instructions.getFirst();
                            while (insn != null) {
                                AbstractInsnNode next = nextReal(insn);
                                if (insn instanceof LdcInsnNode ldc
                                        && ldc.cst instanceof String s
                                        && s.equals(TARGET_URI)) {
                                    AbstractInsnNode n2 = nextReal(insn);
                                    if (n2 instanceof VarInsnNode v1 && v1.getOpcode() == Opcodes.ALOAD && v1.var == 4) {
                                        AbstractInsnNode n3 = nextReal(n2);
                                        if (n3 instanceof MethodInsnNode mi
                                                && mi.getOpcode() == Opcodes.INVOKEVIRTUAL
                                                && mi.owner.equals("java/lang/StringBuffer")
                                                && mi.name.equals("toString")) {
                                            AbstractInsnNode n4 = nextReal(n3);
                                            if (n4 instanceof MethodInsnNode setProp
                                                    && setProp.getOpcode() == Opcodes.INVOKEVIRTUAL
                                                    && setProp.owner.equals("javax/xml/parsers/SAXParser")
                                                    && setProp.name.equals("setProperty")) {
                                                AbstractInsnNode call = new MethodInsnNode(Opcodes.INVOKESTATIC,
                                                        "org/geotools/xsd/Parser", HELPER, HELPER_DESC, false);
                                                mn.instructions.set(n2, new VarInsnNode(Opcodes.ALOAD, 4));
                                                mn.instructions.set(n3, call);
                                                mn.instructions.remove(n4);
                                                found = true;
                                                System.out.println("Patched " + cn.name + "." + mn.name
                                                        + ": setProperty(" + TARGET_URI + ") -> " + HELPER);
                                                break;
                                            }
                                        }
                                    }
                                }
                                insn = next;
                            }
                        }

                        if (found) {
                            cn.methods.add(buildHelper());
                            ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES) {
                                @Override
                                protected String getCommonSuperClass(String type1, String type2) {
                                    try { return super.getCommonSuperClass(type1, type2); }
                                    catch (Exception e) { return "java/lang/Object"; }
                                }
                            };
                            cn.accept(cw);
                            data = cw.toByteArray();
                            anyPatched = true;
                        } else {
                            System.out.println("XtSDSAXPropertyPatcher: target sequence not found");
                        }
                    }
                }

                zipOut.putNextEntry(new ZipEntry(entry.getName()));
                zipOut.write(data);
                zipOut.closeEntry();
            }
        }

        zipIn.close();
        zipOut.close();

        if (anyPatched) {
            jarFile.delete();
            tmpJar.renameTo(jarFile);
            System.out.println("JAR updated: " + jarFile.getName());
        } else if (tmpJar.exists()) {
            tmpJar.delete();
            System.out.println("No targets found");
        }
    }
}