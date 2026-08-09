package org.eclipse.core.resources;

public interface IResource extends org.eclipse.core.runtime.IAdaptable {
    int FILE = 1;
    int FOLDER = 2;
    int PROJECT = 4;
    int ROOT = 8;

    String getName();
    org.eclipse.core.runtime.IPath getFullPath();
    org.eclipse.core.runtime.IPath getLocation();
    boolean exists();
    IProject getProject();
    Object getAdapter(Class adapter);
}
