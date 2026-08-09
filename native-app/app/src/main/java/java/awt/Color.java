package java.awt;

public class Color implements Paint {
    public static final Color WHITE = new Color(255, 255, 255);
    public static final Color BLACK = new Color(0, 0, 0);
    public static final Color RED = new Color(255, 0, 0);
    public static final Color GREEN = new Color(0, 255, 0);
    public static final Color BLUE = new Color(0, 0, 255);
    public static final Color YELLOW = new Color(255, 255, 0);
    public static final Color CYAN = new Color(0, 255, 255);
    public static final Color MAGENTA = new Color(255, 0, 255);
    public static final Color GRAY = new Color(128, 128, 128);
    public static final Color DARK_GRAY = new Color(64, 64, 64);
    public static final Color LIGHT_GRAY = new Color(192, 192, 192);
    public static final Color ORANGE = new Color(255, 200, 0);
    public static final Color PINK = new Color(255, 175, 175);
    // Lowercase aliases used by GAMA
    public static final Color black = BLACK;
    public static final Color blue = BLUE;
    public static final Color cyan = CYAN;
    public static final Color darkGray = DARK_GRAY;
    public static final Color gray = GRAY;
    public static final Color green = GREEN;
    public static final Color lightGray = LIGHT_GRAY;
    public static final Color magenta = MAGENTA;
    public static final Color orange = ORANGE;
    public static final Color pink = PINK;
    public static final Color red = RED;
    public static final Color white = WHITE;
    public static final Color yellow = YELLOW;

    private final int value;

    public Color(int r, int g, int b) {
        value = ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public Color(int rgb, boolean hasalpha) {
        if (hasalpha) {
            value = rgb;
        } else {
            value = 0xFF000000 | (rgb & 0xFFFFFF);
        }
    }

    public Color(int rgb) {
        value = 0xFF000000 | (rgb & 0xFFFFFF);
    }

    public Color(int r, int g, int b, int a) {
        value = ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }

    public Color(float r, float g, float b) {
        this((int)(r * 255 + 0.5f), (int)(g * 255 + 0.5f), (int)(b * 255 + 0.5f));
    }

    public Color(float r, float g, float b, float a) {
        this((int)(r * 255 + 0.5f), (int)(g * 255 + 0.5f), (int)(b * 255 + 0.5f), (int)(a * 255 + 0.5f));
    }

    public int getRed() { return (value >> 16) & 0xFF; }
    public int getGreen() { return (value >> 8) & 0xFF; }
    public int getBlue() { return value & 0xFF; }
    public int getAlpha() { return (value >> 24) & 0xFF; }
    public int getRGB() { return value; }

    public int getTransparency() { return getAlpha() == 255 ? Transparency.OPAQUE : Transparency.TRANSLUCENT; }

    public static Color decode(String nm) throws NumberFormatException {
        Integer val = Integer.decode(nm);
        return new Color(val);
    }

    public static Color getColor(String nm) {
        return null;
    }

    public static Color getColor(String nm, Color v) {
        return v;
    }

    public static Color getColor(String nm, int v) {
        return new Color(v);
    }

    public Color brighter() {
        int r = getRed(), g = getGreen(), b = getBlue();
        int factor = 3;
        if (r == 0 && g == 0 && b == 0) return new Color(factor, factor, factor);
        if (r > 0 && r < factor) r = factor;
        if (g > 0 && g < factor) g = factor;
        if (b > 0 && b < factor) b = factor;
        return new Color(Math.min((int)(r / 0.7), 255), Math.min((int)(g / 0.7), 255), Math.min((int)(b / 0.7), 255));
    }

    public Color darker() {
        return new Color(Math.max((int)(getRed() * 0.7), 0), Math.max((int)(getGreen() * 0.7), 0), Math.max((int)(getBlue() * 0.7), 0));
    }

    public float[] getRGBComponents(float[] componentArray) {
        float[] c = new float[]{getRed()/255f, getGreen()/255f, getBlue()/255f, getAlpha()/255f};
        if (componentArray != null) { System.arraycopy(c, 0, componentArray, 0, Math.min(c.length, componentArray.length)); return componentArray; }
        return c;
    }

    public float[] getRGBColorComponents(float[] componentArray) {
        float[] c = new float[]{getRed()/255f, getGreen()/255f, getBlue()/255f};
        if (componentArray != null) { System.arraycopy(c, 0, componentArray, 0, Math.min(c.length, componentArray.length)); return componentArray; }
        return c;
    }

    public int hashCode() { return value; }

    public boolean equals(Object obj) {
        if (obj instanceof Color) return value == ((Color)obj).value;
        return false;
    }

    public String toString() { return getClass().getName() + "[r=" + getRed() + ",g=" + getGreen() + ",b=" + getBlue() + "]"; }
}
