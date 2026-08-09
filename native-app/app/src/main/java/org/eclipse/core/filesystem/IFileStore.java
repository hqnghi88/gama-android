package org.eclipse.core.filesystem;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

public interface IFileStore {
    String getName();
    URI toURI();
    IFileInfo fetchInfo();
    InputStream openInputStream(int options, org.eclipse.core.runtime.IProgressMonitor monitor) throws CoreException;
    OutputStream openOutputStream(int options, org.eclipse.core.runtime.IProgressMonitor monitor) throws CoreException;
    IFileStore getChild(String name);
    IFileStore[] children(boolean fetchAttributes) throws CoreException;
}
