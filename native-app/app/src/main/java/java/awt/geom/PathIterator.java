package java.awt.geom;

public interface PathIterator {
    public static final int SEG_MOVETO = 1;
    public static final int SEG_LINETO = 2;
    public static final int SEG_QUADTO = 3;
    public static final int SEG_CUBICTO = 4;
    public static final int SEG_CLOSE = 5;
    public static final int WIND_EVEN_ODD = 0;
    public static final int WIND_NON_ZERO = 1;
    int currentSegment(double[] coords);
    int currentSegment(float[] coords);
    int getWindingRule();
    boolean isDone();
    void next();
}
