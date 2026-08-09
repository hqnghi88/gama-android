package java.awt.image;

public class DataBuffer {
    public static final int TYPE_BYTE = 0;
    public static final int TYPE_USHORT = 1;
    public static final int TYPE_SHORT = 2;
    public static final int TYPE_INT = 3;
    public static final int TYPE_FLOAT = 4;
    public static final int TYPE_DOUBLE = 5;

    public int getDataType() { return TYPE_BYTE; }
    public int getSize() { return 0; }
    public int getNumBanks() { return 1; }
    public int getOffset() { return 0; }
    public int getOffset(int bank) { return 0; }
    public int[] getSizeInt() { return new int[] { 0 }; }

    public static class Byte extends DataBuffer {
        public Byte(int size) {}
        public Byte(int size, int numBanks) {}
        public byte[] getData() { return new byte[0]; }
        public byte[] getData(int bank) { return new byte[0]; }
    }

    public static class Short extends DataBuffer {
        public Short(int size) {}
        public short[] getData() { return new short[0]; }
        public short[] getData(int bank) { return new short[0]; }
    }

    public static class Int extends DataBuffer {
        public Int(int size) {}
        public int[] getData() { return new int[0]; }
        public int[] getData(int bank) { return new int[0]; }
    }
}
