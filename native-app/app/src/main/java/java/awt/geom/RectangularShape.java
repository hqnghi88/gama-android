package java.awt.geom;

public abstract class RectangularShape implements java.awt.Shape {
    public abstract double getX();
    public abstract double getY();
    public abstract double getWidth();
    public abstract double getHeight();
    public boolean isEmpty() { return getWidth() <= 0 || getHeight() <= 0; }
    public double getMinX() { return getX(); }
    public double getMinY() { return getY(); }
    public double getCenterX() { return getX() + getWidth() / 2.0; }
    public double getCenterY() { return getY() + getHeight() / 2.0; }
    public double getMaxX() { return getX() + getWidth(); }
    public double getMaxY() { return getY() + getHeight(); }
    public java.awt.Rectangle getBounds() { return new java.awt.Rectangle((int) getX(), (int) getY(), (int) getWidth(), (int) getHeight()); }
    public java.awt.geom.Rectangle2D getBounds2D() { return new Rectangle2D.Double(getX(), getY(), getWidth(), getHeight()); }
    public boolean contains(double x, double y) { return false; }
    public boolean contains(double x, double y, double w, double h) { return false; }
    public boolean contains(java.awt.geom.Point2D p) { return contains(p.getX(), p.getY()); }
    public boolean contains(java.awt.geom.Rectangle2D r) { return contains(r.getX(), r.getY(), r.getWidth(), r.getHeight()); }
    public boolean intersects(double x, double y, double w, double h) { return false; }
    public boolean intersects(java.awt.geom.Rectangle2D r) { return intersects(r.getX(), r.getY(), r.getWidth(), r.getHeight()); }
    public java.awt.geom.PathIterator getPathIterator(java.awt.geom.AffineTransform at) { return null; }
    public java.awt.geom.PathIterator getPathIterator(java.awt.geom.AffineTransform at, double flatness) { return null; }
}
