package java.awt;

import java.awt.image.BufferedImage;

public class GraphicsEnvironment {
    public static GraphicsEnvironment getLocalGraphicsEnvironment() { return new GraphicsEnvironment(); }
    public GraphicsDevice[] getScreenDevices() { return new GraphicsDevice[0]; }
    public GraphicsDevice getDefaultScreenDevice() { return new GraphicsDevice(); }
    public Graphics2D createGraphics(BufferedImage image) { return new Graphics2D(); }
    public static boolean isHeadless() { return true; }
    public String[] getAvailableFontFamilyNames() { return new String[0]; }
    public String[] getAvailableFontNames() { return new String[0]; }
}
