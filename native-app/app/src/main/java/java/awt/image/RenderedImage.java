package java.awt.image;

import java.awt.Rectangle;
import java.util.Vector;

/**
 * Stub mirroring java.awt.image.RenderedImage. On desktop the JAI/GeoTools
 * stack (and gama.extension.image's ImageIO.write) rely on this interface;
 * ART provides no such type, so the app supplies this minimal but API-complete
 * version backed by the BufferedImage stub.
 */
public interface RenderedImage {

    Raster getTile(int tileX, int tileY);

    Raster getData();

    Raster getData(Rectangle rect);

    WritableRaster copyData(WritableRaster raster);

    int getMinTileX();

    int getMaxTileX();

    int getMinTileY();

    int getMaxTileY();

    int getTileGridXOffset();

    int getTileGridYOffset();

    int getNumXTiles();

    int getNumYTiles();

    int getMinX();

    int getMinY();

    int getWidth();

    int getHeight();

    int getTileWidth();

    int getTileHeight();

    SampleModel getSampleModel();

    ColorModel getColorModel();

    Object getProperty(String name);

    String[] getPropertyNames();

    Vector<RenderedImage> getSources();

    default int getNumSources() {
        Vector<RenderedImage> sources = getSources();
        return sources == null ? 0 : sources.size();
    }
}
