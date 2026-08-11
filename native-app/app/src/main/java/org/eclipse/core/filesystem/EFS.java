package org.eclipse.core.filesystem;

import java.net.URI;

import org.eclipse.core.runtime.Path;

public class EFS {
    public static IFileSystem getLocalFileSystem() { return LocalFileSystem.INSTANCE; }
    public static IFileSystem getFileSystem(String scheme) throws CoreException { return LocalFileSystem.INSTANCE; }
    public static IFileStore getStore(URI uri) throws CoreException {
        if (uri == null) return null;
        if (uri.getScheme() == null || "file".equalsIgnoreCase(uri.getScheme())) {
            return LocalFileSystem.INSTANCE.getStore(new Path(new java.io.File(uri).getAbsolutePath()));
        }
        return null;
    }
}
