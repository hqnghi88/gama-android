package java.awt;

/**
 * Minimal Android stub of java.awt.AlphaComposite, matching the OpenJDK API
 * surface used by GAMA and jsvg at runtime (mixed-case rule fields + int rule
 * constants + getInstance/derive accessors).
 */
public class AlphaComposite implements Composite {

    public static final int CLEAR = 1;
    public static final int SRC = 2;
    public static final int DST = 3;
    public static final int SRC_OVER = 4;
    public static final int DST_OVER = 5;
    public static final int SRC_IN = 6;
    public static final int DST_IN = 7;
    public static final int SRC_OUT = 8;
    public static final int DST_OUT = 9;
    public static final int SRC_ATOP = 10;
    public static final int DST_ATOP = 11;
    public static final int XOR = 12;
    public static final int DARKEN = 13;
    public static final int LIGHTEN = 14;
    public static final int MULTIPLY = 15;
    public static final int SCREEN = 16;
    public static final int OVERLAY = 17;
    public static final int ADD = 18;

    public static final AlphaComposite Clear = new AlphaComposite(CLEAR, 1.0f);
    public static final AlphaComposite Src = new AlphaComposite(SRC, 1.0f);
    public static final AlphaComposite Dst = new AlphaComposite(DST, 1.0f);
    public static final AlphaComposite SrcOver = new AlphaComposite(SRC_OVER, 1.0f);
    public static final AlphaComposite DstOver = new AlphaComposite(DST_OVER, 1.0f);
    public static final AlphaComposite SrcIn = new AlphaComposite(SRC_IN, 1.0f);
    public static final AlphaComposite DstIn = new AlphaComposite(DST_IN, 1.0f);
    public static final AlphaComposite SrcOut = new AlphaComposite(SRC_OUT, 1.0f);
    public static final AlphaComposite DstOut = new AlphaComposite(DST_OUT, 1.0f);
    public static final AlphaComposite SrcAtop = new AlphaComposite(SRC_ATOP, 1.0f);
    public static final AlphaComposite DstAtop = new AlphaComposite(DST_ATOP, 1.0f);
    public static final AlphaComposite Xor = new AlphaComposite(XOR, 1.0f);

    private final float alpha;
    private final int rule;

    private AlphaComposite(int rule, float alpha) {
        this.rule = rule;
        this.alpha = alpha;
    }

    public static AlphaComposite getInstance(int rule) {
        switch (rule) {
            case CLEAR: return Clear;
            case SRC: return Src;
            case DST: return Dst;
            case SRC_OVER: return SrcOver;
            case DST_OVER: return DstOver;
            case SRC_IN: return SrcIn;
            case DST_IN: return DstIn;
            case SRC_OUT: return SrcOut;
            case DST_OUT: return DstOut;
            case SRC_ATOP: return SrcAtop;
            case DST_ATOP: return DstAtop;
            case XOR: return Xor;
            default:
                return new AlphaComposite(rule, 1.0f);
        }
    }

    public static AlphaComposite getInstance(int rule, float alpha) {
        if (alpha == 1.0f) {
            return getInstance(rule);
        }
        return new AlphaComposite(rule, alpha);
    }

    public AlphaComposite derive(int rule) {
        return (rule == this.rule) ? this : new AlphaComposite(rule, alpha);
    }

    public AlphaComposite derive(float alpha) {
        return (alpha == this.alpha) ? this : new AlphaComposite(rule, alpha);
    }

    public float getAlpha() {
        return alpha;
    }

    public int getRule() {
        return rule;
    }

    public int getTransparency() {
        return Transparency.TRANSLUCENT;
    }
}
