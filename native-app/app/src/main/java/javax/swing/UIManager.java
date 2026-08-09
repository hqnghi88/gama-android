package javax.swing;

import java.awt.Color;

/**
 * Minimal UIManager stub for Android/JFreeChart compatibility.
 */
public class UIManager {

    public static Color getColor(Object key) {
        if (key != null && key.toString().contains("background")) {
            return Color.WHITE;
        }
        return Color.LIGHT_GRAY;
    }

    public static Object get(Object key) { return null; }
    public static Object put(Object key, Object value) { return null; }
    public static String getString(Object key) { return ""; }
    public static int getInt(Object key) { return 0; }
    public static boolean getBoolean(Object key) { return false; }
    public static javax.swing.plaf.ComponentUI getUI(java.awt.Component c) { return null; }

    public static Object getLookAndFeelDefaults() {
        return null;
    }
}
