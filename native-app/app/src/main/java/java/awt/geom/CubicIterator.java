package java.awt.geom;

import java.awt.geom.PathIterator;

class CubicIterator implements PathIterator {
    private CubicCurve2D cubic;
    private AffineTransform affine;
    private int index;

    CubicIterator(CubicCurve2D c, AffineTransform at) {
        this.cubic = c;
        this.affine = at;
        this.index = 0;
    }

    public int getWindingRule() {
        return WIND_NON_ZERO;
    }

    public boolean isDone() {
        return index > 1;
    }

    public void next() {
        index++;
    }

    public int currentSegment(float[] coords) {
        int type;
        if (index == 0) {
            type = SEG_MOVETO;
            coords[0] = (float) cubic.getX1();
            coords[1] = (float) cubic.getY1();
        } else {
            type = SEG_CUBICTO;
            coords[0] = (float) cubic.getCtrlX1();
            coords[1] = (float) cubic.getCtrlY1();
            coords[2] = (float) cubic.getCtrlX2();
            coords[3] = (float) cubic.getCtrlY2();
            coords[4] = (float) cubic.getX2();
            coords[5] = (float) cubic.getY2();
        }
        if (affine != null) {
            affine.transform(coords, 0, coords, 0, (type == SEG_MOVETO) ? 1 : 3);
        }
        return type;
    }

    public int currentSegment(double[] coords) {
        int type;
        if (index == 0) {
            type = SEG_MOVETO;
            coords[0] = cubic.getX1();
            coords[1] = cubic.getY1();
        } else {
            type = SEG_CUBICTO;
            coords[0] = cubic.getCtrlX1();
            coords[1] = cubic.getCtrlY1();
            coords[2] = cubic.getCtrlX2();
            coords[3] = cubic.getCtrlY2();
            coords[4] = cubic.getX2();
            coords[5] = cubic.getY2();
        }
        if (affine != null) {
            affine.transform(coords, 0, coords, 0, (type == SEG_MOVETO) ? 1 : 3);
        }
        return type;
    }
}
