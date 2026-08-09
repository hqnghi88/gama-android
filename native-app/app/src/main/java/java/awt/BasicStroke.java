package java.awt;

public class BasicStroke implements Stroke {
    public static final int JOIN_MITER = 0;
    public static final int JOIN_ROUND = 1;
    public static final int JOIN_BEVEL = 2;
    public static final int CAP_BUTT = 0;
    public static final int CAP_ROUND = 1;
    public static final int CAP_SQUARE = 2;

    public BasicStroke() {}
    public BasicStroke(float width) {}
    public BasicStroke(float width, int cap, int join) {}
    public BasicStroke(float width, int cap, int join, float miterlimit) {}
    public BasicStroke(float width, int cap, int join, float miterlimit, float[] dash, float dash_phase) {}
}
