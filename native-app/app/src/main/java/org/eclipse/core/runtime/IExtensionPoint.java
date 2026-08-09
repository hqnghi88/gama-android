package org.eclipse.core.runtime;

public interface IExtensionPoint {
    IExtension[] getExtensions();
    String getSimpleIdentifier();
    String getUniqueIdentifier();
}
