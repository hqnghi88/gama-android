package java.awt.color;

public class ColorSpace {

    public static final int TYPE_RGB = 5;
    public static final int TYPE_GRAY = 6;
    public static final int CS_sRGB = 1000;
    public static final int CS_LINEAR_RGB = 1004;
    public static final int CS_CIEXYZ = 1001;
    public static final int CS_PYCC = 1002;
    public static final int CS_GRAY = 1003;

    private final int type;
    private final int cspace;
    private final int numComponents;

    private static final ColorSpace GRAY = new ColorSpace(CS_GRAY, TYPE_GRAY, 1, new boolean[] {false});
    private static final ColorSpace SRGB = new ColorSpace(CS_sRGB, TYPE_RGB, 3, new boolean[] {false, false, false});
    private static final ColorSpace CIEXYZ = new ColorSpace(CS_CIEXYZ, TYPE_RGB, 3, new boolean[] {false, false, false});

    private final boolean[] isCS_sRGB;

    protected ColorSpace(int type, int numComponents) {
        this(CS_sRGB, type, numComponents, null);
    }

    private ColorSpace(int cspace, int type, int numComponents, boolean[] isCS_sRGB) {
        this.cspace = cspace;
        this.type = type;
        this.numComponents = numComponents;
        this.isCS_sRGB = isCS_sRGB;
    }

    public static ColorSpace getInstance(int colorspace) {
        switch (colorspace) {
            case CS_GRAY: return GRAY;
            case CS_CIEXYZ: return CIEXYZ;
            default: return SRGB;
        }
    }

    public int getType() {
        return type;
    }

    public int getNumComponents() {
        return numComponents;
    }

    public int getNumColorComponents() {
        return numComponents;
    }

    public boolean isCS_sRGB() {
        return cspace == CS_sRGB;
    }

    public String getName(int idx) {
        return "color space";
    }

    public float[] toRGB(float[] colorvalue) {
        float[] out = new float[3];
        if (colorvalue != null) {
            float c = colorvalue[0];
            out[0] = clamp(c);
            if (cspace == CS_GRAY) {
                out[1] = clamp(c);
                out[2] = clamp(c);
            } else {
                out[1] = clamp(colorvalue.length > 1 ? colorvalue[1] : c);
                out[2] = clamp(colorvalue.length > 2 ? colorvalue[2] : c);
            }
        }
        return out;
    }

    public float[] fromRGB(float[] rgbvalue) {
        float[] out = new float[numComponents];
        if (rgbvalue != null) {
            if (cspace == CS_GRAY) {
                out[0] = clamp(0.299f * rgbvalue[0] + 0.587f * rgbvalue[1] + 0.114f * rgbvalue[2]);
            } else {
                for (int i = 0; i < out.length && i < 3; i++) {
                    out[i] = clamp(rgbvalue[i]);
                }
            }
        }
        return out;
    }

    public float[] toCIEXYZ(float[] colorvalue) {
        float[] rgb = toRGB(colorvalue);
        return new float[] { rgb[0] * 0.9505f, rgb[1], rgb[2] * 0.8950f };
    }

    public float[] fromCIEXYZ(float[] colorvalue) {
        if (colorvalue == null) return new float[numComponents];
        float[] rgb = new float[] { colorvalue[0] / 0.9505f, colorvalue[1], colorvalue[2] / 0.8950f };
        return fromRGB(rgb);
    }

    private static float clamp(float v) {
        if (v < 0) return 0;
        if (v > 1) return 1;
        return v;
    }
}