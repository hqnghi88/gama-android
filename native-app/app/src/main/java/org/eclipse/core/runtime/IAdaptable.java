package org.eclipse.core.runtime;

public interface IAdaptable {
    <T> T getAdapter(Class<T> adapter);
}
