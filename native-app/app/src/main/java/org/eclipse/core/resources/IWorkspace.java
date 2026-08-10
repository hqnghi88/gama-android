package org.eclipse.core.resources;

/**
 * Android stub of org.eclipse.core.resources.IWorkspace.
 * Only the methods actually referenced by the GAMA jars on the Android boot
 * and compilation path are declared, matching the concrete stub Workspace.
 */
public interface IWorkspace {

    IWorkspaceRoot getRoot();

    void addResourceChangeListener(IResourceChangeListener listener);

    void addResourceChangeListener(IResourceChangeListener listener, int eventMask);

    void removeResourceChangeListener(IResourceChangeListener listener);

    boolean isAutoBuilding();
}
