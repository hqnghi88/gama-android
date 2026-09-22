package java.awt.geom;

/**
 * Real, stateful java.awt.geom.AffineTransform shim for Android.
 *
 * Tracks the standard 6-value 2D affine matrix (m00 m10 m01 m11 m02 m12, i.e.
 * x' = m00*x + m01*y + m02 ; y' = m10*x + m11*y + m12) so transforms composed
 * by the engine (translate/rotate/scale/quadrantRotate ...) are faithfully
 * applied by {@code CanvasGraphics2D.drawImage(Image, AffineTransform, ...)}.
 */
public class AffineTransform {
    private double m00, m01, m02;
    private double m10, m11, m12;

    public AffineTransform() {
        setToIdentity();
    }

    public AffineTransform(double m00, double m10, double m01, double m11, double m02, double m12) {
        this.m00 = m00; this.m10 = m10; this.m01 = m01; this.m11 = m11; this.m02 = m02; this.m12 = m12;
    }

    public AffineTransform(float m00, float m10, float m01, float m11, float m02, float m12) {
        this((double) m00, m10, m01, m11, m02, m12);
    }

    public AffineTransform(AffineTransform Tx) {
        if (Tx == null) { setToIdentity(); } else {
            this.m00 = Tx.m00; this.m10 = Tx.m10; this.m01 = Tx.m01; this.m11 = Tx.m11; this.m02 = Tx.m02; this.m12 = Tx.m12;
        }
    }

    public void setToIdentity() {
        m00 = 1; m01 = 0; m02 = 0;
        m10 = 0; m11 = 1; m12 = 0;
    }

    public void setToTranslation(double tx, double ty) {
        m00 = 1; m01 = 0; m02 = tx;
        m10 = 0; m11 = 1; m12 = ty;
    }

    public void setToRotation(double theta) {
        double c = Math.cos(theta), s = Math.sin(theta);
        m00 = c; m01 = -s; m02 = 0;
        m10 = s; m11 = c;  m12 = 0;
    }

    public void setToRotation(double theta, double x, double y) {
        double c = Math.cos(theta), s = Math.sin(theta);
        double nx = x * (1 - c) + y * s;
        double ny = y * (1 - c) - x * s;
        m00 = c; m01 = -s; m02 = nx;
        m10 = s; m11 = c;  m12 = ny;
    }

    public void setToScale(double sx, double sy) {
        m00 = sx; m01 = 0; m02 = 0;
        m10 = 0; m11 = sy; m12 = 0;
    }

    public void setToShear(double shx, double shy) {
        m00 = 1; m01 = shx; m02 = 0;
        m10 = shy; m11 = 1; m12 = 0;
    }

    public void setTransform(AffineTransform Tx) {
        if (Tx == null) return;
        this.m00 = Tx.m00; this.m10 = Tx.m10; this.m01 = Tx.m01; this.m11 = Tx.m11; this.m02 = Tx.m02; this.m12 = Tx.m12;
    }

    public void concatenate(AffineTransform Tx) {
        double n00 = Tx.m00 * m00 + Tx.m01 * m10;
        double n01 = Tx.m00 * m01 + Tx.m01 * m11;
        double n02 = Tx.m00 * m02 + Tx.m01 * m12 + Tx.m02;
        double n10 = Tx.m10 * m00 + Tx.m11 * m10;
        double n11 = Tx.m10 * m01 + Tx.m11 * m11;
        double n12 = Tx.m10 * m02 + Tx.m11 * m12 + Tx.m12;
        m00 = n00; m01 = n01; m02 = n02;
        m10 = n10; m11 = n11; m12 = n12;
    }

    public void preConcatenate(AffineTransform Tx) {
        double n00 = m00 * Tx.m00 + m01 * Tx.m10;
        double n01 = m00 * Tx.m01 + m01 * Tx.m11;
        double n02 = m00 * Tx.m02 + m01 * Tx.m12 + m02;
        double n10 = m10 * Tx.m00 + m11 * Tx.m10;
        double n11 = m10 * Tx.m01 + m11 * Tx.m11;
        double n12 = m10 * Tx.m02 + m11 * Tx.m12 + m12;
        m00 = n00; m01 = n01; m02 = n02;
        m10 = n10; m11 = n11; m12 = n12;
    }

    public void translate(double tx, double ty) {
        m02 += m00 * tx + m01 * ty;
        m12 += m10 * tx + m11 * ty;
    }

