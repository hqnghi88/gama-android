package javax.imageio;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import javax.imageio.stream.ImageInputStream;

public class ImageIO {

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
}
