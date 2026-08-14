package javax.imageio;

import android.graphics.Bitmap;

import java.awt.image.BufferedImage;
import java.awt.image.RenderedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;

public class ImageIO {

    private static boolean useCache = false;

    public static boolean getUseCache() {
        return useCache;
    }

    public static void setUseCache(boolean useCache) {
        ImageIO.useCache = useCache;
    }

    public static File getCacheDirectory() {
        return null;
    }

    public static ImageOutputStream createImageOutputStream(Object output) {
        if (output instanceof File) {
            return new FileImageOutputStream((File) output);
        }
        return null;
    }

    public static Iterator<ImageReader> getImageReaders(Object input) {
        return getImageReadersBySuffix("");
    }

    public static Iterator<ImageReader> getImageReadersBySuffix(String fileSuffix) {
        List<ImageReader> readers = new ArrayList<>();
        readers.add(new AndroidImageReader());
        return readers.iterator();
    }

    public static Iterator<ImageReader> getImageReadersByFormatName(String formatName) {
        List<ImageReader> readers = new ArrayList<>();
        readers.add(new AndroidImageReader());
        return readers.iterator();
    }

    public static ImageInputStream createImageInputStream(Object input) {
        if (input instanceof File) {
            return new FileImageInputStream((File) input);
        }
        return null;
    }

    public static boolean write(RenderedImage im, String formatName, File output) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(output)) {
            return write(im, formatName, fos);
        }
    }

    public static boolean write(RenderedImage im, String formatName, OutputStream output) throws IOException {
        if (im instanceof BufferedImage) {
            BufferedImage bi = (BufferedImage) im;
            Bitmap bmp = bi.getAndroidBitmap();
            if (bmp != null && !bmp.isRecycled()) {
                Bitmap.CompressFormat fmt = isJpeg(formatName) ? Bitmap.CompressFormat.JPEG
                        : Bitmap.CompressFormat.PNG;
                return bmp.compress(fmt, 100, output);
            }
        }
        return false;
    }

    private static boolean isJpeg(String formatName) {
        return formatName != null
                && ("jpg".equalsIgnoreCase(formatName) || "jpeg".equalsIgnoreCase(formatName));
    }
}
