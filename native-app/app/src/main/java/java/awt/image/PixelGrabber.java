package java.awt.image;

import java.awt.Image;

/**
 * Minimal stub for java.awt.image.PixelGrabber.
 * Used by GAMA's GamaImage and GamaImageFile to extract ARGB pixels from an Image
 * into an int[] buffer.
 */
public class PixelGrabber {
    private final Image image;
    private final int x, y, width, height;
    private final int[] pixels;
    private final int offset;
    private final int scansize;
    private boolean grabDone;
    private int status;

    public PixelGrabber(Image image, int x, int y, int width, int height,
                        int[] pixels, int offset, int scansize) {
        this.image = image;
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.pixels = pixels;
        this.offset = offset;
        this.scansize = scansize;
    }

    public boolean grabPixels() {
        try {
            if (image instanceof BufferedImage) {
                BufferedImage bi = (BufferedImage) image;
                bi.syncBitmapToData();
                for (int row = 0; row < height; row++) {
                    for (int col = 0; col < width; col++) {
                        pixels[offset + row * scansize + col] = bi.getRGB(x + col, y + row);
                    }
                }
                grabDone = true;
                status = 0;
                return true;
            }
        } catch (Exception e) {
            status = 1;
        }
        grabDone = true;
        return false;
    }

    public int getStatus() { return status; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public Object getPixels() { return pixels; }
}
