package org.eclipse.core.filesystem;

import java.io.File;

public class LocalFileInfo implements IFileInfo {
    private final File file;

    public LocalFileInfo(File file) { this.file = file; }

    @Override
    public String getName() { return file != null ? file.getName() : null; }

    @Override
    public boolean exists() { return file != null && file.exists(); }

    @Override
    public long getLength() { return file != null && file.isFile() ? file.length() : 0; }

    @Override
    public boolean isDirectory() { return file != null && file.isDirectory(); }

    @Override
    public long getLastModified() { return file != null ? file.lastModified() : 0; }
}
