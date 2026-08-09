#!/usr/bin/env groovy
/**
 * Standalone Groovy script to ASM-patch SimulationRunner$1.class
 * inside gama.core JARs to fix the deadlock bug.
 *
 * Problem: When agent.step() throws, experimentSemaphore.release() is never called,
 * permanently blocking the experiment controller's execution thread.
 *
 * Fix: Move experimentSemaphore.release() to after the try-catch (always executes).
 */
import org.objectweb.asm.*
import org.objectweb.asm.tree.*

def gamaCoreJar = new File(args[0])
if (!gamaCoreJar.exists()) {
    System.err.println("JAR not found: ${gamaCoreJar}")
    System.exit(1)
}

def tmpDir = new File(System.getProperty("java.io.tmpdir"), "asm_patch_${System.nanoTime()}")
tmpDir.mkdirs()

// Extract
["jar", "xf", gamaCoreJar.absolutePath].execute(null, tmpDir).waitFor()

def simRunner1File = new File(tmpDir, "gama/core/runtime/concurrent/SimulationRunner\$1.class")
if (!simRunner1File.exists()) {
    System.err.println("SimulationRunner\$1.class not found in JAR")
    tmpDir.deleteDir()
    System.exit(1)
}

def originalBytes = simRunner1File.bytes
def cr = new ClassReader(originalBytes)
def cn = new ClassNode()
cr.accept(cn, 0)

boolean patched = false

cn.methods.each { MethodNode mn ->
    if (mn.name == "run" && mn.desc == "()V") {
        def insns = mn.instructions.toArray()
        int releaseIdx = -1
        int getfieldExpSemIdx = -1

        for (int i = 0; i < insns.length; i++) {
            if (insns[i].getOpcode() == Opcodes.GETFIELD) {
                def fin = (FieldInsnNode) insns[i]
                if (fin.name == "experimentSemaphore") {
                    if (i + 1 < insns.length && insns[i + 1].getOpcode() == Opcodes.INVOKEVIRTUAL) {
                        def min = (MethodInsnNode) insns[i + 1]
                        if (min.name == "release" && min.desc == "()V") {
                            getfieldExpSemIdx = i
                            releaseIdx = i + 1
                        }
                    }
                }
            }
        }

        if (releaseIdx < 0) {
            System.err.println("Could not find experimentSemaphore.release() pattern")
            return
        }

        // Find chain start (this$0 GETFIELD)
        int chainStart = getfieldExpSemIdx
        for (int i = getfieldExpSemIdx - 1; i >= Math.max(0, getfieldExpSemIdx - 4); i--) {
            if (insns[i].getOpcode() == Opcodes.GETFIELD) {
                def fin = (FieldInsnNode) insns[i]
                if (fin.name == "this\$0") {
                    chainStart = i
                    break
                }
            }
        }

        // Remove the entire release chain from inside the try block
        for (int i = chainStart; i <= releaseIdx; i++) {
            mn.instructions.remove(insns[i])
        }

        // Now find all GOTO instructions and insert release() before each one
        // that goes back to the loop start
        def freshInsns = mn.instructions.toArray()
        int gotoCount = 0
        for (int i = 0; i < freshInsns.length; i++) {
            if (freshInsns[i].getOpcode() == Opcodes.GOTO) {
                def releaseCode = new InsnList()
                releaseCode.add(new VarInsnNode(Opcodes.ALOAD, 0))
                releaseCode.add(new FieldInsnNode(Opcodes.GETFIELD, cn.name, "this\$0", "Lgama/core/kernel/simulation/SimulationRunner;"))
                releaseCode.add(new FieldInsnNode(Opcodes.GETFIELD, "gama/core/kernel/simulation/SimulationRunner", "experimentSemaphore", "Lgama/core/common/interfaces/GeneralSynchronizer;"))
                releaseCode.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "gama/core/common/interfaces/GeneralSynchronizer", "release", "()V", false))
                mn.instructions.insertBefore(freshInsns[i], releaseCode)
                gotoCount++
            }
        }

        patched = true
        println("ASM-patched SimulationRunner\$1.run() - inserted ${gotoCount} experimentSemaphore.release() before GOTOs")
    }
}

if (!patched) {
    System.err.println("WARNING: Could not patch SimulationRunner\$1")
    tmpDir.deleteDir()
    System.exit(1)
}

// Write patched class
def cw = new ClassWriter(cr, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS)
cn.accept(cw)
simRunner1File.bytes = cw.toByteArray()

// Repack JAR
["jar", "cf", gamaCoreJar.absolutePath, "-C", tmpDir, "."].execute(null, tmpDir).waitFor()
tmpDir.deleteDir()
println("Repacked JAR: ${gamaCoreJar.name}")
