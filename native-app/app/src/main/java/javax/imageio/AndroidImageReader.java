package javax.imageio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.event.IIOReadProgressListener;
import javax.imageio.stream.ImageInputStream;

public class AndroidImageReader extends ImageReader {

    private FileImageInputStream imageInput;
    private final List<IIOReadProgressListener> listeners = new ArrayList<>();

    @Override
    public void setInput(Object input) {
        setInput(input, false);
    }

    @Override
    public void setInput(Object input, boolean seekForwardOnly) {
        if (input instanceof FileImageInputStream) {
            this.imageInput = (FileImageInputStream) input;
        }
    }

    @Override
    public void addIIOReadProgressListener(IIOReadProgressListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeIIOReadProgressListener(IIOReadProgressListener listener) {
        listeners.remove(listener);
    }

    @Override
    public BufferedImage read(int imageIndex) throws IOException {
        if (imageInput == null) {
            throw new IOException("No input source set");
        }
        File file = imageInput.getFile();
        if (file == null || !file.exists()) {
            throw new IOException("File not found: " + file);
        }

        notifyImageStarted();

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);

        if (bitmap == null) {
            throw new IOException("Failed to decode image: " + file.getName());
        }

        notifyImageProgress(50f);

        int w = bitmap.getWidth();
        int h = bitmap.getHeight();
        int[] pixels = new int[w * h];
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h);
        bitmap.recycle();

        BufferedImage image = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, pixels, 0, w);

        notifyImageProgress(100f);
        notifyImageComplete();

        return image;
    }

    @Override
    public int getNumImages(boolean allowSearch) {
        return 1;
    }

    private void notifyImageStarted() {
        for (IIOReadProgressListener l : listeners) {
            l.imageStarted(this, 0);
        }
    }

    private void notifyImageProgress(float pct) {
        for (IIOReadProgressListener l : listeners) {
            l.imageProgress(this, pct);
        }
    }

    private void notifyImageComplete() {
        for (IIOReadProgressListener l : listeners) {
            l.imageComplete(this);
        }
    }
}
