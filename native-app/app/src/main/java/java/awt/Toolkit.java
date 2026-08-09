package java.awt;

import java.awt.image.ColorModel;
import java.awt.image.DirectColorModel;
import java.awt.image.IndexColorModel;

public class Toolkit {
    private static final Toolkit instance = new Toolkit();

    public static Toolkit getDefaultToolkit() {
        return instance;
    }

    public Dimension getScreenSize() {
        return new Dimension(1080, 2400);
    }

    public int getScreenResolution() {
        return 160;
    }

    public ColorModel getColorModel() {
        return new DirectColorModel(32, 0xFF0000, 0xFF00, 0xFF);
    }

    public String getSystemProperty(String key) {
        return null;
    }

    public void sync() {}

    public void beep() {}
}
