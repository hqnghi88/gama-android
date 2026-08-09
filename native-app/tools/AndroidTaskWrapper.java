package gama.core.runtime.concurrent;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

/**
 * Wraps a ParallelAgentRunner as a Callable for submission to a regular ExecutorService.
 * On Android, ForkJoinPool is non-functional; this class bridges the gap by allowing
 * compute() to be called on regular pool threads instead of ForkJoinWorkerThreads.
 */
public class AndroidTaskWrapper<T> implements Callable<T> {
    private final ParallelAgentRunner<T> runner;

    public AndroidTaskWrapper(ParallelAgentRunner<T> runner) {
        this.runner = runner;
    }

    @Override
    public T call() {
        return runner.compute();
    }

    /**
     * Blocks until the future completes, wrapping any exception in RuntimeException.
     * Used by patched compute() to replace ForkJoinTask.join().
     */
    public static void await(Future<?> future) {
        try {
            future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new RuntimeException(cause);
        }
    }
}
