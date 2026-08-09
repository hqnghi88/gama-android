package java.awt.geom;

import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

public abstract class CubicCurve2D implements Shape, Cloneable {

    protected CubicCurve2D() {}

    public abstract double getX1();
    public abstract double getY1();
    public abstract Point2D getP1();
    public abstract double getCtrlX1();
    public abstract double getCtrlY1();
    public abstract Point2D getCtrlP1();
    public abstract double getCtrlX2();
    public abstract double getCtrlY2();
    public abstract Point2D getCtrlP2();
    public abstract double getX2();
    public abstract double getY2();
    public abstract Point2D getP2();

    public void setCurve(double x1, double y1, double cx1, double cy1,
                         double cx2, double cy2, double x2, double y2) {
        throw new UnsupportedOperationException();
    }

    public void setCurve(double[] coords, int offset) {
        setCurve(coords[offset + 0], coords[offset + 1],
                 coords[offset + 2], coords[offset + 3],
                 coords[offset + 4], coords[offset + 5],
                 coords[offset + 6], coords[offset + 7]);
    }

    public void setCurve(Point2D p1, Point2D cp1, Point2D cp2, Point2D p2) {
        setCurve(p1.getX(), p1.getY(), cp1.getX(), cp1.getY(),
                 cp2.getX(), cp2.getY(), p2.getX(), p2.getY());
    }

    public void setCurve(Point2D[] pts, int offset) {
        setCurve(pts[offset].getX(), pts[offset].getY(),
                 pts[offset + 1].getX(), pts[offset + 1].getY(),
                 pts[offset + 2].getX(), pts[offset + 2].getY(),
                 pts[offset + 3].getX(), pts[offset + 3].getY());
    }

    public void setCurve(CubicCurve2D c) {
        setCurve(c.getX1(), c.getY1(), c.getCtrlX1(), c.getCtrlY1(),
                 c.getCtrlX2(), c.getCtrlY2(), c.getX2(), c.getY2());
    }

    public double getFlatnessSq() {
        return getFlatnessSq(getX1(), getY1(), getCtrlX1(), getCtrlY1(),
                             getCtrlX2(), getCtrlY2(), getX2(), getY2());
    }

    public double getFlatness() {
        return Math.sqrt(getFlatnessSq());
    }

    public static double getFlatnessSq(double[] coords, int offset) {
        return getFlatnessSq(coords[offset + 0], coords[offset + 1],
                             coords[offset + 2], coords[offset + 3],
                             coords[offset + 4], coords[offset + 5],
                             coords[offset + 6], coords[offset + 7]);
    }

    public static double getFlatness(double[] coords, int offset) {
        return Math.sqrt(getFlatnessSq(coords, offset));
    }

    public static double getFlatnessSq(double x1, double y1,
                                       double cx1, double cy1,
                                       double cx2, double cy2,
                                       double x2, double y2) {
        double d1 = ptSegDistSq(x1, y1, x2, y2, cx1, cy1);
        double d2 = ptSegDistSq(x1, y1, x2, y2, cx2, cy2);
        return Math.max(d1, d2);
    }

    public static double getFlatness(double x1, double y1,
                                     double cx1, double cy1,
                                     double cx2, double cy2,
                                     double x2, double y2) {
        return Math.sqrt(getFlatnessSq(x1, y1, cx1, cy1, cx2, cy2, x2, y2));
    }

    public void subdivide(CubicCurve2D left, CubicCurve2D right) {
        subdivide(this, left, right);
    }

    public static void subdivide(CubicCurve2D src,
                                 CubicCurve2D left,
                                 CubicCurve2D right) {
        subdivide(src.getX1(), src.getY1(), src.getCtrlX1(), src.getCtrlY1(),
                  src.getCtrlX2(), src.getCtrlY2(), src.getX2(), src.getY2(),
                  left, right);
    }

    public static void subdivide(double[] src, int srcoff,
                                 double[] left, int leftoff,
                                 double[] right, int rightoff) {
        double x1 = src[srcoff + 0], y1 = src[srcoff + 1];
        double cx1 = src[srcoff + 2], cy1 = src[srcoff + 3];
        double cx2 = src[srcoff + 4], cy2 = src[srcoff + 5];
        double x2 = src[srcoff + 6], y2 = src[srcoff + 7];
        double cx1m = (x1 + cx1) / 2.0, cy1m = (y1 + cy1) / 2.0;
        double cx2m = (cx2 + x2) / 2.0, cy2m = (cy2 + y2) / 2.0;
        double ax = (cx1m + cx2m) / 2.0, ay = (cy1m + cy2m) / 2.0;
        double midx = (cx1 + cx2) / 2.0, midy = (cy1 + cy2) / 2.0;
        cx1m = (x1 + cx1m) / 2.0; cy1m = (y1 + cy1m) / 2.0;
        cx2m = (x2 + cx2m) / 2.0; cy2m = (y2 + cy2m) / 2.0;
        if (left != null) {
            left[leftoff + 0] = x1; left[leftoff + 1] = y1;
            left[leftoff + 2] = cx1m; left[leftoff + 3] = cy1m;
            left[leftoff + 4] = ax; left[leftoff + 5] = ay;
            left[leftoff + 6] = midx; left[leftoff + 7] = midy;
        }
        if (right != null) {
            right[rightoff + 0] = midx; right[rightoff + 1] = midy;
            right[rightoff + 2] = (ax + cx2m) / 2.0; right[rightoff + 3] = (ay + cy2m) / 2.0;
            right[rightoff + 4] = cx2m; right[rightoff + 5] = cy2m;
            right[rightoff + 6] = x2; right[rightoff + 7] = y2;
        }
    }

