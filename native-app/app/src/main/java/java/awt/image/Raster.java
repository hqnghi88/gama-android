package java.awt.image;

public class Raster {
    public int getMinX() { return 0; }
    public int getMinY() { return 0; }
    public int getWidth() { return 0; }
    public int getHeight() { return 0; }
    public int getNumBands() { return 0; }
    public int getTransferType() { return 0; }
    public int[] getPixel(int x, int y, int[] iArray) { return iArray; }
    public float[] getPixel(int x, int y, float[] fArray) { return fArray; }
    public double[] getPixel(int x, int y, double[] dArray) { return dArray; }
    public int[] getPixels(int x, int y, int w, int h, int[] iArray) { return iArray; }
    public int getSample(int x, int y, int band) { return 0; }
    public double getSampleDouble(int x, int y, int band) { return 0; }
    public int[] getSamples(int x, int y, int w, int h, int band, int[] iArray) { return iArray; }
    public SampleModel getSampleModel() { return null; }
    public java.awt.image.ColorModel getColorModel() { return null; }
    public Object getDataElements(int x, int y, Object obj) { return obj; }
    public DataBuffer getDataBuffer() { return null; }
    public static WritableRaster createWritableRaster(int bands, int w, int h) { return new WritableRaster(); }
    public static DataBuffer getDataBufferStatic(Raster raster) { return raster != null ? raster.getDataBuffer() : null; }
}
