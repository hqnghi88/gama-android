package org.eclipse.core.runtime;

public class Path implements IPath {
    private final String path;

    public Path(String path) {
        this.path = path != null ? path : "";
    }

    public static Path fromOSString(String path) {
        return new Path(path);
    }

    @Override public IPath append(String child) { return new Path(this.path + "/" + child); }
    @Override public IPath append(IPath child) { return new Path(this.path + "/" + child.toString()); }
    @Override public IPath removeLastSegments(int count) { return new Path(path); }
    @Override public IPath makeRelative() { return this; }
    @Override public IPath makeAbsolute() { return this.path.startsWith("/") ? this : new Path("/" + path); }
    @Override public IPath removeFileExtension() {
        int dot = path.lastIndexOf('.');
        int sep = path.lastIndexOf('/');
        if (dot > sep) return new Path(path.substring(0, dot));
        return this;
    }
    @Override public String getFileExtension() {
        int dot = path.lastIndexOf('.');
        int sep = path.lastIndexOf('/');
        if (dot > sep) return path.substring(dot + 1);
        return null;
    }
    @Override public IPath removeTrailingSeparator() { return this; }
    @Override public IPath addTrailingSeparator() { return this; }
    @Override public IPath segment(int index) { return null; }
    @Override public int segmentCount() { return 0; }
    @Override public boolean isAbsolute() { return path.startsWith("/"); }
    @Override public boolean isEmpty() { return path.isEmpty(); }
    @Override public boolean isRoot() { return "/".equals(path); }
    @Override public boolean hasTrailingSeparator() { return path.endsWith("/"); }
    @Override public String toString() { return path; }
    @Override public String toOSString() { return path; }
    @Override public String toFileString() { return path; }
    @Override public String lastSegment() {
        int sep = path.lastIndexOf('/');
        return sep >= 0 ? path.substring(sep + 1) : path;
    }
    @Override public IPath upLevels(int count) { return this; }
    @Override public IPath prefix(IPath base) { return this; }
    @Override public String[] segments() { return new String[]{path}; }
    @Override public boolean isPrefixOf(IPath another) { return path.startsWith(another.toString()); }
    @Override public boolean isPrefixOf(String another) { return path.startsWith(another); }
    @Override public IPath setDevice(String device) { return this; }
    @Override public IPath addFileExtension(String extension) { return this; }
    @Override public java.io.File toFile() { return new java.io.File(path); }
    @Override public java.net.URI toURI() {
        try { return new java.io.File(path).toURI(); } catch (Exception e) { return java.net.URI.create("file:///"); }
    }
}
