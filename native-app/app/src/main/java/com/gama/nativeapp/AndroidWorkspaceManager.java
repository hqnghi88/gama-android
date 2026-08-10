package com.gama.nativeapp;

import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.Workspace;
import org.eclipse.core.resources.WorkspaceRoot;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;

import gama.api.runtime.IWorkspaceManager;

/**
 * Android implementation of IWorkspaceManager. The desktop implementation
 * (gama.workspace.manager.WorkspaceManager) relies on OSGi service tracking,
 * which does not exist on Android, so the bootstrap installs this one instead.
 */
public class AndroidWorkspaceManager implements IWorkspaceManager {

    private final IWorkspace workspace = new Workspace();
    private final IWorkspaceRoot root = new WorkspaceRoot();

    @Override
    public IWorkspace getWorkspace() {
        return workspace;
    }

    @Override
    public IWorkspaceRoot getRoot() {
        return root;
    }

    @Override
    public org.eclipse.emf.common.util.URI getWorkspaceURI() {
        return org.eclipse.emf.common.util.URI.createURI("file:///");
    }

    @Override
    public String getWorkspaceLocation() {
        return "/";
    }

    @Override
    public IPath getWorkspacePath() {
        return new Path("/");
    }

    @Override
    public String checkWorkspaceDirectory(String dir, boolean remember, boolean ask, boolean rebuild) {
        return dir;
    }

    @Override
    public String getModelIdentifier() {
        return null;
    }

    @Override
    public void setLastSetWorkspaceDirectory(String dir) {
    }

    @Override
    public String getLastSetWorkspaceDirectory() {
        return null;
    }

    @Override
    public void isRememberWorkspace(boolean value) {
    }

    @Override
    public void setLastUsedWorkspaces(String value) {
    }

    @Override
    public boolean isRememberWorkspace() {
        return false;
    }

    @Override
    public String getLastUsedWorkspaces() {
        return null;
    }

    @Override
    public Object checkWorkspace() {
        return null;
    }

    @Override
    public void forceWorkspaceRebuild() {
    }

    @Override
    public void clearWorkspace(boolean delete) {
    }

    @Override
    public String getCurrentGamaStampString() {
        return "";
    }
}
