package java.awt.image;

public class ColorModel {
    private boolean hasAlpha;
    public ColorModel(int bits) { this.hasAlpha = false; }
    public ColorModel(int bits, boolean hasAlpha) { this.hasAlpha = hasAlpha; }
    public ColorModel() { this.hasAlpha = false; }
    public int getPixelSize() { return 0; }
    public int getTransferType() { return DataBuffer.TYPE_BYTE; }
    public int getNumComponents() { return 0; }
    public int getNumColorComponents() { return 0; }
    public int getComponentSize(int componentIdx) { return 8; }
    public int[] getComponentSize() { return new int[0]; }
    public boolean isAlphaPremultiplied() { return false; }
    public boolean hasAlpha() { return hasAlpha; }
    public boolean isTranslucent() { return hasAlpha; }
    public int getTransparency() { return hasAlpha ? 4 : 1; }
    public int getRed(int pixel) { return 0; }
    public int getGreen(int pixel) { return 0; }
    public int getBlue(int pixel) { return 0; }
    public int getAlpha(int pixel) { return 255; }
    public int getRGB(int pixel) { return 0xFF000000; }
    public int getRed(java.awt.image.DataBuffer dataBuffer, int bidx, int pixel) { return 0; }
    public int getGreen(java.awt.image.DataBuffer dataBuffer, int bidx, int pixel) { return 0; }
    public int getBlue(java.awt.image.DataBuffer dataBuffer, int bidx, int pixel) { return 0; }
    public int getAlpha(java.awt.image.DataBuffer dataBuffer, int bidx, int pixel) { return 255; }
    public int getRGB(java.awt.image.DataBuffer dataBuffer, int bidx, int pixel, int[] raster) { return 0xFF000000; }
    public Object getDataElements(int rgb, Object pixel) { return pixel; }
    public int[] getComponents(int pixel, int[] components, int offset) { return components; }
    public int[] getComponents(Object pixel, int[] components, int offset) { return components; }
    public int[] getComponents(java.awt.image.DataBuffer dataBuffer, int bidx, int pixel, int[] components, int offset) { return components; }
    public int getUnnormalizedComponents(float[] normComponents, int normOffset, int[] components, int offset) { return components != null ? components.length : 0; }
    public float[] getNormalizedComponents(int[] components, int offset, float[] normComponents, int normOffset) { return normComponents; }
    public ColorModel coerceData(java.awt.image.WritableRaster raster, boolean isAlphaPremultiplied) { return this; }
    public boolean isCompatibleRaster(java.awt.image.Raster raster) { return true; }
    public java.awt.image.WritableRaster createCompatibleWritableRaster(int w, int h) { return null; }
    public java.awt.image.WritableRaster createCompatibleWritableRaster(int w, int h, int bits[], java.awt.Point location) { return null; }
    public static ColorModel getRGBdefault() { return new DirectColorModel(32, 0xFF0000, 0xFF00, 0xFF, 0xFF000000); }
}
