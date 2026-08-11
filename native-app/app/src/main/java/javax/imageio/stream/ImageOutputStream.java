package javax.imageio.stream;

public interface ImageOutputStream extends ImageInputStream {
    void write(int b) throws java.io.IOException;
    void write(byte[] b, int off, int len) throws java.io.IOException;
    void flush() throws java.io.IOException;
}
