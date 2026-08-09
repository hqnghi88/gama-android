package javax.imageio;

import java.awt.image.BufferedImage;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.stream.ImageInputStream;

public abstract class ImageReader {
    protected ImageReader() {}
    public void setInput(Object input) {}
    public void setInput(Object input, boolean seekForwardOnly) {}
    public void addIIOReadProgressListener(IIOReadProgressListener listener) {}
    public void removeIIOReadProgressListener(IIOReadProgressListener listener) {}
    public BufferedImage read(int imageIndex) throws java.io.IOException {
        return null;
    }
    public int getNumImages(boolean allowSearch) throws java.io.IOException {
        return 0;
    }
}
