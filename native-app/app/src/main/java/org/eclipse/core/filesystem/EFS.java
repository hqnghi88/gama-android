package org.eclipse.core.filesystem;

import java.net.URI;

public class EFS {
    public static IFileSystem getLocalFileSystem() { return null; }
    public static IFileSystem getFileSystem(String scheme) throws CoreException { return null; }
    public static IFileStore getStore(URI uri) throws CoreException { return null; }
}