    public void rotate(double theta) {
        double c = Math.cos(theta), s = Math.sin(theta);
        double n00 = m00 * c + m01 * s;
        double n01 = -m00 * s + m01 * c;
        double n10 = m10 * c + m11 * s;
        double n11 = -m10 * s + m11 * c;
        double n02 = m02;
        double n12 = m12;
        m00 = n00; m01 = n01; m02 = n02;
        m10 = n10; m11 = n11; m12 = n12;
    }

    public void rotate(double theta, double x, double y) {
        translate(x, y);
        rotate(theta);
        translate(-x, -y);
    }

    public void quadrantRotate(int numquadrants) {
        quadrantRotate(numquadrants, 0, 0);
    }

    public void quadrantRotate(int numquadrants, double anchorx, double anchory) {
        double s = 0, c = 1;
        switch (numquadrants & 3) {
            case 0: c = 1; s = 0; break;
            case 1: c = 0; s = 1; break;
            case 2: c = -1; s = 0; break;
            default: c = 0; s = -1; break;
        }
        double n00 = m00 * c + m01 * s;
        double n01 = -m00 * s + m01 * c;
        double n10 = m10 * c + m11 * s;
        double n11 = -m10 * s + m11 * c;
        double n02 = m02 + (anchory * c - anchorx * s - anchory) * m01 + (anchorx - anchory * s - anchorx * c) * m00;
        double n12 = m12 + (anchory * c - anchorx * s - anchory) * m11 + (anchorx - anchory * s - anchorx * c) * m10;
        m00 = n00; m01 = n01; m02 = n02;
        m10 = n10; m11 = n11; m12 = n12;
    }

    public void scale(double sx, double sy) {
        m00 *= sx; m01 *= sy;
        m10 *= sx; m11 *= sy;
    }

    public void shear(double shx, double shy) {
        double n00 = m00 + m01 * shy;
        double n01 = m00 * shx + m01;
        double n10 = m10 + m11 * shy;
        double n11 = m10 * shx + m11;
        m00 = n00; m01 = n01;
        m10 = n10; m11 = n11;
    }

    public void transform(double[] srcPts, int srcOff, double[] dstPts, int dstOff, int numPts) {
        if (srcPts == dstPts) { double[] tmp = new double[numPts * 2]; transform(srcPts, srcOff, tmp, 0, numPts); System.arraycopy(tmp, 0, dstPts, dstOff, numPts * 2); return; }
        for (int i = 0; i < numPts; i++) {
            double x = srcPts[srcOff + i * 2], y = srcPts[srcOff + i * 2 + 1];
            dstPts[dstOff + i * 2] = m00 * x + m01 * y + m02;
            dstPts[dstOff + i * 2 + 1] = m10 * x + m11 * y + m12;
        }
    }

    public void transform(float[] srcPts, int srcOff, double[] dstPts, int dstOff, int numPts) {
        for (int i = 0; i < numPts; i++) {
            double x = srcPts[srcOff + i * 2], y = srcPts[srcOff + i * 2 + 1];
            dstPts[dstOff + i * 2] = m00 * x + m01 * y + m02;
            dstPts[dstOff + i * 2 + 1] = m10 * x + m11 * y + m12;
        }
    }

    public void transform(double[] srcPts, int srcOff, float[] dstPts, int dstOff, int numPts) {
        for (int i = 0; i < numPts; i++) {
            double x = srcPts[srcOff + i * 2], y = srcPts[srcOff + i * 2 + 1];
            dstPts[dstOff + i * 2] = (float) (m00 * x + m01 * y + m02);
            dstPts[dstOff + i * 2 + 1] = (float) (m10 * x + m11 * y + m12);
        }
    }

    public void transform(float[] srcPts, int srcOff, float[] dstPts, int dstOff, int numPts) {
        for (int i = 0; i < numPts; i++) {
            float x = srcPts[srcOff + i * 2], y = srcPts[srcOff + i * 2 + 1];
            dstPts[dstOff + i * 2] = (float) (m00 * x + m01 * y + m02);
            dstPts[dstOff + i * 2 + 1] = (float) (m10 * x + m11 * y + m12);
        }
    }

    public Point2D transform(Point2D ptSrc, Point2D ptDst) {
        double x = m00 * ptSrc.getX() + m01 * ptSrc.getY() + m02;
        double y = m10 * ptSrc.getX() + m11 * ptSrc.getY() + m12;
        if (ptDst == null) ptDst = new Point2D.Double();
        ptDst.setLocation(x, y);
        return ptDst;
    }

