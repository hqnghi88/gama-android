package org.eclipse.core.filesystem;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Path;

public class LocalFileStore implements IFileStore {
    private final File file;

    public LocalFileStore(File file) { this.file = file; }

    @Override
    public String getName() { return file != null ? file.getName() : null; }

    @Override
    public URI toURI() { return file != null ? file.toURI() : null; }

    @Override
    public IFileInfo fetchInfo() { return new LocalFileInfo(file); }

    @Override
    public InputStream openInputStream(int options, IProgressMonitor monitor) throws CoreException {
        try { return new FileInputStream(file); }
        catch (Exception e) { throw new CoreException("Cannot open input stream for " + file, e); }
    }

    @Override
    public OutputStream openOutputStream(int options, IProgressMonitor monitor) throws CoreException {
        try { return new FileOutputStream(file); }
        catch (Exception e) { throw new CoreException("Cannot open output stream for " + file, e); }
    }

    @Override
    public IFileStore getChild(String name) {
        return new LocalFileStore(new File(file, name));
    }

    @Override
    public IFileStore[] children(boolean fetchAttributes) throws CoreException {
        File[] children = file.listFiles();
        if (children == null) return new IFileStore[0];
        IFileStore[] stores = new IFileStore[children.length];
        for (int i = 0; i < children.length; i++) stores[i] = new LocalFileStore(children[i]);
        return stores;
    }

    public IPath toLocalPath() { return new Path(file.getAbsolutePath()); }
}
