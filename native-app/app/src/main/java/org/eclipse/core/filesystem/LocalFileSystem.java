package org.eclipse.core.filesystem;

import java.net.URI;
import java.net.URISyntaxException;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

public class LocalFileSystem implements IFileSystem {
    public static final LocalFileSystem INSTANCE = new LocalFileSystem();

    @Override
    public IFileStore getStore(IPath path) {
        if (path == null) return null;
        return new LocalFileStore(new java.io.File(path.toString()));
    }

    @Override
    public IFileStore getStore(URI uri) {
        if (uri == null) return null;
        return new LocalFileStore(new java.io.File(uri));
    }

    @Override
    public int attributes() { return 0; }

    @Override
    public boolean canDelete() { return true; }

    @Override
    public boolean canWrite() { return true; }

    public URI toURI(java.io.File file) {
        try { return file.toURI(); } catch (Throwable t) { return null; }
    }

    public IPath toPath(java.io.File file) {
        return new Path(file.getAbsolutePath());
    }
}