    public void transform(Point2D[] srcPts, int srcOff, Point2D[] dstPts, int dstOff, int numPts) {
        for (int i = 0; i < numPts; i++) {
            Point2D s = srcPts[srcOff + i];
            double x = m00 * s.getX() + m01 * s.getY() + m02;
            double y = m10 * s.getX() + m11 * s.getY() + m12;
            dstPts[dstOff + i].setLocation(x, y);
        }
    }

    public Point2D inverseTransform(Point2D ptSrc, Point2D ptDst) {
        double det = getDeterminant();
        if (det == 0) return null;
        double inv00 = m11 / det, inv01 = -m01 / det, inv10 = -m10 / det, inv11 = m00 / det;
        double x = ptSrc.getX() - m02, y = ptSrc.getY() - m12;
        double ix = inv00 * x + inv01 * y;
        double iy = inv10 * x + inv11 * y;
        if (ptDst == null) ptDst = new Point2D.Double();
        ptDst.setLocation(ix, iy);
        return ptDst;
    }

    public AffineTransform createInverse() {
        double det = getDeterminant();
        if (det == 0) return null;
        double inv00 = m11 / det, inv01 = -m01 / det, inv10 = -m10 / det, inv11 = m00 / det;
        double x = -(inv00 * m02 + inv01 * m12);
        double y = -(inv10 * m02 + inv11 * m12);
        return new AffineTransform(inv00, inv10, inv01, inv11, x, y);
    }

    public double getDeterminant() {
        return m00 * m11 - m01 * m10;
    }

    public boolean isIdentity() {
        return m00 == 1 && m11 == 1 && m01 == 0 && m10 == 0 && m02 == 0 && m12 == 0;
    }

    public int getType() {
        if (isIdentity()) return TYPE_IDENTITY;
        double det = getDeterminant();
        int type = (m02 != 0 || m12 != 0) ? TYPE_TRANSLATION : TYPE_IDENTITY;
        if (det != 0) {
            if (m01 == 0 && m10 == 0) {
                if (m00 != m11) type |= TYPE_GENERAL_SCALE;
                else if (m00 > 0) {
                    if (m00 != 1) type |= TYPE_UNIFORM_SCALE;
                } else type |= TYPE_UNIFORM_SCALE | TYPE_FLIP;
            } else if (m00 == -m11 && m01 == m10 && m00 * m11 - m01 * m10 > 0) {
                type |= TYPE_QUADRANT_ROTATION;
            } else {
                type |= TYPE_GENERAL_ROTATION;
            }
        } else {
            type |= TYPE_GENERAL_TRANSFORM;
        }
        return type;
    }

    public double getScaleX() { return m00; }
    public double getScaleY() { return m11; }
    public double getShearX() { return m01; }
    public double getShearY() { return m10; }
    public double getTranslateX() { return m02; }
    public double getTranslateY() { return m12; }

    public void getMatrix(double[] flatmatrix) {
        if (flatmatrix == null || flatmatrix.length < 6) return;
        flatmatrix[0] = m00;
        flatmatrix[1] = m10;
        flatmatrix[2] = m01;
        flatmatrix[3] = m11;
        flatmatrix[4] = m02;
        flatmatrix[5] = m12;
    }

    public Object clone() {
        return new AffineTransform(this);
    }

    public java.awt.Shape createTransformedShape(java.awt.Shape pSrc) {
        if (pSrc == null) return null;
        java.awt.geom.PathIterator pi = pSrc.getPathIterator(this);
        if (pi == null) return pSrc;
        java.awt.geom.GeneralPath path = new java.awt.geom.GeneralPath(pi.getWindingRule());
        double[] coords = new double[6];
        while (!pi.isDone()) {
            int type = pi.currentSegment(coords);
            switch (type) {
                case java.awt.geom.PathIterator.SEG_MOVETO: path.moveTo((float) coords[0], (float) coords[1]); break;
                case java.awt.geom.PathIterator.SEG_LINETO: path.lineTo((float) coords[0], (float) coords[1]); break;
                case java.awt.geom.PathIterator.SEG_QUADTO: path.quadTo((float) coords[0], (float) coords[1], (float) coords[2], (float) coords[3]); break;
                case java.awt.geom.PathIterator.SEG_CUBICTO: path.curveTo((float) coords[0], (float) coords[1], (float) coords[2], (float) coords[3], (float) coords[4], (float) coords[5]); break;
                case java.awt.geom.PathIterator.SEG_CLOSE: path.closePath(); break;
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