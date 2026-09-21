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

    public static class Float extends Ellipse2D {
        public float x, y, width, height;

        public Float() { this(0, 0, 0, 0); }

        public Float(float x, float y, float w, float h) {
            this.x = x; this.y = y; this.width = w; this.height = h;
        }

        public Float(double x, double y, double w, double h) {
            this((float) x, (float) y, (float) w, (float) h);
        }

        @Override public double getX() { return x; }
        @Override public double getY() { return y; }
        @Override public double getWidth() { return width; }
        @Override public double getHeight() { return height; }

        @Override public void setFrame(double x, double y, double w, double h) {
            this.x = (float) x; this.y = (float) y; this.width = (float) w; this.height = (float) h;
        }

        public void setFrame(float x, float y, float w, float h) {
            this.x = x; this.y = y; this.width = w; this.height = h;
        }

        @Override public boolean contains(double px, double py) {
            if (width <= 0 || height <= 0) return false;
            double nx = (px - x) / width * 2 - 1;
            double ny = (py - y) / height * 2 - 1;
            return nx * nx + ny * ny <= 1.0;
        }
    }
}
