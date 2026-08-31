package java.awt;

public class BasicStroke implements Stroke {
    public static final int JOIN_MITER = 0;
    public static final int JOIN_ROUND = 1;
    public static final int JOIN_BEVEL = 2;
    public static final int CAP_BUTT = 0;
    public static final int CAP_ROUND = 1;
    public static final int CAP_SQUARE = 2;

    private float width;
    private int cap;
    private int join;
    private float miterLimit;
    private float[] dash;

    public BasicStroke() { this(1.0f, CAP_SQUARE, JOIN_MITER, 10.0f, null, 0.0f); }
    public BasicStroke(float width) { this(width, CAP_SQUARE, JOIN_MITER, 10.0f, null, 0.0f); }
    public BasicStroke(float width, int cap, int join) { this(width, cap, join, 10.0f, null, 0.0f); }
    public BasicStroke(float width, int cap, int join, float miterLimit) {
        this(width, cap, join, miterLimit, null, 0.0f);
    }
    public BasicStroke(float width, int cap, int join, float miterLimit, float[] dash, float dashPhase) {
        this.width = width;
        this.cap = cap;
        this.join = join;
        this.miterLimit = miterLimit;
        this.dash = dash;
    }

    public float getLineWidth() { return width; }
    public int getEndCap() { return cap; }
    public int getLineJoin() { return join; }
    public float getMiterLimit() { return miterLimit; }
    public float[] getDashArray() { return dash; }
    public float getDashPhase() { return 0f; }
}
