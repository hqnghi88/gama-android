package org.eclipse.core.runtime;

public interface IExtensionRegistry {
    IExtensionPoint getExtensionPoint(String extensionPointId);
    IConfigurationElement[] getConfigurationElementsFor(String extensionPointId);
    IExtension[] getExtensions(String extensionPointId);
}
