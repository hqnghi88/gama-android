package org.eclipse.core.filesystem;

import java.net.URI;

public class URIUtil {
    public static URI toURI(String pathString) { return URI.create(pathString); }
    public static String toURIString(URI uri) { return uri.toString(); }
}
