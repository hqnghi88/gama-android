package javax.imageio;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
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

        byte[] rawBytes = readFileBytes(file);
        byte[] decodedBytes = stripGammaChunk(rawBytes);

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
        Bitmap bitmap;
        if (decodedBytes != null) {
            bitmap = BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.length, opts);
        } else {
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath(), opts);
        }

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

    private static byte[] readFileBytes(File file) throws IOException {
        java.io.FileInputStream fis = new java.io.FileInputStream(file);
        byte[] data = new byte[(int) file.length()];
        try {
            int off = 0;
            while (off < data.length) {
                int n = fis.read(data, off, data.length - off);
                if (n < 0) break;
                off += n;
            }
        } finally {
            fis.close();
        }
        return data;
    }

    private static byte[] stripGammaChunk(byte[] png) {
        if (png.length < 8) return null;
        if (png[0] != (byte) 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') return null;

        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(png.length);
        out.write(png, 0, 8);
        int pos = 8;
        boolean stripped = false;
        while (pos + 8 <= png.length) {
            int len = ((png[pos] & 0xFF) << 24) | ((png[pos + 1] & 0xFF) << 16)
                    | ((png[pos + 2] & 0xFF) << 8) | (png[pos + 3] & 0xFF);
            int type = ((png[pos + 4] & 0xFF) << 24) | ((png[pos + 5] & 0xFF) << 16)
                    | ((png[pos + 6] & 0xFF) << 8) | (png[pos + 7] & 0xFF);
            int totalLen = 12 + len;
            if (pos + totalLen > png.length) return null;

            if (type == 0x67414D41) {
                stripped = true;
            } else {
                out.write(png, pos, totalLen);
            }
            pos += totalLen;
        }
        if (!stripped) return null;
        return out.toByteArray();
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
