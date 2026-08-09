package java.awt.image;

public class WritableRaster extends Raster {
    private DataBuffer dataBuffer;
    private int width, height;

    public WritableRaster() {}
    public WritableRaster(DataBuffer buffer) { this.dataBuffer = buffer; }
    public WritableRaster(DataBuffer buffer, int w, int h) { this.dataBuffer = buffer; this.width = w; this.height = h; }
    public WritableRaster(DataBuffer buffer, int w, int h, int scanlineStride, int pixelStride, int[] bandOffsets, java.awt.Point location) { this.dataBuffer = buffer; this.width = w; this.height = h; }

    @Override
    public DataBuffer getDataBuffer() { return dataBuffer; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getMinX() { return 0; }
    public int getMinY() { return 0; }

    public WritableRaster createCompatibleWritableRaster() {
        return new WritableRaster(dataBuffer, width > 0 ? width : 1, height > 0 ? height : 1);
    }
    public WritableRaster createCompatibleWritableRaster(int w, int h) {
        DataBufferInt buf = new DataBufferInt(w * h);
        return new WritableRaster(buf, w, h);
    }

    public void setPixel(int x, int y, int[] iArray) {}
    public void setPixel(int x, int y, float[] fArray) {}
    public void setPixel(int x, int y, double[] dArray) {}
    public void setPixels(int startX, int startY, int w, int h, int[] iArray) {}
    public void setSamples(int x, int y, int w, int h, int band, int[] iArray) {}
    public void setDataElements(int x, int y, Object inObj) {}
    public void setDataElements(int x, int y, int w, int h, Object inObj) {}
    public void setRect(int x, int y, Raster src) {}
    public void setRect(Raster src) {}
}
