package java.awt.geom;

public abstract class RoundRectangle2D extends RectangularShape {

    protected RoundRectangle2D() {}

    public abstract double getArcWidth();

    public abstract double getArcHeight();

    public abstract void setRoundRect(double x, double y, double w, double h, double arcWidth, double arcHeight);

    public void setRoundRect(RoundRectangle2D rr) {
        setRoundRect(rr.getX(), rr.getY(), rr.getWidth(), rr.getHeight(), rr.getArcWidth(), rr.getArcHeight());
    }

    public static class Float extends RoundRectangle2D {
        public float x, y, width, height, arcWidth, arcHeight;

        public Float() { this(0, 0, 0, 0, 0, 0); }

        public Float(float x, float y, float w, float h, float arcw, float arch) {
            this.x = x; this.y = y; this.width = w; this.height = h;
            this.arcWidth = arcw; this.arcHeight = arch;
        }

        public Float(double x, double y, double w, double h, double arcw, double arch) {
            this((float) x, (float) y, (float) w, (float) h, (float) arcw, (float) arch);
        }

        @Override public double getX() { return x; }
        @Override public double getY() { return y; }
        @Override public double getWidth() { return width; }
        @Override public double getHeight() { return height; }
        @Override public double getArcWidth() { return arcWidth; }
        @Override public double getArcHeight() { return arcHeight; }

        public void setRoundRect(float x, float y, float w, float h, float arcw, float arch) {
            this.x = x; this.y = y; this.width = w; this.height = h;
            this.arcWidth = arcw; this.arcHeight = arch;
        }

        @Override public void setRoundRect(double x, double y, double w, double h, double arcw, double arch) {
            this.x = (float) x; this.y = (float) y; this.width = (float) w; this.height = (float) h;
            this.arcWidth = (float) arcw; this.arcHeight = (float) arch;
        }
    }

    public static class Double extends RoundRectangle2D {
        public double x, y, width, height, arcWidth, arcHeight;

        public Double() { this(0, 0, 0, 0, 0, 0); }

        public Double(double x, double y, double w, double h, double arcw, double arch) {
            this.x = x; this.y = y; this.width = w; this.height = h;
            this.arcWidth = arcw; this.arcHeight = arch;
        }

        @Override public double getX() { return x; }
        @Override public double getY() { return y; }
        @Override public double getWidth() { return width; }
        @Override public double getHeight() { return height; }
        @Override public double getArcWidth() { return arcWidth; }
        @Override public double getArcHeight() { return arcHeight; }

        @Override public void setRoundRect(double x, double y, double w, double h, double arcw, double arch) {
            this.x = x; this.y = y; this.width = w; this.height = h;
            this.arcWidth = arcw; this.arcHeight = arch;
        }
    }
}