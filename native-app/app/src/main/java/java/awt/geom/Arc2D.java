package java.awt.geom;

import java.awt.Shape;
import java.awt.Rectangle;

public abstract class Arc2D implements Shape {

    public static final int OPEN = 0;
    public static final int CHORD = 1;
    public static final int PIE = 2;

    private int arcType;

    protected Arc2D() { this(OPEN); }

    protected Arc2D(int type) { this.arcType = type; }

    public int getArcType() { return arcType; }

    public void setArcType(int type) { this.arcType = type; }

    public abstract double getX();
    public abstract double getY();
    public abstract double getWidth();
    public abstract double getHeight();
    public abstract double getAngleStart();
    public abstract double getAngleExtent();
    public abstract void setArc(double x, double y, double w, double h, double angSt, double angExt, int type);

    public void setArc(Arc2D a) {
        setArc(a.getX(), a.getY(), a.getWidth(), a.getHeight(), a.getAngleStart(), a.getAngleExtent(), a.getArcType());
    }

    public double getCenterX() { return getX() + getWidth() / 2.0; }
    public double getCenterY() { return getY() + getHeight() / 2.0; }

    public Point2D getStartPoint() {
        double angle = Math.toRadians(-getAngleStart());
        double cx = getCenterX(), cy = getCenterY();
        double rx = getWidth() / 2.0, ry = getHeight() / 2.0;
        return new Point2D.Double(cx + Math.cos(angle) * rx, cy + Math.sin(angle) * ry);
    }

    public Point2D getEndPoint() {
        double angle = Math.toRadians(-getAngleStart() - getAngleExtent());
        double cx = getCenterX(), cy = getCenterY();
        double rx = getWidth() / 2.0, ry = getHeight() / 2.0;
        return new Point2D.Double(cx + Math.cos(angle) * rx, cy + Math.sin(angle) * ry);
    }

    public Rectangle2D getBounds2D() {
        return new Rectangle2D.Double(getX(), getY(), getWidth(), getHeight());
    }

    public Rectangle getBounds() {
        Rectangle2D b = getBounds2D();
        return new Rectangle((int) b.getX(), (int) b.getY(), (int) b.getWidth(), (int) b.getHeight());
    }

    public java.awt.geom.PathIterator getPathIterator(java.awt.geom.AffineTransform at) {
        return new ArcIterator(this, at);
    }

    public java.awt.geom.PathIterator getPathIterator(java.awt.geom.AffineTransform at, double flatness) {
        return new ArcIterator(this, at);
    }

    public boolean contains(double x, double y) { return false; }
    public boolean contains(double x, double y, double w, double h) { return false; }
    public boolean contains(java.awt.geom.Point2D p) { return false; }
    public boolean contains(Rectangle2D r) { return false; }
    public boolean intersects(double x, double y, double w, double h) { return false; }
    public boolean intersects(Rectangle2D r) { return false; }


    public static class Double extends Arc2D {
        protected double x, y, width, height, start, extent;

        public Double() { super(OPEN); }

        public Double(int type) { super(type); }

        public Double(double x, double y, double w, double h, double angSt, double angExt, int type) {
            super(type);
            this.x = x; this.y = y; this.width = w; this.height = h;
            this.start = angSt; this.extent = angExt;
        }

        public Double(Rectangle2D ellipse, double angSt, double angExt, int type) {
            super(type);
            this.x = ellipse.getX(); this.y = ellipse.getY();
            this.width = ellipse.getWidth(); this.height = ellipse.getHeight();
            this.start = angSt; this.extent = angExt;
        }

        public double getX() { return x; }
        public double getY() { return y; }
        public double getWidth() { return width; }
        public double getHeight() { return height; }
        public double getAngleStart() { return start; }
        public double getAngleExtent() { return extent; }

        public void setArc(double x, double y, double w, double h, double angSt, double angExt, int type) {
            this.x = x; this.y = y; this.width = w; this.height = h;
            this.start = angSt; this.extent = angExt;
            setArcType(type);
        }
    }
}
