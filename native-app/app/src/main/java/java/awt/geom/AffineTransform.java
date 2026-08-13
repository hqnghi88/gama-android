package java.awt.geom;

public class AffineTransform {
    public AffineTransform() {}
    public AffineTransform(double m00, double m10, double m01, double m11, double m02, double m12) {}
    public AffineTransform(float m00, float m10, float m01, float m11, float m02, float m12) {}
    public AffineTransform(AffineTransform Tx) {}
    public void setToIdentity() {}
    public void setToTranslation(double tx, double ty) {}
    public void setTransform(AffineTransform Tx) {}
    public void setToRotation(double theta) {}
    public void setToRotation(double theta, double x, double y) {}
    public void setToScale(double sx, double sy) {}
    public void setToShear(double shx, double shy) {}
    public void concatenate(AffineTransform Tx) {}
    public void preConcatenate(AffineTransform Tx) {}
    public void transform(double[] srcPts, int srcOff, double[] dstPts, int dstOff, int numPts) {}
    public void transform(float[] srcPts, int srcOff, double[] dstPts, int dstOff, int numPts) {}
    public void transform(double[] srcPts, int srcOff, float[] dstPts, int dstOff, int numPts) {}
    public void transform(float[] srcPts, int srcOff, float[] dstPts, int dstOff, int numPts) {}
    public Point2D transform(Point2D ptSrc, Point2D ptDst) { return ptDst; }
    public void transform(java.awt.geom.Point2D[] srcPts, int srcOff, java.awt.geom.Point2D[] dstPts, int dstOff, int numPts) {}
    public Point2D inverseTransform(Point2D ptSrc, Point2D ptDst) { return ptDst; }
    public AffineTransform createInverse() { return new AffineTransform(); }
    public double getDeterminant() { return 1; }
    public boolean isIdentity() { return true; }
    public int getType() { return 0; }
    public double getScaleX() { return 1; }
    public double getScaleY() { return 1; }
    public double getShearX() { return 0; }
    public double getShearY() { return 0; }
    public double getTranslateX() { return 0; }
    public double getTranslateY() { return 0; }
    public void scale(double sx, double sy) {}
    public void rotate(double theta) {}
    public void rotate(double theta, double x, double y) {}
    public void translate(double tx, double ty) {}
    public void shear(double shx, double shy) {}
    public Object clone() { return new AffineTransform(); }

    public java.awt.Shape createTransformedShape(java.awt.Shape pSrc) {
        if (pSrc == null) return null;
        java.awt.geom.PathIterator pi = pSrc.getPathIterator(this);
        if (pi == null) {
            return pSrc;
        }
        java.awt.geom.GeneralPath path = new java.awt.geom.GeneralPath(pi.getWindingRule());
        double[] coords = new double[6];
        while (!pi.isDone()) {
            int type = pi.currentSegment(coords);
            switch (type) {
                case java.awt.geom.PathIterator.SEG_MOVETO:
                    path.moveTo((float) coords[0], (float) coords[1]);
                    break;
                case java.awt.geom.PathIterator.SEG_LINETO:
                    path.lineTo((float) coords[0], (float) coords[1]);
                    break;
                case java.awt.geom.PathIterator.SEG_QUADTO:
                    path.quadTo((float) coords[0], (float) coords[1], (float) coords[2], (float) coords[3]);
                    break;
                case java.awt.geom.PathIterator.SEG_CUBICTO:
                    path.curveTo((float) coords[0], (float) coords[1], (float) coords[2], (float) coords[3], (float) coords[4], (float) coords[5]);
                    break;
                case java.awt.geom.PathIterator.SEG_CLOSE:
                    path.closePath();
                    break;
            }
            pi.next();
        }
        return path;
    }

    public static AffineTransform getTranslateInstance(double tx, double ty) {
        AffineTransform t = new AffineTransform();
        t.setToTranslation(tx, ty);
        return t;
    }

    public static AffineTransform getRotateInstance(double theta) {
        AffineTransform t = new AffineTransform();
        t.setToRotation(theta);
        return t;
    }

    public static AffineTransform getRotateInstance(double theta, double x, double y) {
        AffineTransform t = new AffineTransform();
        t.setToRotation(theta, x, y);
        return t;
    }

    public static AffineTransform getScaleInstance(double sx, double sy) {
        AffineTransform t = new AffineTransform();
        t.setToScale(sx, sy);
        return t;
    }

    public static AffineTransform getShearInstance(double shx, double shy) {
        AffineTransform t = new AffineTransform();
        t.setToShear(shx, shy);
        return t;
    }

    public static final int TYPE_IDENTITY = 0;
    public static final int TYPE_TRANSLATION = 1;
    public static final int TYPE_UNIFORM_SCALE = 2;
    public static final int TYPE_GENERAL_SCALE = 4;
    public static final int TYPE_FLIP = 64;
    public static final int TYPE_QUADRANT_ROTATION = 8;
    public static final int TYPE_GENERAL_ROTATION = 16;
    public static final int TYPE_GENERAL_TRANSFORM = 32;
    public static final int TYPE_MASK_SCALE = 6;
    public static final int TYPE_MASK_ROTATION = 24;
}
