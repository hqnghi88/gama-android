package java.awt.geom;

public abstract class Rectangle2D extends RectangularShape implements Cloneable {
    public abstract double getX();
    public abstract double getY();
    public abstract double getWidth();
    public abstract double getHeight();
    public abstract void setRect(double x, double y, double w, double h);
    public boolean contains(double x, double y) { return x >= getX() && y >= getY() && x < getX() + getWidth() && y < getY() + getHeight(); }
    public boolean contains(double x, double y, double w, double h) { return false; }
    public boolean intersects(double x, double y, double w, double h) { return false; }
    public java.awt.Shape createIntersection(Rectangle2D r) { return this; }
    public java.awt.Shape createUnion(Rectangle2D r) { return this; }
    public void add(double x, double y) {
        double minX = Math.min(getX(), x);
        double minY = Math.min(getY(), y);
        double maxX = Math.max(getX() + getWidth(), x);
        double maxY = Math.max(getY() + getHeight(), y);
        setRect(minX, minY, maxX - minX, maxY - minY);
    }
    public void add(Rectangle2D r) {
        if (r == null) return;
        add(r.getX(), r.getY());
        add(r.getX() + r.getWidth(), r.getY() + r.getHeight());
    }

    @Override
    public Object clone() {
        try { return super.clone(); } catch (CloneNotSupportedException e) { throw new RuntimeException(e); }
    }

    public void setRect(Rectangle2D r) {
        setRect(r.getX(), r.getY(), r.getWidth(), r.getHeight());
    }

    public static class Double extends Rectangle2D {
        protected double x, y, width, height;
        public Double() { this(0, 0, 0, 0); }
        public Double(double x, double y, double w, double h) { this.x = x; this.y = y; this.width = w; this.height = h; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getWidth() { return width; }
        public double getHeight() { return height; }
        public void setRect(double x, double y, double w, double h) { this.x = x; this.y = y; this.width = w; this.height = h; }
    }

    public static class Float extends Rectangle2D {
        protected float x, y, width, height;
        public Float() { this(0, 0, 0, 0); }
        public Float(float x, float y, float w, float h) { this.x = x; this.y = y; this.width = w; this.height = h; }
        public double getX() { return x; }
        public double getY() { return y; }
        public double getWidth() { return width; }
        public double getHeight() { return height; }
        public void setRect(double x, double y, double w, double h) { this.x = (float) x; this.y = (float) y; this.width = (float) w; this.height = (float) h; }
    }
}
