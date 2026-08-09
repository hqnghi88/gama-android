import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches eclipse-core-stubs.jar and gama.core JAR to add missing IWorkspace.getRoot(),
 * IWorkspaceRoot interface, and Workspace.getRoot() implementation.
 */
public class EclipseCorePatcher {

    static byte[] patchIWorkspace(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        // Check if getRoot already exists
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("getRoot")) return classBytes;
        }

        // Add: IWorkspaceRoot getRoot();
        MethodNode getRoot = new MethodNode(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            "getRoot",
            "()Lorg/eclipse/core/resources/IWorkspaceRoot;",
            null, null);
        cn.methods.add(getRoot);

        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    static byte[] patchWorkspace(byte[] classBytes) {
        ClassReader cr = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        cr.accept(cn, 0);

        // Check if getRoot already exists
        for (MethodNode mn : cn.methods) {
            if (mn.name.equals("getRoot")) return classBytes;
        }

        // Add: IWorkspaceRoot getRoot() { return new WorkspaceRoot(); }
        MethodNode getRoot = new MethodNode(
            Opcodes.ACC_PUBLIC,
            "getRoot",
            "()Lorg/eclipse/core/resources/IWorkspaceRoot;",
            null, null);

        InsnList il = new InsnList();
        il.add(new TypeInsnNode(Opcodes.NEW, "org/eclipse/core/resources/WorkspaceRoot"));
        il.add(new InsnNode(Opcodes.DUP));
        il.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
            "org/eclipse/core/resources/WorkspaceRoot", "<init>", "()V", false));
        il.add(new InsnNode(Opcodes.ARETURN));
        getRoot.instructions = il;
        getRoot.maxStack = 2;

        cn.methods.add(getRoot);

        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    static byte[] createWorkspaceRoot() {
        ClassNode cn = new ClassNode();
        cn.visit(Opcodes.V11, Opcodes.ACC_PUBLIC,
            "org/eclipse/core/resources/WorkspaceRoot", null,
            "java/lang/Object",
            new String[]{"org/eclipse/core/resources/IWorkspaceRoot"});

        // Constructor
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        InsnList initIl = new InsnList();
        initIl.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initIl.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        initIl.add(new InsnNode(Opcodes.RETURN));
        init.instructions = initIl;
        init.maxStack = 1;
        cn.methods.add(init);

        // getLocation(): IPath
        MethodNode getLocation = new MethodNode(Opcodes.ACC_PUBLIC, "getLocation",
            "()Lorg/eclipse/core/runtime/IPath;", null, null);
        InsnList locIl = new InsnList();
        locIl.add(new TypeInsnNode(Opcodes.NEW, "org/eclipse/core/runtime/Path"));
        locIl.add(new InsnNode(Opcodes.DUP));
        locIl.add(new LdcInsnNode("/"));
        locIl.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
            "org/eclipse/core/runtime/Path", "<init>", "(Ljava/lang/String;)V", false));
        locIl.add(new InsnNode(Opcodes.ARETURN));
        getLocation.instructions = locIl;
        getLocation.maxStack = 3;
        cn.methods.add(getLocation);

        // getLocationURI(): URI
        MethodNode getLocationURI = new MethodNode(Opcodes.ACC_PUBLIC, "getLocationURI",
            "()Ljava/net/URI;", null, null);
        InsnList uriIl = new InsnList();
        uriIl.add(new LdcInsnNode("file:///"));
        uriIl.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "java/net/URI", "create", "(Ljava/lang/String;)Ljava/net/URI;", false));
        uriIl.add(new InsnNode(Opcodes.ARETURN));
        getLocationURI.instructions = uriIl;
        getLocationURI.maxStack = 1;
        cn.methods.add(getLocationURI);

        // getPathVariableManager(): IPathVariableManager
        MethodNode getPM = new MethodNode(Opcodes.ACC_PUBLIC, "getPathVariableManager",
            "()Lorg/eclipse/core/resources/IPathVariableManager;", null, null);
        InsnList pmIl = new InsnList();
        pmIl.add(new TypeInsnNode(Opcodes.NEW, "org/eclipse/core/resources/PathVariableManager"));
        pmIl.add(new InsnNode(Opcodes.DUP));
        pmIl.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
            "org/eclipse/core/resources/PathVariableManager", "<init>", "()V", false));
        pmIl.add(new InsnNode(Opcodes.ARETURN));
        getPM.instructions = pmIl;
        getPM.maxStack = 2;
        cn.methods.add(getPM);

        // findContainersForLocation(IPath): IContainer[] - returns empty array
        MethodNode findContainers = new MethodNode(Opcodes.ACC_PUBLIC, "findContainersForLocation",
            "(Lorg/eclipse/core/runtime/IPath;)[Lorg/eclipse/core/resources/IContainer;", null, null);
        InsnList fcIl = new InsnList();
        fcIl.add(new IntInsnNode(Opcodes.BIPUSH, 0));
        fcIl.add(new TypeInsnNode(Opcodes.ANEWARRAY, "org/eclipse/core/resources/IContainer"));
        fcIl.add(new InsnNode(Opcodes.ARETURN));
        findContainers.instructions = fcIl;
        findContainers.maxStack = 1;
        cn.methods.add(findContainers);

        // findFilesForLocation(IPath): IFile[] - returns empty array
        MethodNode findFiles = new MethodNode(Opcodes.ACC_PUBLIC, "findFilesForLocation",
            "(Lorg/eclipse/core/runtime/IPath;)[Lorg/eclipse/core/resources/IFile;", null, null);
        InsnList ffiIl = new InsnList();
        ffiIl.add(new IntInsnNode(Opcodes.BIPUSH, 0));
        ffiIl.add(new TypeInsnNode(Opcodes.ANEWARRAY, "org/eclipse/core/resources/IFile"));
        ffiIl.add(new InsnNode(Opcodes.ARETURN));
        findFiles.instructions = ffiIl;
        findFiles.maxStack = 1;
        cn.methods.add(findFiles);

        // getFile(IPath): IFile - returns null
        MethodNode getFile = new MethodNode(Opcodes.ACC_PUBLIC, "getFile",
            "(Lorg/eclipse/core/runtime/IPath;)Lorg/eclipse/core/resources/IFile;", null, null);
        InsnList gfIl = new InsnList();
        gfIl.add(new InsnNode(Opcodes.ACONST_NULL));
        gfIl.add(new InsnNode(Opcodes.ARETURN));
        getFile.instructions = gfIl;
        getFile.maxStack = 1;
        cn.methods.add(getFile);

        // getFolder(IPath): IFolder - returns null
        MethodNode getFolder = new MethodNode(Opcodes.ACC_PUBLIC, "getFolder",
            "(Lorg/eclipse/core/runtime/IPath;)Lorg/eclipse/core/resources/IFolder;", null, null);
        InsnList gfoIl = new InsnList();
        gfoIl.add(new InsnNode(Opcodes.ACONST_NULL));
        gfoIl.add(new InsnNode(Opcodes.ARETURN));
        getFolder.instructions = gfoIl;
        getFolder.maxStack = 1;
        cn.methods.add(getFolder);

        // getProjects(): IProject[] - returns empty array
        MethodNode getProjects = new MethodNode(Opcodes.ACC_PUBLIC, "getProjects",
            "()[Lorg/eclipse/core/resources/IProject;", null, null);
        InsnList gpIl = new InsnList();
        gpIl.add(new IntInsnNode(Opcodes.BIPUSH, 0));
        gpIl.add(new TypeInsnNode(Opcodes.ANEWARRAY, "org/eclipse/core/resources/IProject"));
        gpIl.add(new InsnNode(Opcodes.ARETURN));
        getProjects.instructions = gpIl;
        getProjects.maxStack = 1;
        cn.methods.add(getProjects);

        cn.visitEnd();

        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    static byte[] createIWorkspaceRootInterface() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
            "org/eclipse/core/resources/IWorkspaceRoot", null,
            "java/lang/Object", null);

        cw.visitEnd();

        ClassNode cn = new ClassNode();
        cn.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
            "org/eclipse/core/resources/IWorkspaceRoot", null,
            "java/lang/Object", null);

        cn.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            "getLocation", "()Lorg/eclipse/core/runtime/IPath;", null, null);
        cn.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            "getLocationURI", "()Ljava/net/URI;", null, null);
        cn.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            "getPathVariableManager", "()Lorg/eclipse/core/resources/IPathVariableManager;", null, null);
        cn.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            "findContainersForLocation", "(Lorg/eclipse/core/runtime/IPath;)[Lorg/eclipse/core/resources/IContainer;", null, null);
        cn.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            "findFilesForLocation", "(Lorg/eclipse/core/runtime/IPath;)[Lorg/eclipse/core/resources/IFile;", null, null);
        cn.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            "getFile", "(Lorg/eclipse/core/runtime/IPath;)Lorg/eclipse/core/resources/IFile;", null, null);
        cn.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            "getFolder", "(Lorg/eclipse/core/runtime/IPath;)Lorg/eclipse/core/resources/IFolder;", null, null);
        cn.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            "getProjects", "()[Lorg/eclipse/core/resources/IProject;", null, null);

        cn.visitEnd();

        ClassWriter cw2 = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cn.accept(cw2);
        return cw2.toByteArray();
    }

    static byte[] createIPathVariableManager() {
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        cw.visit(Opcodes.V11, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT,
            "org/eclipse/core/resources/IPathVariableManager", null,
            "java/lang/Object", null);

        // setValue(String, IPath)
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            "setValue", "(Ljava/lang/String;Lorg/eclipse/core/runtime/IPath;)V", null,
            new String[]{"org/eclipse/core/runtime/CoreException"});

        // getValue(String): IPath
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            "getValue", "(Ljava/lang/String;)Lorg/eclipse/core/runtime/IPath;", null, null);

        // getURIValue(String): URI
        cw.visitMethod(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
            "getURIValue", "(Ljava/lang/String;)Ljava/net/URI;", null, null);

        cw.visitEnd();
        return cw.toByteArray();
    }

    static byte[] createPathVariableManagerImpl() {
        ClassNode cn = new ClassNode();
        cn.visit(Opcodes.V11, Opcodes.ACC_PUBLIC,
            "org/eclipse/core/resources/PathVariableManager", null,
            "java/lang/Object",
            new String[]{"org/eclipse/core/resources/IPathVariableManager"});

        // Field: Map<String, IPath> variables
        FieldNode variables = new FieldNode(Opcodes.ACC_PRIVATE, "variables",
            "Ljava/util/Map;", "Ljava/util/Map<Ljava/lang/String;Lorg/eclipse/core/runtime/IPath;>;", null);
        cn.fields.add(variables);

        // Constructor
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        InsnList initIl = new InsnList();
        initIl.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initIl.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        initIl.add(new VarInsnNode(Opcodes.ALOAD, 0));
        initIl.add(new TypeInsnNode(Opcodes.NEW, "java/util/HashMap"));
        initIl.add(new InsnNode(Opcodes.DUP));
        initIl.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/util/HashMap", "<init>", "()V", false));
        initIl.add(new FieldInsnNode(Opcodes.PUTFIELD, "org/eclipse/core/resources/PathVariableManager", "variables", "Ljava/util/Map;"));
        initIl.add(new InsnNode(Opcodes.RETURN));
        init.instructions = initIl;
        init.maxStack = 2;
        cn.methods.add(init);

        // setValue(String, IPath): void
        MethodNode setValue = new MethodNode(Opcodes.ACC_PUBLIC, "setValue",
            "(Ljava/lang/String;Lorg/eclipse/core/runtime/IPath;)V", null,
            new String[]{"org/eclipse/core/runtime/CoreException"});
        InsnList setIl = new InsnList();
        setIl.add(new VarInsnNode(Opcodes.ALOAD, 0));
        setIl.add(new FieldInsnNode(Opcodes.GETFIELD, "org/eclipse/core/resources/PathVariableManager", "variables", "Ljava/util/Map;"));
        setIl.add(new VarInsnNode(Opcodes.ALOAD, 1));
        setIl.add(new VarInsnNode(Opcodes.ALOAD, 2));
        setIl.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "put", "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", true));
        setIl.add(new InsnNode(Opcodes.POP));
        setIl.add(new InsnNode(Opcodes.RETURN));
        setValue.instructions = setIl;
        setValue.maxStack = 3;
        cn.methods.add(setValue);

        // getValue(String): IPath
        MethodNode getValue = new MethodNode(Opcodes.ACC_PUBLIC, "getValue",
            "(Ljava/lang/String;)Lorg/eclipse/core/runtime/IPath;", null, null);
        InsnList getIl = new InsnList();
        getIl.add(new VarInsnNode(Opcodes.ALOAD, 0));
        getIl.add(new FieldInsnNode(Opcodes.GETFIELD, "org/eclipse/core/resources/PathVariableManager", "variables", "Ljava/util/Map;"));
        getIl.add(new VarInsnNode(Opcodes.ALOAD, 1));
        getIl.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/Map", "get", "(Ljava/lang/Object;)Ljava/lang/Object;", true));
        getIl.add(new TypeInsnNode(Opcodes.CHECKCAST, "org/eclipse/core/runtime/IPath"));
        getIl.add(new InsnNode(Opcodes.ARETURN));
        getValue.instructions = getIl;
        getValue.maxStack = 2;
        cn.methods.add(getValue);

        // getURIValue(String): URI
        MethodNode getURIValue = new MethodNode(Opcodes.ACC_PUBLIC, "getURIValue",
            "(Ljava/lang/String;)Ljava/net/URI;", null, null);
        InsnList uriIl = new InsnList();
        uriIl.add(new LdcInsnNode("file:///"));
        uriIl.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/net/URI", "create", "(Ljava/lang/String;)Ljava/net/URI;", false));
        uriIl.add(new InsnNode(Opcodes.ARETURN));
        getURIValue.instructions = uriIl;
        getURIValue.maxStack = 1;
        cn.methods.add(getURIValue);

        cn.visitEnd();
        ClassWriter cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        cn.accept(cw);
        return cw.toByteArray();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: EclipseCorePatcher <eclipse-core-stubs.jar> <gama.core.jar>");
            System.exit(1);
        }

        File stubsJar = new File(args[0]);
        File gamaJar = new File(args[1]);

        // Patch IWorkspace in gama.core
        patchJar(gamaJar, "org/eclipse/core/resources/IWorkspace.class", EclipseCorePatcher::patchIWorkspace);
        System.out.println("Patched IWorkspace in gama.core: added getRoot()");

        // Patch eclipse-core-stubs.jar
        patchJar(stubsJar, "org/eclipse/core/resources/Workspace.class", EclipseCorePatcher::patchWorkspace);
        System.out.println("Patched Workspace in eclipse-core-stubs: added getRoot()");

        // Add IWorkspaceRoot interface and WorkspaceRoot class to stubs jar
        addNewClasses(stubsJar);
        System.out.println("Added IWorkspaceRoot, WorkspaceRoot, IPathVariableManager, PathVariableManager to eclipse-core-stubs");
    }

    interface ClassPatcher {
        byte[] patch(byte[] original) throws Exception;
    }

    static void patchJar(File jarFile, String targetEntry, ClassPatcher patcher) throws Exception {
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

            if (entry.getName().equals(targetEntry)) {
                data = patcher.patch(data);
                patched = true;
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
        } else {
            tmpJar.delete();
            System.err.println("WARNING: Target " + targetEntry + " not found in " + jarFile.getName());
        }
    }

    static void addNewClasses(File jarFile) throws Exception {
        ZipFile zipIn = new ZipFile(jarFile);
        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));

        Set<String> existingEntries = new HashSet<>();
        // Copy all existing entries
        Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            existingEntries.add(entry.getName());
            byte[] data;
            try (InputStream is = zipIn.getInputStream(entry)) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = is.read(buf)) > 0) baos.write(buf, 0, n);
                data = baos.toByteArray();
            }
            ZipEntry outEntry = new ZipEntry(entry.getName());
            zipOut.putNextEntry(outEntry);
            zipOut.write(data);
            zipOut.closeEntry();
        }
        zipIn.close();

        // Add new classes only if not already present
        if (!existingEntries.contains("org/eclipse/core/resources/IWorkspaceRoot.class")) {
            ZipEntry iwrEntry = new ZipEntry("org/eclipse/core/resources/IWorkspaceRoot.class");
            zipOut.putNextEntry(iwrEntry);
            zipOut.write(createIWorkspaceRootInterface());
            zipOut.closeEntry();
        }

        if (!existingEntries.contains("org/eclipse/core/resources/WorkspaceRoot.class")) {
            ZipEntry wrEntry = new ZipEntry("org/eclipse/core/resources/WorkspaceRoot.class");
            zipOut.putNextEntry(wrEntry);
            zipOut.write(createWorkspaceRoot());
            zipOut.closeEntry();
        }

        if (!existingEntries.contains("org/eclipse/core/resources/IPathVariableManager.class")) {
            ZipEntry ipmEntry = new ZipEntry("org/eclipse/core/resources/IPathVariableManager.class");
            zipOut.putNextEntry(ipmEntry);
            zipOut.write(createIPathVariableManager());
            zipOut.closeEntry();
        }

        if (!existingEntries.contains("org/eclipse/core/resources/PathVariableManager.class")) {
            ZipEntry pmEntry = new ZipEntry("org/eclipse/core/resources/PathVariableManager.class");
            zipOut.putNextEntry(pmEntry);
            zipOut.write(createPathVariableManagerImpl());
            zipOut.closeEntry();
        }

        zipOut.close();

        jarFile.delete();
        tmpJar.renameTo(jarFile);
    }
}