    public static void subdivide(double x1, double y1,
                                 double cx1, double cy1,
                                 double cx2, double cy2,
                                 double x2, double y2,
                                 CubicCurve2D left,
                                 CubicCurve2D right) {
        double cx1m = (x1 + cx1) / 2.0, cy1m = (y1 + cy1) / 2.0;
        double cx2m = (cx2 + x2) / 2.0, cy2m = (cy2 + y2) / 2.0;
        double ax = (cx1m + cx2m) / 2.0, ay = (cy1m + cy2m) / 2.0;
        double midx = (cx1 + cx2) / 2.0, midy = (cy1 + cy2) / 2.0;
        cx1m = (x1 + cx1m) / 2.0; cy1m = (y1 + cy1m) / 2.0;
        cx2m = (x2 + cx2m) / 2.0; cy2m = (y2 + cy2m) / 2.0;
        if (left != null) {
            left.setCurve(x1, y1, cx1m, cy1m, ax, ay, midx, midy);
        }
        if (right != null) {
            right.setCurve(midx, midy, (ax + cx2m) / 2.0, (ay + cy2m) / 2.0,
                           cx2m, cy2m, x2, y2);
        }
    }

    public static int solveCubic(double[] eqn) {
        return solveCubic(eqn, eqn);
    }

    public static int solveCubic(double[] eqn, double[] res) {
        int num = 0;
        double[] derivCoefs = new double[4];
        derivCoefs[0] = eqn[0] * 3.0;
        derivCoefs[1] = eqn[1] * 2.0;
        derivCoefs[2] = eqn[2];
        int numD = solveCubic(derivCoefs, null);
        double[] intervals = new double[numD + 2];
        intervals[0] = -1.0;
        for (int i = 0; i < numD; i++) {
            intervals[i + 1] = derivCoefs[i + 3];
        }
        intervals[numD + 1] = 1.0;
        double[] eqn2 = new double[4];
        eqn2[0] = eqn[0];
        eqn2[1] = eqn[1];
        eqn2[2] = eqn[2];
        eqn2[3] = eqn[3];
        double lastY = eqn2[0] + eqn2[1] + eqn2[2] + eqn2[3];
        for (int i = 0; i <= numD; i++) {
            double mid = (intervals[i] + intervals[i + 1]) / 2.0;
            double y = ((eqn2[0] * mid + eqn2[1]) * mid + eqn2[2]) * mid + eqn2[3];
            if (Math.abs(y) < 1.0E-7) {
                if (res != null) {
                    res[num++] = mid;
                }
                lastY = 0;
            } else if (lastY * y < 0) {
                double lo = intervals[i];
                double hi = intervals[i + 1];
                for (int j = 0; j < 30; j++) {
                    double t = (lo + hi) / 2.0;
                    double ty = ((eqn2[0] * t + eqn2[1]) * t + eqn2[2]) * t + eqn2[3];
                    if (Math.abs(ty) < 1.0E-7) {
                        lo = t;
                        break;
                    }
                    if (ty * lastY < 0) {
                        hi = t;
                    } else {
                        lo = t;
                        lastY = ty;
                    }
                }
                if (res != null) {
                    res[num++] = (lo + hi) / 2.0;
                }
            }
            lastY = y;
        }
        return num;
    }

    public Rectangle2D getBounds2D() {
        double x1 = getX1(), y1 = getY1();
        double cx1 = getCtrlX1(), cy1 = getCtrlY1();
        double cx2 = getCtrlX2(), cy2 = getCtrlY2();
        double x2 = getX2(), y2 = getY2();
        double left = Math.min(x1, Math.min(x2, Math.min(cx1, cx2)));
        double top = Math.min(y1, Math.min(y2, Math.min(cy1, cy2)));
        double right = Math.max(x1, Math.max(x2, Math.max(cx1, cx2)));
        double bottom = Math.max(y1, Math.max(y2, Math.max(cy1, cy2)));
        return new Rectangle2D.Double(left, top, right - left, bottom - top);
    }

