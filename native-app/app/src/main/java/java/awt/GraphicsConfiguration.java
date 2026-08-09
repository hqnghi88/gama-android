package java.awt;

import java.awt.image.BufferedImage;

public class GraphicsConfiguration {
    public BufferedImage createCompatibleImage(int width, int height, int transparency) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }
    public BufferedImage createCompatibleImage(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }
    public java.awt.Rectangle getBounds() { return new java.awt.Rectangle(); }
}
