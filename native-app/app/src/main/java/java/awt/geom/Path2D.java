package java.awt.geom;

public abstract class Path2D implements java.awt.Shape, Cloneable {

    public static final int WIND_EVEN_ODD = 0;
    public static final int WIND_NON_ZERO = 1;

    private static final int DEFAULT_CAPACITY = 16;

    int windingRule = WIND_NON_ZERO;
    double[] coords = new double[DEFAULT_CAPACITY * 2];
    int[] types = new int[DEFAULT_CAPACITY];
    int numTypes = 0;
    int numCoords = 0;
    int moveToIndex = -1;

    Path2D() {}

    Path2D(int rule, int initialCapacity) {
        if (rule != WIND_EVEN_ODD && rule != WIND_NON_ZERO) {
            throw new IllegalArgumentException("invalid winding rule " + rule);
        }
        this.windingRule = rule;
        int cap = Math.max(initialCapacity, 4);
        this.coords = new double[cap * 2];
        this.types = new int[cap];
    }

    private void ensureTypes(int needed) {
        if (needed <= types.length) return;
        int[] nt = new int[Math.max(types.length * 2, needed)];
        System.arraycopy(types, 0, nt, 0, numTypes);
        types = nt;
    }

    private void ensureCoords(int needed) {
        if (needed <= coords.length) return;
        double[] nc = new double[Math.max(coords.length * 2, needed)];
        System.arraycopy(coords, 0, nc, 0, numCoords);
        coords = nc;
    }

    protected final void appendType(int type, int nCoords) {
        ensureTypes(numTypes + 1);
        types[numTypes++] = type;
        ensureCoords(numCoords + nCoords);
    }

    public abstract void moveTo(double x, double y);
    public abstract void lineTo(double x, double y);
    public abstract void quadTo(double x1, double y1, double x2, double y2);
    public abstract void curveTo(double x1, double y1, double x2, double y2, double x3, double y3);
    public abstract void closePath();

    protected final void setWindingRuleInternal(int rule) {
        if (rule != WIND_EVEN_ODD && rule != WIND_NON_ZERO) {
            throw new IllegalArgumentException("invalid winding rule " + rule);
        }
        windingRule = rule;
    }

    public final int getWindingRule() { return windingRule; }

    public final void setWindingRule(int rule) {
        setWindingRuleInternal(rule);
    }

    public void reset() {
        numTypes = 0;
        numCoords = 0;
        moveToIndex = -1;
    }

    public void append(PathIterator pi, boolean connect) {
        if (pi == null) throw new NullPointerException("pi == null");
        double[] c = new double[6];
        while (!pi.isDone()) {
            int type = pi.currentSegment(c);
            switch (type) {
                case PathIterator.SEG_MOVETO:
                    if (!connect || numTypes == 0) {
                        moveTo(c[0], c[1]);
                    } else {
                        lineTo(c[0], c[1]);
                    }
                    break;
                case PathIterator.SEG_LINETO:
                    lineTo(c[0], c[1]);
                    break;
                case PathIterator.SEG_QUADTO:
                    quadTo(c[0], c[1], c[2], c[3]);
                    break;
                case PathIterator.SEG_CUBICTO:
                    curveTo(c[0], c[1], c[2], c[3], c[4], c[5]);
                    break;
                case PathIterator.SEG_CLOSE:
                    closePath();
                    break;
                default:
                    throw new IllegalStateException("unknown segment type " + type);
            }
            pi.next();
        }
    }

    public void append(java.awt.Shape s, boolean connect) {
        append(s.getPathIterator(null), connect);
    }

    protected final void moveToImpl(double x, double y) {
        appendType(PathIterator.SEG_MOVETO, 2);
        coords[numCoords++] = x;
        coords[numCoords++] = y;
        moveToIndex = numTypes - 1;
    }

    protected final void lineToImpl(double x, double y) {
        if (numTypes == 0) { moveToImpl(x, y); return; }
        appendType(PathIterator.SEG_LINETO, 2);
        coords[numCoords++] = x;
        coords[numCoords++] = y;
    }

    protected final void quadToImpl(double x1, double y1, double x2, double y2) {
        if (numTypes == 0) { moveToImpl(x1, y1); lineToImpl(x2, y2); return; }
        appendType(PathIterator.SEG_QUADTO, 4);
        coords[numCoords++] = x1;
        coords[numCoords++] = y1;
        coords[numCoords++] = x2;
        coords[numCoords++] = y2;
    }

    protected final void curveToImpl(double x1, double y1, double x2, double y2, double x3, double y3) {
        if (numTypes == 0) { moveToImpl(x1, y1); lineToImpl(x3, y3); return; }
        appendType(PathIterator.SEG_CUBICTO, 6);
        coords[numCoords++] = x1;
        coords[numCoords++] = y1;
        coords[numCoords++] = x2;
        coords[numCoords++] = y2;
        coords[numCoords++] = x3;
        coords[numCoords++] = y3;
    }

    protected final void closePathImpl() {
        if (numTypes == 0) return;
        if (types[numTypes - 1] != PathIterator.SEG_CLOSE) {
            appendType(PathIterator.SEG_CLOSE, 0);
        }
    }

    public Point2D getCurrentPoint() {
        if (numTypes == 0) return null;
        int last = numTypes - 1;
        int type = types[last];
        if (type == PathIterator.SEG_CLOSE) {
            if (moveToIndex < 0) return null;
            int idx = 0;
            for (int i = 0; i < moveToIndex; i++) idx += segCoordCount(types[i]);
            return new Point2D.Double(coords[idx], coords[idx + 1]);
        }
        int n = segCoordCount(type);
        int idx = numCoords - n;
        return new Point2D.Double(coords[idx], coords[idx + 1]);
    }

