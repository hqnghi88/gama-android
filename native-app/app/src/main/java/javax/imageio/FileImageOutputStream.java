package javax.imageio;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import javax.imageio.stream.ImageOutputStream;

public class FileImageOutputStream implements ImageOutputStream {

    private final File file;
    private final OutputStream outputStream;
    private long position = 0;

    public FileImageOutputStream(File file) {
        this.file = file;
        try {
            this.outputStream = new FileOutputStream(file);
        } catch (IOException e) {
            throw new RuntimeException("Cannot open file for writing: " + file, e);
        }
    }

    @Override
    public void close() throws IOException {
        outputStream.close();
    }

    @Override
    public void flush() throws IOException {
        outputStream.flush();
    }

    @Override
    public void write(int b) throws IOException {
        outputStream.write(b);
        position++;
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        outputStream.write(b, off, len);
        position += len;
    }

    @Override
    public boolean isCached() {
        return false;
    }

    @Override
    public void seek(long pos) throws IOException {
        throw new IOException("Cannot seek on a write-only stream");
    }

    @Override
    public long getStreamPosition() {
        return position;
    }

    @Override
    public void setByteOrder(java.nio.ByteOrder byteOrder) {}

    @Override
    public java.nio.ByteOrder getByteOrder() {
        return java.nio.ByteOrder.BIG_ENDIAN;
    }

    @Override
    public int readUnsignedByte() throws IOException {
        throw new IOException("Cannot read from a write-only stream");
    }

    @Override
    public int readUnsignedShort() throws IOException {
        throw new IOException("Cannot read from a write-only stream");
    }

    @Override
    public int readInt() throws IOException {
        throw new IOException("Cannot read from a write-only stream");
    }

    @Override
    public long readLong() throws IOException {
        throw new IOException("Cannot read from a write-only stream");
    }

    @Override
    public float readFloat() throws IOException {
        throw new IOException("Cannot read from a write-only stream");
    }

    @Override
    public double readDouble() throws IOException {
        throw new IOException("Cannot read from a write-only stream");
    }

    @Override
    public void readFully(byte[] b, int off, int len) throws IOException {
        throw new IOException("Cannot read from a write-only stream");
    }

    @Override
    public void readFully(char[] c, int off, int len) throws IOException {
        throw new IOException("Cannot read from a write-only stream");
    }

    @Override
    public void readFully(short[] s, int off, int len) throws IOException {
        throw new IOException("Cannot read from a write-only stream");
    }

    @Override
    public void readFully(int[] i, int off, int len) throws IOException {
        throw new IOException("Cannot read from a write-only stream");
    }

    @Override
    public void readFully(long[] l, int off, int len) throws IOException {
        throw new IOException("Cannot read from a write-only stream");
    }

    @Override
    public void readFully(float[] f, int off, int len) throws IOException {
        throw new IOException("Cannot read from a write-only stream");
    }

    @Override
    public void readFully(double[] d, int off, int len) throws IOException {
        throw new IOException("Cannot read from a write-only stream");
    }
}
