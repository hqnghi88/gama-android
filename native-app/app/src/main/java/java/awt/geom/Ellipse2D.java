package java.awt.geom;

public class Ellipse2D extends RectangularShape {
    protected double x, y, width, height;

    public Ellipse2D() {}

    public Ellipse2D(double x, double y, double w, double h) {
        this.x = x; this.y = y; this.width = w; this.height = h;
    }

    public double getX() { return x; }
    public double getY() { return y; }
    public double getWidth() { return width; }
    public double getHeight() { return height; }

    public boolean contains(double px, double py) {
        if (width <= 0 || height <= 0) return false;
        double nx = (px - x) / width * 2 - 1;
        double ny = (py - y) / height * 2 - 1;
        return nx * nx + ny * ny <= 1.0;
    }

    public void setFrame(double x, double y, double w, double h) {
        this.x = x; this.y = y; this.width = w; this.height = h;
    }

    public static class Double extends Ellipse2D {
        public Double() { super(); }
        public Double(double x, double y, double w, double h) { super(x, y, w, h); }
    }
}