    private static int segCoordCount(int type) {
        switch (type) {
            case PathIterator.SEG_MOVETO:
            case PathIterator.SEG_LINETO: return 2;
            case PathIterator.SEG_QUADTO: return 4;
            case PathIterator.SEG_CUBICTO: return 6;
            default: return 0;
        }
    }

    public java.awt.Rectangle getBounds() {
        Rectangle2D b = getBounds2D();
        return b.getBounds();
    }

    public Rectangle2D getBounds2D() {
        if (numCoords == 0) return new Rectangle2D.Double();
        double minX = java.lang.Double.MAX_VALUE, minY = java.lang.Double.MAX_VALUE;
        double maxX = -java.lang.Double.MAX_VALUE, maxY = -java.lang.Double.MAX_VALUE;
        for (int i = 0; i < numCoords; i += 2) {
            double x = coords[i], y = coords[i + 1];
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            if (x > maxX) maxX = x;
            if (y > maxY) maxY = y;
        }
        return new Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY);
    }

    public PathIterator getPathIterator(AffineTransform at) {
        return new Path2DIterator(this, at);
    }

    public PathIterator getPathIterator(AffineTransform at, double flatness) {
        return getPathIterator(at);
    }

    public boolean contains(double x, double y) { return false; }
    public boolean contains(double x, double y, double w, double h) { return false; }
    public boolean contains(Point2D p) { return contains(p.getX(), p.getY()); }
    public boolean contains(Rectangle2D r) { return contains(r.getX(), r.getY(), r.getWidth(), r.getHeight()); }
    public boolean intersects(double x, double y, double w, double h) { return false; }
    public boolean intersects(Rectangle2D r) { return intersects(r.getX(), r.getY(), r.getWidth(), r.getHeight()); }

    public void trimToSize() {}

    @Override
    public Path2D clone() {
        try {
            Path2D c = (Path2D) super.clone();
            c.coords = coords.clone();
            c.types = types.clone();
            return c;
        } catch (CloneNotSupportedException e) {
            throw new InternalError(e);
        }
    }

    private static final class Path2DIterator implements PathIterator {
        private final Path2D path;
        private final AffineTransform at;
        private int typeIdx = 0;
        private int coordIdx = 0;
        private final double[] c = new double[6];

        Path2DIterator(Path2D path, AffineTransform at) {
            this.path = path;
            this.at = at;
        }

        public int getWindingRule() { return path.getWindingRule(); }

        public boolean isDone() { return typeIdx >= path.numTypes; }

        public void next() {
            int type = path.types[typeIdx];
            typeIdx++;
            switch (type) {
                case SEG_MOVETO:
                case SEG_LINETO: coordIdx += 2; break;
                case SEG_QUADTO: coordIdx += 4; break;
                case SEG_CUBICTO: coordIdx += 6; break;
                case SEG_CLOSE: break;
            }
        }

        public int currentSegment(float[] coordsOut) {
            int type = currentSegment(c);
            for (int i = 0; i < 6; i++) coordsOut[i] = (float) c[i];
            return type;
        }

        public int currentSegment(double[] coordsOut) {
            int type = path.types[typeIdx];
            int n = segCoordCount(type);
            for (int i = 0; i < n; i++) coordsOut[i] = path.coords[coordIdx + i];
            if (at != null && type != SEG_CLOSE) {
                at.transform(coordsOut, 0, coordsOut, 0, n / 2);
            }
            return type;
        }

        private static int segCoordCount(int type) {
            switch (type) {
                case SEG_MOVETO:
                case SEG_LINETO: return 2;
                case SEG_QUADTO: return 4;
                case SEG_CUBICTO: return 6;
                default: return 0;
            }
        }
    }

    public static class Float extends Path2D {
        public Float() {}
        public Float(int rule) { super(rule, DEFAULT_CAPACITY); }
        public Float(int rule, int initialCapacity) { super(rule, initialCapacity); }
        public void moveTo(float x, float y) { moveToImpl(x, y); }
        public void lineTo(float x, float y) { lineToImpl(x, y); }
        public void quadTo(float x1, float y1, float x2, float y2) { quadToImpl(x1, y1, x2, y2); }
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) { curveToImpl(x1, y1, x2, y2, x3, y3); }
        public void closePath() { closePathImpl(); }
        @Override public void moveTo(double x, double y) { moveToImpl(x, y); }
        @Override public void lineTo(double x, double y) { lineToImpl(x, y); }
        @Override public void quadTo(double x1, double y1, double x2, double y2) { quadToImpl(x1, y1, x2, y2); }
        @Override public void curveTo(double x1, double y1, double x2, double y2, double x3, double y3) { curveToImpl(x1, y1, x2, y2, x3, y3); }
    }

    public static class Double extends Path2D {
        public Double() {}
        public Double(int rule) { super(rule, DEFAULT_CAPACITY); }
        public Double(int rule, int initialCapacity) { super(rule, initialCapacity); }
        @Override public void moveTo(double x, double y) { moveToImpl(x, y); }
        @Override public void lineTo(double x, double y) { lineToImpl(x, y); }
        @Override public void quadTo(double x1, double y1, double x2, double y2) { quadToImpl(x1, y1, x2, y2); }
        @Override public void curveTo(double x1, double y1, double x2, double y2, double x3, double y3) { curveToImpl(x1, y1, x2, y2, x3, y3); }
        @Override public void closePath() { closePathImpl(); }
    }
}
