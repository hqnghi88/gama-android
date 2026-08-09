package org.eclipse.core.filesystem;

public interface IFileInfo {
    String getName();
    boolean exists();
    long getLength();
    boolean isDirectory();
    long getLastModified();
}
