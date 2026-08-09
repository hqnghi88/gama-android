package org.eclipse.core.runtime;

public interface IExtension {
    IConfigurationElement[] getConfigurationElements();
    IExtensionPoint getExtensionPoint();
    String getSimpleIdentifier();
    String getNamespace();
    ExtensionContributor getContributor();
}

interface ExtensionContributor {
    String getName();
}
