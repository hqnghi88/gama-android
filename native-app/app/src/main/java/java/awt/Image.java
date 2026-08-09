package java.awt;

import java.awt.image.ImageObserver;

public abstract class Image {
    public static final int SCALE_DEFAULT = 1;
    public static final int SCALE_FAST = 2;
    public static final int SCALE_SMOOTH = 4;
    public static final int SCALE_REPLICATE = 8;
    public static final int SCALE_AREA_AVERAGING = 16;

    public abstract int getWidth(ImageObserver observer);
    public abstract int getHeight(ImageObserver observer);
    public Image getScaledInstance(int width, int height, int hints) { return this; }
    public abstract Object getProperty(String name, ImageObserver observer);
    public Graphics getGraphics() { return null; }
    public abstract java.awt.image.ColorModel getColorModel();
    public abstract java.awt.image.WritableRaster getRaster();
    public abstract int getType();
    public boolean isScaled() { return false; }
    public void flush() {}
}
