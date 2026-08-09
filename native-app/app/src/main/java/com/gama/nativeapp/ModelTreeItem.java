package com.gama.nativeapp;

import java.util.ArrayList;
import java.util.List;

public class ModelTreeItem {

    public enum Type { CATEGORY, MODEL_FILE, FILE }

    private final String name;
    private final String fullPath;
    private final Type type;
    private int depth;
    private ModelTreeItem parent;
    private final List<ModelTreeItem> children = new ArrayList<>();
    private boolean expanded;
    private long fileSize;

    public ModelTreeItem(String name, String fullPath, Type type, int depth, ModelTreeItem parent) {
        this.name = name;
        this.fullPath = fullPath;
        this.type = type;
        this.depth = depth;
        this.parent = parent;
    }

    public String getName() { return name; }
    public String getFullPath() { return fullPath; }
    public Type getType() { return type; }
    public int getDepth() { return depth; }
    public void setDepth(int depth) { this.depth = depth; }
    public ModelTreeItem getParent() { return parent; }
    public void setParent(ModelTreeItem parent) { this.parent = parent; }
    public List<ModelTreeItem> getChildren() { return children; }
    public boolean isExpanded() { return expanded; }
    public void setExpanded(boolean expanded) { this.expanded = expanded; }
    public boolean isDirectory() { return type == Type.CATEGORY; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long size) { this.fileSize = size; }

    public String getExtension() {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "";
    }

    public static int getExtensionColor(String ext) {
        switch (ext) {
            case "gaml": return 0xFF4CAF50;
            case "png": case "jpg": case "jpeg": case "gif": case "tif": case "tiff": return 0xFF2196F3;
            case "shp": case "shx": case "dbf": case "prj": case "qpj": case "fix": case "cpg": return 0xFFFF9800;
            case "csv": case "txt": case "asc": return 0xFF9C27B0;
            case "xml": case "prefs": case "project": case "qix": return 0xFF607D8B;
            case "obj": return 0xFF795548;
            default: return 0xFF888888;
        }
    }

    public static String getExtensionLabel(String ext) {
        switch (ext) {
            case "gaml": return "GAML";
            case "png": return "PNG";
            case "jpg": case "jpeg": return "JPG";
            case "gif": return "GIF";
            case "shp": return "SHP";
            case "shx": return "SHX";
            case "dbf": return "DBF";
            case "prj": return "PRJ";
            case "csv": return "CSV";
            case "xml": return "XML";
            case "asc": return "ASC";
            case "obj": return "OBJ";
            case "tif": case "tiff": return "TIF";
            case "prefs": return "PRF";
            case "project": return "PRJ";
            case "txt": return "TXT";
            default: return ext.toUpperCase();
        }
    }
}
