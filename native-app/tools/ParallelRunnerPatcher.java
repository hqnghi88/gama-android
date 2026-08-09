import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.util.*;
import java.util.zip.*;

/**
 * Patches gama.core for Android parallel execution:
 *
 * 1. GamaExecutorService:
 *    - Adds ANDROID_PARALLEL_EXECUTOR (ExecutorService) field
 *    - Initializes it in setConcurrencyLevel() via Executors.newCachedThreadPool()
 *    - Patches executeThreaded() to use ANDROID_PARALLEL_EXECUTOR instead of broken ForkJoinPool
 *
 * 2. ParallelAgentRunner:
 *    - execute(ForkJoinTask) -> calls task.invoke() (bypasses broken ForkJoinPool)
 *    - compute() -> uses AndroidTaskWrapper + ANDROID_PARALLEL_EXECUTOR for true parallelism
 *      via ExecutorService.submit() + Future.get() instead of ForkJoinTask fork()/join()
 *
 * 3. Adds AndroidTaskWrapper.class to the JAR
 */
public class ParallelRunnerPatcher {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: ParallelRunnerPatcher <gama.core.jar> [AndroidTaskWrapper.class]");
            System.exit(1);
        }
        File jarFile = new File(args[0]);
        if (!jarFile.exists()) { System.err.println("JAR not found"); System.exit(1); }

        // Read the pre-compiled AndroidTaskWrapper.class if provided
        byte[] wrapperClassBytes = null;
        if (args.length >= 2) {
            File wrapperFile = new File(args[1]);
            if (wrapperFile.exists()) {
                wrapperClassBytes = java.nio.file.Files.readAllBytes(wrapperFile.toPath());
                System.out.println("Read AndroidTaskWrapper.class: " + wrapperFile.length() + " bytes");
            }
        }

        String runnerClass = "gama/core/runtime/concurrent/ParallelAgentRunner.class";
        String executorClass = "gama/core/runtime/concurrent/GamaExecutorService.class";
        String wrapperEntry = "gama/core/runtime/concurrent/AndroidTaskWrapper.class";

        ZipFile zipIn = new ZipFile(jarFile);
        File tmpJar = new File(jarFile.getAbsolutePath() + ".tmp");
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(tmpJar));
        boolean patchedRunner = false;
        boolean patchedExecutor = false;
        boolean wrapperAdded = false;
        boolean wrapperAlreadyInJar = false;

        // First pass: check if wrapper already exists
        Enumeration<? extends ZipEntry> checkEntries = zipIn.entries();
        while (checkEntries.hasMoreElements()) {
            if (checkEntries.nextElement().getName().equals(wrapperEntry)) {
                wrapperAlreadyInJar = true;
                break;
            }
        }

        Enumeration<? extends ZipEntry> entries = zipIn.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            byte[] data;
            try (InputStream is = zipIn.getInputStream(entry)) { data = is.readAllBytes(); }

            // --- Patch ParallelAgentRunner ---
            if (entry.getName().equals(runnerClass)) {
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);

                for (MethodNode mn : cn.methods) {
                    // Patch execute(ForkJoinTask) -> task.invoke()
                    if (mn.name.equals("execute") && mn.desc.equals("(Ljava/util/concurrent/ForkJoinTask;)Ljava/lang/Object;")) {
                        mn.instructions.clear();
                        mn.tryCatchBlocks.clear();
                        mn.localVariables.clear();

                        InsnList insns = new InsnList();
                        LabelNode nonnull = new LabelNode();
                        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        insns.add(new JumpInsnNode(Opcodes.IFNONNULL, nonnull));
                        insns.add(new InsnNode(Opcodes.ACONST_NULL));
                        insns.add(new InsnNode(Opcodes.ARETURN));
                        insns.add(nonnull);
                        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                "java/util/concurrent/ForkJoinTask", "invoke",
                                "()Ljava/lang/Object;", false));
                        insns.add(new InsnNode(Opcodes.ARETURN));

                        mn.maxStack = 2;
                        mn.maxLocals = 1;
                        mn.instructions.insert(insns);
                        patchedRunner = true;
                        System.out.println("Patched: " + runnerClass + " -> execute() calls task.invoke()");
                    }

                    // Patch compute() -> uses AndroidTaskWrapper + ANDROID_PARALLEL_EXECUTOR
                    if (mn.name.equals("compute") && mn.desc.equals("()Ljava/lang/Object;")) {
                        mn.instructions.clear();
                        mn.tryCatchBlocks.clear();
                        mn.localVariables.clear();

                        LabelNode L_recursive = new LabelNode();

                        InsnList insns = new InsnList();
                        // Spliterator<IAgent> sub = agents.trySplit();
                        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        insns.add(new FieldInsnNode(Opcodes.GETFIELD,
                                "gama/core/runtime/concurrent/ParallelAgentRunner",
                                "agents", "Ljava/util/Spliterator;"));
                        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                                "java/util/Spliterator", "trySplit",
                                "()Ljava/util/Spliterator;", true));
                        insns.add(new VarInsnNode(Opcodes.ASTORE, 1));

                        // if (sub == null) goto recursive
                        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        insns.add(new JumpInsnNode(Opcodes.IFNONNULL, L_recursive));

                        // return executeOn(originalScope)
                        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        insns.add(new FieldInsnNode(Opcodes.GETFIELD,
                                "gama/core/runtime/concurrent/ParallelAgentRunner",
                                "originalScope", "Lgama/core/runtime/IScope;"));
                        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                "gama/core/runtime/concurrent/ParallelAgentRunner",
                                "executeOn",
                                "(Lgama/core/runtime/IScope;)Ljava/lang/Object;", false));
                        insns.add(new InsnNode(Opcodes.ARETURN));

                        // L_recursive:
                        insns.add(L_recursive);

                        // ParallelAgentRunner<T> left = subTask(sub);
                        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
                        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                "gama/core/runtime/concurrent/ParallelAgentRunner",
                                "subTask",
                                "(Ljava/util/Spliterator;)Lgama/core/runtime/concurrent/ParallelAgentRunner;",
                                false));
                        insns.add(new VarInsnNode(Opcodes.ASTORE, 2));

                        // Future<T> leftFuture = GamaExecutorService.ANDROID_PARALLEL_EXECUTOR.submit(new AndroidTaskWrapper<>(left));
                        insns.add(new FieldInsnNode(Opcodes.GETSTATIC,
                                "gama/core/runtime/concurrent/GamaExecutorService",
                                "ANDROID_PARALLEL_EXECUTOR",
                                "Ljava/util/concurrent/ExecutorService;"));
                        insns.add(new TypeInsnNode(Opcodes.NEW,
                                "gama/core/runtime/concurrent/AndroidTaskWrapper"));
                        insns.add(new InsnNode(Opcodes.DUP));
                        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
                        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                                "gama/core/runtime/concurrent/AndroidTaskWrapper",
                                "<init>",
                                "(Lgama/core/runtime/concurrent/ParallelAgentRunner;)V",
                                false));
                        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                                "java/util/concurrent/ExecutorService",
                                "submit",
                                "(Ljava/util/concurrent/Callable;)Ljava/util/concurrent/Future;",
                                true));
                        insns.add(new VarInsnNode(Opcodes.ASTORE, 3));

                        // T result = compute();
                        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                                "gama/core/runtime/concurrent/ParallelAgentRunner",
                                "compute",
                                "()Ljava/lang/Object;", false));
                        insns.add(new VarInsnNode(Opcodes.ASTORE, 4));

                        // AndroidTaskWrapper.await(leftFuture);
                        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
                        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "gama/core/runtime/concurrent/AndroidTaskWrapper",
                                "await",
                                "(Ljava/util/concurrent/Future;)V", false));

                        // return result;
                        insns.add(new VarInsnNode(Opcodes.ALOAD, 4));
                        insns.add(new InsnNode(Opcodes.ARETURN));

                        mn.maxStack = 5;
                        mn.maxLocals = 5;
                        mn.instructions.insert(insns);
                        patchedRunner = true;
                        System.out.println("Patched: " + runnerClass + " -> compute() uses AndroidTaskWrapper + ExecutorService");
                    }
                }

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

            // --- Patch GamaExecutorService ---
            if (entry.getName().equals(executorClass)) {
                ClassNode cn = new ClassNode();
                new ClassReader(data).accept(cn, 0);

                // 1. Add ANDROID_PARALLEL_EXECUTOR field
                boolean hasField = false;
                for (FieldNode fn : cn.fields) {
                    if (fn.name.equals("ANDROID_PARALLEL_EXECUTOR")) {
                        hasField = true;
                        break;
                    }
                }
                if (!hasField) {
                    cn.fields.add(new FieldNode(
                            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE,
                            "ANDROID_PARALLEL_EXECUTOR",
                            "Ljava/util/concurrent/ExecutorService;",
                            null, null));
                    System.out.println("Added field: ANDROID_PARALLEL_EXECUTOR to " + executorClass);
                }

                for (MethodNode mn : cn.methods) {
                    // 2. Patch setConcurrencyLevel() — add ANDROID_PARALLEL_EXECUTOR initialization
                    if (mn.name.equals("setConcurrencyLevel") && mn.desc.equals("(I)V")) {
                        // Find the last RETURN instruction and insert before it
                        InsnList insns = mn.instructions;
                        for (int i = insns.size() - 1; i >= 0; i--) {
                            AbstractInsnNode node = insns.get(i);
                            if (node.getOpcode() == Opcodes.RETURN) {
                                InsnList initCode = new InsnList();
                                LabelNode skipShutdown = new LabelNode();

                                // if (ANDROID_PARALLEL_EXECUTOR != null) goto skip_shutdown
                                initCode.add(new FieldInsnNode(Opcodes.GETSTATIC,
                                        "gama/core/runtime/concurrent/GamaExecutorService",
                                        "ANDROID_PARALLEL_EXECUTOR",
                                        "Ljava/util/concurrent/ExecutorService;"));
                                initCode.add(new JumpInsnNode(Opcodes.IFNULL, skipShutdown));

                                // ANDROID_PARALLEL_EXECUTOR.shutdown()
                                initCode.add(new FieldInsnNode(Opcodes.GETSTATIC,
                                        "gama/core/runtime/concurrent/GamaExecutorService",
                                        "ANDROID_PARALLEL_EXECUTOR",
                                        "Ljava/util/concurrent/ExecutorService;"));
                                initCode.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                                        "java/util/concurrent/ExecutorService",
                                        "shutdown", "()V", true));

                                // skip_shutdown:
                                initCode.add(skipShutdown);

                                // ANDROID_PARALLEL_EXECUTOR = Executors.newCachedThreadPool()
                                initCode.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                        "java/util/concurrent/Executors",
                                        "newCachedThreadPool",
                                        "()Ljava/util/concurrent/ExecutorService;", false));
                                initCode.add(new FieldInsnNode(Opcodes.PUTSTATIC,
                                        "gama/core/runtime/concurrent/GamaExecutorService",
                                        "ANDROID_PARALLEL_EXECUTOR",
                                        "Ljava/util/concurrent/ExecutorService;"));

                                insns.insertBefore(node, initCode);
                                patchedExecutor = true;
                                System.out.println("Patched: " + executorClass + " -> setConcurrencyLevel() initializes ANDROID_PARALLEL_EXECUTOR");
                                break;
                            }
                        }
                    }

                    // 3. Patch getParallelism() — always return 0 (sequential stepping)
                    //    This prevents ParallelAgentRunner.compute() from recursively submitting
                    //    tasks to ANDROID_PARALLEL_EXECUTOR which causes thread explosion on Android
                    if (mn.name.equals("getParallelism") && mn.desc.contains("Caller")) {
                        mn.instructions.clear();
                        mn.tryCatchBlocks.clear();
                        mn.localVariables.clear();

                        InsnList insns = new InsnList();
                        insns.add(new InsnNode(Opcodes.ICONST_0));
                        insns.add(new InsnNode(Opcodes.IRETURN));
                        mn.instructions.insert(insns);
                        mn.maxStack = 1;
                        mn.maxLocals = 4;
                        patchedExecutor = true;
                        System.out.println("Patched: " + executorClass + " -> getParallelism() returns 0 (sequential stepping)");
                    }

                    // 4. Patch executeThreaded() — use ANDROID_PARALLEL_EXECUTOR
                    if (mn.name.equals("executeThreaded") && mn.desc.equals("(Ljava/lang/Runnable;)V")) {
                        mn.instructions.clear();
                        mn.tryCatchBlocks.clear();
                        mn.localVariables.clear();

                        InsnList insns = new InsnList();
                        // getstatic ANDROID_PARALLEL_EXECUTOR
                        insns.add(new FieldInsnNode(Opcodes.GETSTATIC,
                                "gama/core/runtime/concurrent/GamaExecutorService",
                                "ANDROID_PARALLEL_EXECUTOR",
                                "Ljava/util/concurrent/ExecutorService;"));
                        // aload_0 (the Runnable parameter)
                        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
                        // invokeinterface submit(Runnable) -> Future
                        insns.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
                                "java/util/concurrent/ExecutorService",
                                "submit",
                                "(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;",
                                true));
                        // invokestatic AndroidTaskWrapper.await(Future)
                        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                                "gama/core/runtime/concurrent/AndroidTaskWrapper",
                                "await",
                                "(Ljava/util/concurrent/Future;)V", false));
                        // return
                        insns.add(new InsnNode(Opcodes.RETURN));

                        mn.maxStack = 2;
                        mn.maxLocals = 1;
                        mn.instructions.insert(insns);
                        patchedExecutor = true;
                        System.out.println("Patched: " + executorClass + " -> executeThreaded() uses ANDROID_PARALLEL_EXECUTOR");
                    }
                }

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

            zipOut.putNextEntry(new ZipEntry(entry.getName()));
            zipOut.write(data);
            zipOut.closeEntry();
        }

        // Add AndroidTaskWrapper.class if provided and not already in JAR
        if (wrapperClassBytes != null && !wrapperAlreadyInJar && !wrapperAdded) {
            zipOut.putNextEntry(new ZipEntry(wrapperEntry));
            zipOut.write(wrapperClassBytes);
            zipOut.closeEntry();
            wrapperAdded = true;
            System.out.println("Added: " + wrapperEntry + " (" + wrapperClassBytes.length + " bytes)");
        }

        zipIn.close();
        zipOut.close();

        if (patchedRunner || patchedExecutor || wrapperAdded) {
            jarFile.delete();
            tmpJar.renameTo(jarFile);
            System.out.println("JAR updated: " + jarFile.getName());
        } else {
            tmpJar.delete();
            System.out.println("No targets found");
        }
    }
}
