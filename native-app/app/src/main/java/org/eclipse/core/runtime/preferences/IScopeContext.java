package org.eclipse.core.runtime.preferences;

public interface IScopeContext {
    IEclipsePreferences getNode(String qualifier);
}
