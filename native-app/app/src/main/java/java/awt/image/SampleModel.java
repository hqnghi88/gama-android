package java.awt.image;

public class SampleModel {
    public SampleModel(int dataType, int width, int height, int numBands) {}
    public int getDataType() { return 0; }
    public int getWidth() { return 0; }
    public int getHeight() { return 0; }
    public int getNumBands() { return 0; }
    public int getNumDataElements() { return 0; }
    public int getTransferType() { return 0; }
    public int[] getPixel(int x, int y, int[] iArray, java.awt.image.DataBuffer dataBuffer) { return iArray; }
    public int getSample(int x, int y, int band, java.awt.image.DataBuffer dataBuffer) { return 0; }
    public Object getDataElements(int x, int y, Object obj, java.awt.image.DataBuffer dataBuffer) { return obj; }
}
