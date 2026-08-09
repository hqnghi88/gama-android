package org.eclipse.core.runtime;

public interface IConfigurationElement {
    String getAttribute(String name) throws InvalidRegistryObjectException;
    IConfigurationElement[] getChildren(String name) throws InvalidRegistryObjectException;
    IExtension getDeclaringExtension() throws InvalidRegistryObjectException;
    Object createExecutableExtension(String attributeName) throws CoreException;
}
