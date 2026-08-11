package org.eclipse.core.filesystem;

import java.net.URI;

import org.eclipse.core.runtime.IPath;

public interface IFileSystem {
    IFileStore getStore(IPath path);
    IFileStore getStore(URI uri);
    int attributes();
    boolean canDelete();
    boolean canWrite();
}
