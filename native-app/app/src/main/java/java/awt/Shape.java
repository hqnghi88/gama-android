package java.awt;

public interface Shape {
    java.awt.Rectangle getBounds();
    java.awt.geom.Rectangle2D getBounds2D();
    boolean contains(double x, double y);
    boolean contains(double x, double y, double w, double h);
    boolean contains(java.awt.geom.Point2D p);
    boolean contains(java.awt.geom.Rectangle2D r);
    boolean intersects(double x, double y, double w, double h);
    boolean intersects(java.awt.geom.Rectangle2D r);
    java.awt.geom.PathIterator getPathIterator(java.awt.geom.AffineTransform at);
    java.awt.geom.PathIterator getPathIterator(java.awt.geom.AffineTransform at, double flatness);
}