    public Rectangle getBounds() {
        return getBounds2D().getBounds();
    }

    public boolean contains(double x, double y) {
        return false;
    }

    public boolean contains(Point2D p) {
        return contains(p.getX(), p.getY());
    }

    public boolean intersects(double x, double y, double w, double h) {
        return getBounds2D().intersects(x, y, w, h);
    }

    public boolean intersects(Rectangle2D r) {
        return intersects(r.getX(), r.getY(), r.getWidth(), r.getHeight());
    }

    public boolean contains(double x, double y, double w, double h) {
        return false;
    }

    public boolean contains(Rectangle2D r) {
        return contains(r.getX(), r.getY(), r.getWidth(), r.getHeight());
    }

    public PathIterator getPathIterator(AffineTransform at) {
        return new CubicIterator(this, at);
    }

    public PathIterator getPathIterator(AffineTransform at, double flatness) {
        return new FlatteningPathIterator(getPathIterator(at), flatness);
    }

    private static double ptSegDistSq(double x1, double y1, double x2, double y2, double px, double py) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double lenSq = dx * dx + dy * dy;
        if (lenSq == 0) {
            double ddx = px - x1;
            double ddy = py - y1;
            return ddx * ddx + ddy * ddy;
        }
        double t = ((px - x1) * dx + (py - y1) * dy) / lenSq;
        t = Math.max(0, Math.min(1, t));
        double projX = x1 + t * dx;
        double projY = y1 + t * dy;
        double ddx = px - projX;
        double ddy = py - projY;
        return ddx * ddx + ddy * ddy;
    }

    public Object clone() {
        try {
            return super.clone();
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    public static class Float extends CubicCurve2D {
        public float x1, y1, ctrlx1, ctrly1, ctrlx2, ctrly2, x2, y2;

        public Float() {}

        public Float(float x1, float y1, float ctrlx1, float ctrly1,
                     float ctrlx2, float ctrly2, float x2, float y2) {
            setCurve(x1, y1, ctrlx1, ctrly1, ctrlx2, ctrly2, x2, y2);
        }

        public double getX1() { return (double) x1; }
        public double getY1() { return (double) y1; }
        public Point2D getP1() { return new Point2D.Float(x1, y1); }
        public double getCtrlX1() { return (double) ctrlx1; }
        public double getCtrlY1() { return (double) ctrly1; }
        public Point2D getCtrlP1() { return new Point2D.Float(ctrlx1, ctrly1); }
        public double getCtrlX2() { return (double) ctrlx2; }
        public double getCtrlY2() { return (double) ctrly2; }
        public Point2D getCtrlP2() { return new Point2D.Float(ctrlx2, ctrly2); }
        public double getX2() { return (double) x2; }
        public double getY2() { return (double) y2; }
        public Point2D getP2() { return new Point2D.Float(x2, y2); }

        public void setCurve(double x1, double y1, double cx1, double cy1,
                             double cx2, double cy2, double x2, double y2) {
            this.x1 = (float) x1;
            this.y1 = (float) y1;
            this.ctrlx1 = (float) cx1;
            this.ctrly1 = (float) cy1;
            this.ctrlx2 = (float) cx2;
            this.ctrly2 = (float) cy2;
            this.x2 = (float) x2;
            this.y2 = (float) y2;
        }
    }

    public static class Double extends CubicCurve2D {
        public double x1, y1, ctrlx1, ctrly1, ctrlx2, ctrly2, x2, y2;

        public Double() {}

        public Double(double x1, double y1, double ctrlx1, double ctrly1,
                      double ctrlx2, double ctrly2, double x2, double y2) {
            setCurve(x1, y1, ctrlx1, ctrly1, ctrlx2, ctrly2, x2, y2);
        }

        public double getX1() { return x1; }
        public double getY1() { return y1; }
        public Point2D getP1() { return new Point2D.Double(x1, y1); }
        public double getCtrlX1() { return ctrlx1; }
        public double getCtrlY1() { return ctrly1; }
        public Point2D getCtrlP1() { return new Point2D.Double(ctrlx1, ctrly1); }
        public double getCtrlX2() { return ctrlx2; }
        public double getCtrlY2() { return ctrly2; }
        public Point2D getCtrlP2() { return new Point2D.Double(ctrlx2, ctrly2); }
        public double getX2() { return x2; }
        public double getY2() { return y2; }
        public Point2D getP2() { return new Point2D.Double(x2, y2); }

        public void setCurve(double x1, double y1, double cx1, double cy1,
                             double cx2, double cy2, double x2, double y2) {
            this.x1 = x1;
            this.y1 = y1;
            this.ctrlx1 = cx1;
            this.ctrly1 = cy1;
            this.ctrlx2 = cx2;
            this.ctrly2 = cy2;
            this.x2 = x2;
            this.y2 = y2;
        }
    }
}
