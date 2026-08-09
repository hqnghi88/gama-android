package org.eclipse.core.filesystem;

public class CoreException extends Exception {
    public CoreException(String message) { super(message); }
    public CoreException(String message, Throwable cause) { super(message, cause); }
}
