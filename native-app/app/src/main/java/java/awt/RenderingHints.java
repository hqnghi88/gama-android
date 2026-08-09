package java.awt;

public class RenderingHints extends java.util.AbstractMap<RenderingHints.Key, Object> {
    public static final Key KEY_ALPHA_INTERPOLATION = new Key(1);
    public static final Key KEY_ANTIALIASING = new Key(2);
    public static final Key KEY_COLOR_RENDERING = new Key(3);
    public static final Key KEY_DITHERING = new Key(4);
    public static final Key KEY_FRACTIONALMETRICS = new Key(5);
    public static final Key KEY_INTERPOLATION = new Key(6);
    public static final Key KEY_RENDERING = new Key(7);
    public static final Key KEY_RESOLUTION_VARIANT = new Key(8);
    public static final Key KEY_STROKE_CONTROL = new Key(9);
    public static final Key KEY_TEXT_ANTIALIASING = new Key(10);

    public static final Object VALUE_ALPHA_INTERPOLATION_DEFAULT = "ALPHA_DEFAULT";
    public static final Object VALUE_ALPHA_INTERPOLATION_QUALITY = "ALPHA_QUALITY";
    public static final Object VALUE_ALPHA_INTERPOLATION_SPEED = "ALPHA_SPEED";
    public static final Object VALUE_ANTIALIAS_DEFAULT = "AA_DEFAULT";
    public static final Object VALUE_ANTIALIAS_OFF = "AA_OFF";
    public static final Object VALUE_ANTIALIAS_ON = "AA_ON";
    public static final Object VALUE_COLOR_RENDER_DEFAULT = "COLOR_DEFAULT";
    public static final Object VALUE_COLOR_RENDER_QUALITY = "COLOR_QUALITY";
    public static final Object VALUE_COLOR_RENDER_SPEED = "COLOR_SPEED";
    public static final Object VALUE_DITHER_DEFAULT = "DITHER_DEFAULT";
    public static final Object VALUE_DITHER_DISABLE = "DITHER_DISABLE";
    public static final Object VALUE_DITHER_ENABLE = "DITHER_ENABLE";
    public static final Object VALUE_FRACTIONALMETRICS_DEFAULT = "FM_DEFAULT";
    public static final Object VALUE_FRACTIONALMETRICS_OFF = "FM_OFF";
    public static final Object VALUE_FRACTIONALMETRICS_ON = "FM_ON";
    public static final Object VALUE_INTERPOLATION_BICUBIC = "INTERP_BICUBIC";
    public static final Object VALUE_INTERPOLATION_BILINEAR = "INTERP_BILINEAR";
    public static final Object VALUE_INTERPOLATION_NEAREST_NEIGHBOR = "INTERP_NEAREST";
    public static final Object VALUE_RENDER_DEFAULT = "RENDER_DEFAULT";
    public static final Object VALUE_RENDER_QUALITY = "RENDER_QUALITY";
    public static final Object VALUE_RENDER_SPEED = "RENDER_SPEED";
    public static final Object VALUE_RESOLUTION_DEFAULT = "RES_DEFAULT";
    public static final Object VALUE_RESOLUTION_VARIANT_NATIVE = "RES_NATIVE";
    public static final Object VALUE_RESOLUTION_VARIANT_OPTIMIZE = "RES_OPTIMIZE";
    public static final Object VALUE_STROKE_ADAPTIVE = "STROKE_ADAPTIVE";
    public static final Object VALUE_STROKE_DEFAULT = "STROKE_DEFAULT";
    public static final Object VALUE_STROKE_NORMALIZE = "STROKE_NORMALIZE";
    public static final Object VALUE_STROKE_PURE = "STROKE_PURE";
    public static final Object VALUE_TEXT_ANTIALIAS_DEFAULT = "TA_DEFAULT";
    public static final Object VALUE_TEXT_ANTIALIAS_LCD_HBGR = "TA_LCD_HBGR";
    public static final Object VALUE_TEXT_ANTIALIAS_LCD_HRGB = "TA_LCD_HRGB";
    public static final Object VALUE_TEXT_ANTIALIAS_LCD_VBGR = "TA_LCD_VBGR";
    public static final Object VALUE_TEXT_ANTIALIAS_LCD_VRGB = "TA_LCD_VRGB";
    public static final Object VALUE_TEXT_ANTIALIAS_OFF = "TA_OFF";
    public static final Object VALUE_TEXT_ANTIALIAS_ON = "TA_ON";

    public RenderingHints(java.util.Map<Key, Object> init) {}
    public RenderingHints(Key key, Object value) {}

    @Override public int size() { return 0; }
    @Override public boolean isEmpty() { return true; }
    @Override public boolean containsKey(Object key) { return false; }
    @Override public Object get(Object key) { return null; }
    @Override public Object put(Key key, Object value) { return null; }
    @Override public Object remove(Object key) { return null; }
    @Override public java.util.Set<Entry<Key, Object>> entrySet() { return new java.util.HashSet<>(); }
    @Override public void putAll(java.util.Map<? extends Key, ? extends Object> m) {}

    public void add(RenderingHints.Key key, Object value) {}
    public void add(RenderingHints hints) {}
    public boolean isKey(Object key) { return false; }

    public static class Key {
        private int key;
        public Key(int key) { this.key = key; }
        public int getKey() { return key; }
        public boolean compatibleValue(Object value) { return true; }
    }
}
