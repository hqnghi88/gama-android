package javax.imageio.stream;

public interface ImageInputStream {
    void close() throws java.io.IOException;
    boolean isCached();
    void seek(long pos) throws java.io.IOException;
    long getStreamPosition() throws java.io.IOException;
    void setByteOrder(java.nio.ByteOrder byteOrder);
    java.nio.ByteOrder getByteOrder();
    int readUnsignedByte() throws java.io.IOException;
    int readUnsignedShort() throws java.io.IOException;
    int readInt() throws java.io.IOException;
    long readLong() throws java.io.IOException;
    float readFloat() throws java.io.IOException;
    double readDouble() throws java.io.IOException;
    void readFully(byte[] b, int off, int len) throws java.io.IOException;
    void readFully(char[] c, int off, int len) throws java.io.IOException;
    void readFully(short[] s, int off, int len) throws java.io.IOException;
    void readFully(int[] i, int off, int len) throws java.io.IOException;
    void readFully(long[] l, int off, int len) throws java.io.IOException;
    void readFully(float[] f, int off, int len) throws java.io.IOException;
    void readFully(double[] d, int off, int len) throws java.io.IOException;
}
