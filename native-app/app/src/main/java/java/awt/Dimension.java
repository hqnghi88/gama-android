package java.awt;

public class Dimension extends java.awt.geom.Dimension2D {
    public int width, height;
    public Dimension() { this(0, 0); }
    public Dimension(int width, int height) { this.width = width; this.height = height; }
    public Dimension(java.awt.Dimension d) { this(d.width, d.height); }
    public double getWidth() { return width; }
    public double getHeight() { return height; }
    public void setSize(double width, double height) { this.width = (int) width; this.height = (int) height; }
    public void setSize(int width, int height) { this.width = width; this.height = height; }
    public void setSize(java.awt.Dimension d) { setSize(d.width, d.height); }
    public boolean equals(Object obj) { if (obj instanceof Dimension) { Dimension d = (Dimension) obj; return d.width == width && d.height == height; } return false; }
    public String toString() { return "java.awt.Dimension[width=" + width + ",height=" + height + "]"; }
}
