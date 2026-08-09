package org.eclipse.core.runtime;

public interface IProgressMonitor {
    void beginTask(String name, int totalWork);
    void worked(int work);
    void done();
    boolean isCanceled();
    void setCanceled(boolean value);
    void subTask(String name);
}
