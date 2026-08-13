package java.awt.geom;

import java.awt.Rectangle;
import java.awt.Shape;

/**
 * Functional Line2D (the awt-stubs.jar version was a broken stub whose
 * getPathIterator returned an always-empty EmptyPathIterator and getBounds2D
 * returned (0,0,0,0)). JFreeChart draws axes, gridlines and baselines as
 * Line2D, so a working iterator is required.
 */
public abstract class Line2D implements Shape, Cloneable {
    public abstract double getX1();
    public abstract double getY1();
    public abstract double getX2();
    public abstract double getY2();
    public abstract Point2D getP1();
    public abstract Point2D getP2();
    public abstract void setLine(double x1, double y1, double x2, double y2);

    protected Line2D() { }

    public double getP1x() { return getX1(); }
    public double getP1y() { return getY1(); }
    public double getP2x() { return getX2(); }
    public double getP2y() { return getY2(); }

    public Rectangle2D getBounds2D() {
        double x1 = getX1(), y1 = getY1(), x2 = getX2(), y2 = getY2();
        double rx = (x1 < x2) ? x1 : x2;
        double ry = (y1 < y2) ? y1 : y2;
        double rw = Math.abs(x2 - x1);
        double rh = Math.abs(y2 - y1);
        return new Rectangle2D.Double(rx, ry, rw, rh);
    }

    @Override
    public Rectangle getBounds() {
        Rectangle2D b = getBounds2D();
        return new Rectangle((int) Math.floor(b.getX()), (int) Math.floor(b.getY()),
                (int) Math.ceil(b.getWidth()), (int) Math.ceil(b.getHeight()));
    }

    @Override
    public boolean contains(double x, double y) { return false; }
    @Override public boolean contains(double x, double y, double w, double h) { return false; }
    @Override public boolean contains(Point2D p) { return false; }
    @Override public boolean contains(Rectangle2D r) { return false; }
    @Override public boolean intersects(double x, double y, double w, double h) { return false; }
    @Override public boolean intersects(Rectangle2D r) { return false; }

    @Override
    public PathIterator getPathIterator(AffineTransform at) {
        return new LineIterator(this, at);
    }

    @Override
    public PathIterator getPathIterator(AffineTransform at, double flatness) {
        return new LineIterator(this, at);
    }

    public boolean intersectsLine(double x1, double y1, double x2, double y2) { return false; }
    public boolean intersectsLine(java.awt.Shape p) { return false; }
    public double ptSegDist(double x1, double y1, double x2, double y2, double px, double py) { return 0; }
    public double ptSegDist(java.awt.geom.Point2D p1, java.awt.geom.Point2D p2, java.awt.geom.Point2D p) { return 0; }
    public double ptLineDist(double x1, double y1, double x2, double y2, double px, double py) { return 0; }
    public double ptLineDist(java.awt.geom.Point2D p1, java.awt.geom.Point2D p2, java.awt.geom.Point2D p) { return 0; }
    public double ptLineDistSq(double x1, double y1, double x2, double y2, double px, double py) { return 0; }
    public double ptSegDistSq(double x1, double y1, double x2, double y2, double px, double py) { return 0; }
    public int relativeCCW(double x1, double y1, double x2, double y2, double px, double py) { return 0; }

    private static final class LineIterator implements PathIterator {
        private final Line2D line;
        private final AffineTransform at;
        private int index = 0;

        LineIterator(Line2D l, AffineTransform at) {
            this.line = l;
            this.at = at;
        }

        public int getWindingRule() { return PathIterator.WIND_NON_ZERO; }

        public boolean isDone() { return index >= 2; }

        public void next() { index++; }

        public int currentSegment(float[] c) {
            if (index == 0) { c[0] = (float) line.getX1(); c[1] = (float) line.getY1(); }
            else            { c[0] = (float) line.getX2(); c[1] = (float) line.getY2(); }
            if (at != null) at.transform(c, 0, c, 0, 1);
            return index == 0 ? PathIterator.SEG_MOVETO : PathIterator.SEG_LINETO;
        }

        public int currentSegment(double[] c) {
            if (index == 0) { c[0] = line.getX1(); c[1] = line.getY1(); }
            else            { c[0] = line.getX2(); c[1] = line.getY2(); }
            if (at != null) at.transform(c, 0, c, 0, 1);
            return index == 0 ? PathIterator.SEG_MOVETO : PathIterator.SEG_LINETO;
        }
    }

    public static class Double extends Line2D {
        public double x1, y1, x2, y2;

        public Double() { super(); }

        public Double(double x1, double y1, double x2, double y2) {
            super();
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        }

        public Double(Point2D p1, Point2D p2) {
            super();
            this.x1 = p1.getX(); this.y1 = p1.getY(); this.x2 = p2.getX(); this.y2 = p2.getY();
        }

        @Override public double getX1() { return x1; }
        @Override public double getY1() { return y1; }
        @Override public double getX2() { return x2; }
        @Override public double getY2() { return y2; }
        @Override public Point2D getP1() { return new Point2D.Double(x1, y1); }
        @Override public Point2D getP2() { return new Point2D.Double(x2, y2); }
        @Override public void setLine(double x1, double y1, double x2, double y2) {
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        }
    }

    public static class Float extends Line2D {
        public float x1, y1, x2, y2;

        public Float() { super(); }

        public Float(float x1, float y1, float x2, float y2) {
            super();
            this.x1 = x1; this.y1 = y1; this.x2 = x2; this.y2 = y2;
        }

        @Override public double getX1() { return x1; }
        @Override public double getY1() { return y1; }
        @Override public double getX2() { return x2; }
        @Override public double getY2() { return y2; }
        @Override public Point2D getP1() { return new Point2D.Float(x1, y1); }
        @Override public Point2D getP2() { return new Point2D.Float(x2, y2); }
        @Override public void setLine(double x1, double y1, double x2, double y2) {
            this.x1 = (float) x1; this.y1 = (float) y1; this.x2 = (float) x2; this.y2 = (float) y2;
        }
    }
}
