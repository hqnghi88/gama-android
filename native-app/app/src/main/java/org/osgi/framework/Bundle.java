package org.osgi.framework;

import java.io.IOException;
import java.net.URL;
import java.util.Collections;
import java.util.Enumeration;

public interface Bundle {
    String getSymbolicName();
    Class<?> loadClass(String name) throws ClassNotFoundException;
    URL getResource(String name);
    Enumeration<URL> getResources(String name) throws IOException;
    URL getEntry(String path);
    Enumeration<URL> findEntries(String path, String filePattern, boolean recurse);
}
