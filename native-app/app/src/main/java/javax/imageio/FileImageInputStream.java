package javax.imageio;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteOrder;
import javax.imageio.stream.ImageInputStream;

public class FileImageInputStream implements ImageInputStream {

    private final File file;
    private final InputStream inputStream;
    private long position = 0;
    private ByteOrder byteOrder = ByteOrder.BIG_ENDIAN;

    public FileImageInputStream(File file) {
        this.file = file;
        try {
            this.inputStream = new FileInputStream(file);
        } catch (IOException e) {
            throw new RuntimeException("Cannot open file: " + file, e);
        }
    }

    public File getFile() {
        return file;
    }

    @Override
    public void close() throws IOException {
        inputStream.close();
    }

    @Override
    public boolean isCached() {
        return false;
    }

    @Override
    public void seek(long pos) throws IOException {
        if (pos < position) {
            inputStream.close();
            InputStream newStream = new FileInputStream(file);
            long skip = newStream.skip(pos);
            // Re-assign via reflection or just track position
            position = skip;
        } else {
            long toSkip = pos - position;
            long skipped = 0;
            while (skipped < toSkip) {
                long s = inputStream.skip(toSkip - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            position += skipped;
        }
    }

    @Override
    public long getStreamPosition() {
        return position;
    }

    @Override
    public void setByteOrder(ByteOrder byteOrder) {
        this.byteOrder = byteOrder;
    }

    @Override
    public ByteOrder getByteOrder() {
        return byteOrder;
    }

    @Override
    public int readUnsignedByte() throws IOException {
        int b = inputStream.read();
        if (b < 0) throw new IOException("EOF");
        position++;
        return b;
    }

    @Override
    public int readUnsignedShort() throws IOException {
        int b1 = readUnsignedByte();
        int b2 = readUnsignedByte();
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            return (b1 << 8) | b2;
        } else {
            return b1 | (b2 << 8);
        }
    }

    @Override
    public int readInt() throws IOException {
        int b1 = readUnsignedByte();
        int b2 = readUnsignedByte();
        int b3 = readUnsignedByte();
        int b4 = readUnsignedByte();
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            return (b1 << 24) | (b2 << 16) | (b3 << 8) | b4;
        } else {
            return b1 | (b2 << 8) | (b3 << 16) | (b4 << 24);
        }
    }

    @Override
    public long readLong() throws IOException {
        long hi = readInt() & 0xFFFFFFFFL;
        long lo = readInt() & 0xFFFFFFFFL;
        if (byteOrder == ByteOrder.BIG_ENDIAN) {
            return (hi << 32) | lo;
        } else {
            return lo | (hi << 32);
        }
    }

    @Override
    public float readFloat() throws IOException {
        return Float.intBitsToFloat(readInt());
    }

    @Override
    public double readDouble() throws IOException {
        return Double.longBitsToDouble(readLong());
    }

    @Override
    public void readFully(byte[] b, int off, int len) throws IOException {
        int totalRead = 0;
        while (totalRead < len) {
            int n = inputStream.read(b, off + totalRead, len - totalRead);
            if (n < 0) throw new IOException("EOF");
            totalRead += n;
        }
        position += len;
    }

    @Override
    public void readFully(char[] c, int off, int len) throws IOException {
        byte[] buf = new byte[len];
        readFully(buf, 0, len);
        for (int i = 0; i < len; i++) {
            c[off + i] = (char) (buf[i] & 0xFF);
        }
    }

    @Override
    public void readFully(short[] s, int off, int len) throws IOException {
        byte[] buf = new byte[len * 2];
        readFully(buf, 0, len * 2);
        for (int i = 0; i < len; i++) {
            int idx = i * 2;
            if (byteOrder == ByteOrder.BIG_ENDIAN) {
                s[off + i] = (short) ((buf[idx] << 8) | (buf[idx + 1] & 0xFF));
            } else {
                s[off + i] = (short) ((buf[idx] & 0xFF) | (buf[idx + 1] << 8));
            }
        }
    }

    @Override
    public void readFully(int[] i, int off, int len) throws IOException {
        byte[] buf = new byte[len * 4];
        readFully(buf, 0, len * 4);
        for (int idx = 0; idx < len; idx++) {
            int bIdx = idx * 4;
            if (byteOrder == ByteOrder.BIG_ENDIAN) {
                i[off + idx] = (buf[bIdx] << 24) | ((buf[bIdx + 1] & 0xFF) << 16)
                        | ((buf[bIdx + 2] & 0xFF) << 8) | (buf[bIdx + 3] & 0xFF);
            } else {
                i[off + idx] = (buf[bIdx] & 0xFF) | ((buf[bIdx + 1] & 0xFF) << 8)
                        | ((buf[bIdx + 2] & 0xFF) << 16) | (buf[bIdx + 3] << 24);
            }
        }
    }

    @Override
    public void readFully(long[] l, int off, int len) throws IOException {
        byte[] buf = new byte[len * 8];
        readFully(buf, 0, len * 8);
        for (int idx = 0; idx < len; idx++) {
            int bIdx = idx * 8;
            long hi, lo;
            if (byteOrder == ByteOrder.BIG_ENDIAN) {
                hi = ((long) buf[bIdx] << 56) | ((long) (buf[bIdx + 1] & 0xFF) << 48)
                        | ((long) (buf[bIdx + 2] & 0xFF) << 40) | ((long) (buf[bIdx + 3] & 0xFF) << 32);
                lo = ((long) (buf[bIdx + 4] & 0xFF) << 24) | ((long) (buf[bIdx + 5] & 0xFF) << 16)
                        | ((long) (buf[bIdx + 6] & 0xFF) << 8) | (buf[bIdx + 7] & 0xFF);
            } else {
                lo = (buf[bIdx] & 0xFF) | ((long) (buf[bIdx + 1] & 0xFF) << 8)
                        | ((long) (buf[bIdx + 2] & 0xFF) << 16) | ((long) buf[bIdx + 3] << 24);
                hi = ((long) (buf[bIdx + 4] & 0xFF)) | ((long) (buf[bIdx + 5] & 0xFF) << 8)
                        | ((long) (buf[bIdx + 6] & 0xFF) << 16) | ((long) buf[bIdx + 7] << 24);
            }
            l[off + idx] = (hi << 32) | lo;
        }
    }

    @Override
    public void readFully(float[] f, int off, int len) throws IOException {
        int[] buf = new int[len];
        readFully(buf, 0, len);
        for (int i = 0; i < len; i++) {
            f[off + i] = Float.intBitsToFloat(buf[i]);
        }
    }

    @Override
    public void readFully(double[] d, int off, int len) throws IOException {
        long[] buf = new long[len];
        readFully(buf, 0, len);
        for (int i = 0; i < len; i++) {
            d[off + i] = Double.longBitsToDouble(buf[i]);
        }
    }
}
