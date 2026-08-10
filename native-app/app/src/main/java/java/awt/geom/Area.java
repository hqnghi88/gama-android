package java.awt.geom;

public class Area implements java.awt.Shape, Cloneable {

    private java.awt.Shape shape;

    public Area() {
        this.shape = new Rectangle2D.Double();
    }

    public Area(java.awt.Shape s) {
        this.shape = (s == null) ? new Rectangle2D.Double() : s;
    }

    public void add(Area area) {
        if (area == null) return;
        Rectangle2D b = union(getBounds2D(), area.getBounds2D());
        shape = b;
    }

    public void intersect(Area area) {
        if (area == null) return;
        Rectangle2D b = intersection(getBounds2D(), area.getBounds2D());
        shape = b;
    }

    public void subtract(Area area) {
        // Approximate: leave the current shape unchanged.
    }

    public void exclusiveOr(Area area) {
        if (area == null) return;
        shape = union(getBounds2D(), area.getBounds2D());
    }

    public boolean isRectangular() {
        return true;
    }

    public boolean isEmpty() {
        Rectangle2D b = getBounds2D();
        return b == null || b.getWidth() <= 0 || b.getHeight() <= 0;
    }

    private static Rectangle2D union(Rectangle2D a, Rectangle2D b) {
        double x = Math.min(a.getX(), b.getX());
        double y = Math.min(a.getY(), b.getY());
        double w = Math.max(a.getX() + a.getWidth(), b.getX() + b.getWidth()) - x;
        double h = Math.max(a.getY() + a.getHeight(), b.getY() + b.getHeight()) - y;
        return new Rectangle2D.Double(x, y, w, h);
    }

    private static Rectangle2D intersection(Rectangle2D a, Rectangle2D b) {
        double x = Math.max(a.getX(), b.getX());
        double y = Math.max(a.getY(), b.getY());
        double w = Math.min(a.getX() + a.getWidth(), b.getX() + b.getWidth()) - x;
        double h = Math.min(a.getY() + a.getHeight(), b.getY() + b.getHeight()) - y;
        if (w < 0) w = 0;
        if (h < 0) h = 0;
        return new Rectangle2D.Double(x, y, w, h);
    }

    public java.awt.Rectangle getBounds() {
        return shape.getBounds();
    }

    public Rectangle2D getBounds2D() {
        return shape.getBounds2D();
    }

    public boolean contains(double x, double y) { return shape.contains(x, y); }
    public boolean contains(double x, double y, double w, double h) { return shape.contains(x, y, w, h); }
    public boolean contains(Point2D p) { return shape.contains(p); }
    public boolean contains(Rectangle2D r) { return shape.contains(r); }
    public boolean intersects(double x, double y, double w, double h) { return shape.intersects(x, y, w, h); }
    public boolean intersects(Rectangle2D r) { return shape.intersects(r); }

    public PathIterator getPathIterator(AffineTransform at) {
        return shape.getPathIterator(at);
    }

    public PathIterator getPathIterator(AffineTransform at, double flatness) {
        return shape.getPathIterator(at, flatness);
    }

    @Override
    public Area clone() {
        try {
            return (Area) super.clone();
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }
}
