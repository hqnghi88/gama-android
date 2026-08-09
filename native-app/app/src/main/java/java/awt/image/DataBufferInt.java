package java.awt.image;

public class DataBufferInt extends DataBuffer {
    private int[] data;

    public DataBufferInt(int size) {
        super();
        this.data = new int[size];
    }

    public DataBufferInt(int[] dataArray, int size) {
        super();
        this.data = dataArray;
    }

    public int[] getData() { return data; }
    public int[] getData(int bank) { return data; }
}
