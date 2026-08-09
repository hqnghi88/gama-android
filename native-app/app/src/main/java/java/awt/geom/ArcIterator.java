package java.awt.geom;

class ArcIterator implements PathIterator {
    private Arc2D arc;
    private AffineTransform affine;
    private int index;
    private double cx, cy, w, h, angleStart, angleExtent;
    private int arcType;

    ArcIterator(Arc2D arc, AffineTransform at) {
        this.arc = arc;
        this.affine = at;
        this.index = 0;
        this.cx = arc.getCenterX();
        this.cy = arc.getCenterY();
        this.w = arc.getWidth() / 2.0;
        this.h = arc.getHeight() / 2.0;
        this.angleStart = arc.getAngleStart();
        this.angleExtent = arc.getAngleExtent();
        this.arcType = arc.getArcType();
        if (arcType == Arc2D.OPEN) {
            this.steps = 3;
        } else if (arcType == Arc2D.CHORD) {
            this.steps = 4;
        } else {
            this.steps = 6;
        }
    }

    private int steps;

    public int getWindingRule() { return PathIterator.WIND_NON_ZERO; }
    public boolean isDone() { return index >= steps; }
    public void next() { index++; }

    private double interpolate(double v0, double v1, double t) {
        return v0 + t * (v1 - v0);
    }

    private Point2D.Double pointOnArc(double angle) {
        double rad = Math.toRadians(-angle);
        return new Point2D.Double(cx + Math.cos(rad) * w, cy + Math.sin(rad) * h);
    }

    public int currentSegment(float[] coords) {
        double[] dcoords = new double[6];
        int type = currentSegment(dcoords);
        for (int i = 0; i < 6; i++) coords[i] = (float) dcoords[i];
        return type;
    }

    public int currentSegment(double[] coords) {
        double angleEnd = angleStart + angleExtent;

        if (arcType == Arc2D.OPEN) {
            switch (index) {
                case 0: {
                    Point2D.Double p = pointOnArc(angleStart);
                    coords[0] = p.x; coords[1] = p.y;
                    return SEG_MOVETO;
                }
                case 1: {
                    Point2D.Double p = pointOnArc(angleEnd);
                    coords[0] = p.x; coords[1] = p.y;
                    return SEG_LINETO;
                }
                case 2:
                    return SEG_CLOSE;
            }
        } else if (arcType == Arc2D.CHORD) {
            switch (index) {
                case 0: {
                    Point2D.Double p = pointOnArc(angleStart);
                    coords[0] = p.x; coords[1] = p.y;
                    return SEG_MOVETO;
                }
                case 1: {
                    Point2D.Double p = pointOnArc(angleEnd);
                    coords[0] = p.x; coords[1] = p.y;
                    return SEG_LINETO;
                }
                case 2:
                    coords[0] = cx; coords[1] = cy;
                    return SEG_LINETO;
                case 3:
                    return SEG_CLOSE;
            }
        } else {
            switch (index) {
                case 0: {
                    Point2D.Double p = pointOnArc(angleStart);
                    coords[0] = p.x; coords[1] = p.y;
                    return SEG_MOVETO;
                }
                case 1: {
                    Point2D.Double p = pointOnArc(angleEnd);
                    coords[0] = p.x; coords[1] = p.y;
                    return SEG_LINETO;
                }
                case 2:
                    coords[0] = cx; coords[1] = cy;
                    return SEG_LINETO;
                case 3:
                    coords[0] = cx; coords[1] = cy;
                    return SEG_LINETO;
                case 4:
                    return SEG_CLOSE;
                case 5:
                    return SEG_CLOSE;
            }
        }
        return SEG_CLOSE;
    }
}
