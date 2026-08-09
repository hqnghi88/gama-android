package java.awt;

public class Rectangle extends java.awt.geom.Rectangle2D.Double {
    public Rectangle() { super(0, 0, 0, 0); }
    public Rectangle(int x, int y, int width, int height) { super(x, y, width, height); }
    public Rectangle(int width, int height) { super(0, 0, width, height); }
    public Rectangle(java.awt.Point p) { super(p.x, p.y, 0, 0); }
    public Rectangle(java.awt.Point p, java.awt.Dimension d) { super(p.x, p.y, d.width, d.height); }
    public Rectangle(Rectangle r) { super(r.x, r.y, r.width, r.height); }

    public boolean isEmpty() { return width <= 0 || height <= 0; }
    public void setBounds(int x, int y, int width, int height) { setRect(x, y, width, height); }
    public void setBounds(Rectangle r) { setRect(r.x, r.y, r.width, r.height); }
    public java.awt.Rectangle getBounds() { return new java.awt.Rectangle(this); }
    public java.awt.geom.Rectangle2D getBounds2D() { return new java.awt.geom.Rectangle2D.Double(x, y, width, height); }
    public int getXInt() { return (int) x; }
    public int getYInt() { return (int) y; }
    public int getWidthInt() { return (int) width; }
    public int getHeightInt() { return (int) height; }
    public boolean contains(double x, double y) { return x >= this.x && y >= this.y && x < this.x + this.width && y < this.y + this.height; }
    public boolean contains(double x, double y, double w, double h) { return false; }
    public boolean contains(java.awt.geom.Point2D p) { return contains(p.getX(), p.getY()); }
    public boolean contains(java.awt.geom.Rectangle2D r) { return false; }
    public boolean intersects(double x, double y, double w, double h) { return false; }
    public boolean intersects(java.awt.geom.Rectangle2D r) { return false; }
    public java.awt.geom.PathIterator getPathIterator(java.awt.geom.AffineTransform at) { return null; }
    public java.awt.geom.PathIterator getPathIterator(java.awt.geom.AffineTransform at, double flatness) { return null; }
    public boolean equals(Object obj) { if (obj instanceof Rectangle) { Rectangle r = (Rectangle) obj; return r.x == x && r.y == y && r.width == width && r.height == height; } return false; }
    public int hashCode() { return java.util.Objects.hash(x, y, width, height); }
    public String toString() { return "java.awt.Rectangle[x=" + (int)x + ",y=" + (int)y + ",width=" + (int)width + ",height=" + (int)height + "]"; }
}
