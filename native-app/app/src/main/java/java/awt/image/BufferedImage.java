package java.awt.image;

import android.graphics.Bitmap;

import java.awt.CanvasGraphics2D;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Transparency;
import java.util.Vector;

public class BufferedImage extends Image implements Transparency, RenderedImage {
    public static final int TYPE_INT_RGB = 1;
    public static final int TYPE_INT_ARGB = 2;
    public static final int TYPE_INT_ARGB_PRE = 3;
    public static final int TYPE_INT_BGR = 4;
    public static final int TYPE_3BYTE_BGR = 5;
    public static final int TYPE_4BYTE_ABGR = 6;
    public static final int TYPE_4BYTE_ABGR_PRE = 7;
    public static final int TYPE_USHORT_565_RGB = 8;
    public static final int TYPE_USHORT_555_RGB = 9;
    public static final int TYPE_BYTE_GRAY = 10;
    public static final int TYPE_USHORT_GRAY = 11;
    public static final int TYPE_BYTE_BINARY = 12;
    public static final int TYPE_BYTE_INDEXED = 13;

    private int width;
    private int height;
    private int type;
    private int[] data;
    private ColorModel colorModel;
    private WritableRaster raster;
    private Bitmap androidBitmap;
    // true once the image's Graphics2D (a CanvasGraphics2D) has actually drawn onto
    // androidBitmap. For such images the androidBitmap is the source of truth and the
    // int[] data[] is stale, so image -> bitmap extraction must sync bitmap->data
    // (and never the reverse, which would clobber the rendered chart).
    private boolean graphicsDrawn = false;

    public BufferedImage(int width, int height, int imageType) {
        this.width = width;
        this.height = height;
        this.type = imageType;
        this.data = new int[width * height];
        this.colorModel = new DirectColorModel(32, 0xFF0000, 0xFF00, 0xFF, 0xFF000000);
        this.raster = new WritableRaster(new DataBufferInt(this.data, width * height));
        this.androidBitmap = Bitmap.createBitmap(width > 0 ? width : 1, height > 0 ? height : 1, Bitmap.Config.ARGB_8888);
    }

    public BufferedImage(int width, int height, int imageType, IndexColorModel cm) {
        this(width, height, imageType);
    }

    public BufferedImage(ColorModel cm, WritableRaster raster, boolean isRasterPremultiplied, java.util.Hashtable<?,?> properties) {
        this.width = raster.getWidth();
        this.height = raster.getHeight();
        this.type = TYPE_INT_ARGB;
        this.raster = raster;
        this.data = ((DataBufferInt) raster.getDataBuffer()).getData();
        this.colorModel = cm;
        this.androidBitmap = Bitmap.createBitmap(width > 0 ? width : 1, height > 0 ? height : 1, Bitmap.Config.ARGB_8888);
    }

    @Override public int getWidth(ImageObserver observer) { return width; }
    @Override public int getHeight(ImageObserver observer) { return height; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getType() { return type; }

    @Override
    public Object getProperty(String name, ImageObserver observer) { return null; }
    public Object getProperty(String name) { return null; }
    public String[] getPropertyNames() { return new String[0]; }

    @Override
    public ColorModel getColorModel() { return colorModel != null ? colorModel : new DirectColorModel(32, 0xFF0000, 0xFF00, 0xFF, 0xFF000000); }
    public ColorModel getColorModel(int x, int y, int w, int h) { return getColorModel(); }

    @Override
    public WritableRaster getRaster() { return raster; }

    @Override
    public Graphics getGraphics() { return createGraphics(); }

    public Graphics2D createGraphics() {
        if (androidBitmap != null) {
            return new CanvasGraphics2D(androidBitmap, this);
        }
        return new Graphics2D();
    }

    public void markGraphicsDrawn() { graphicsDrawn = true; }
    public boolean isGraphicsDrawn() { return graphicsDrawn; }

    @Override
    public void flush() {
        if (androidBitmap != null) {
            androidBitmap.recycle();
            androidBitmap = null;
        }
    }

    @Override
    public int getTransparency() { return colorModel != null ? colorModel.getTransparency() : Transparency.TRANSLUCENT; }

    public int getMinX() { return 0; }
    public int getMinY() { return 0; }

    public Bitmap getAndroidBitmap() { return androidBitmap; }

    public void syncBitmapToData() {
        if (androidBitmap != null && data != null) {
            androidBitmap.getPixels(data, 0, width, 0, 0, width, height);
        }
    }

    public void syncDataToBitmap() {
        if (androidBitmap != null && data != null) {
            androidBitmap.setPixels(data, 0, width, 0, 0, width, height);
        }
    }

    public int getRGB(int x, int y) {
        if (x >= 0 && x < width && y >= 0 && y < height) {
            // For images whose pixels were written to the data[] raster (GIF frames, grid cell
            // buffers, BufferedImage.setRGB), data[] is authoritative and androidBitmap is a stale,
            // empty copy — reading it would turn real content transparent. Only trust androidBitmap
            // when a CanvasGraphics2D has actually drawn onto it (graphicsDrawn).
            if (graphicsDrawn && androidBitmap != null && !androidBitmap.isRecycled()) {
                return androidBitmap.getPixel(x, y);
            }
            return data[y * width + x];
        }
        return 0;
    }

    public void setRGB(int x, int y, int rgb) {
        if (x >= 0 && x < width && y >= 0 && y < height) {
            if (androidBitmap != null) {
                androidBitmap.setPixel(x, y, rgb);
            }
            data[y * width + x] = rgb;
        }
    }

    public void setRGB(int startX, int startY, int w, int h, int[] rgbArray, int offset, int scansize) {
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                int idxx = startX + col;
                int idx = startY + row;
                if (idxx >= 0 && idxx < width && idx >= 0 && idx < height) {
                    int rgb = rgbArray[offset + row * scansize + col];
                    if (androidBitmap != null) {
                        androidBitmap.setPixel(idxx, idx, rgb);
                    }
                    data[idx * width + idxx] = rgb;
                }
            }
        }
    }

    public int[] getRGB(int startX, int startY, int w, int h, int[] rgbArray, int offset, int scansize) {
        if (rgbArray == null) rgbArray = new int[w * h];
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                rgbArray[offset + row * scansize + col] = getRGB(startX + col, startY + row);
            }
        }
        return rgbArray;
    }

    @Override
    public Raster getTile(int tileX, int tileY) { return raster; }

    @Override
    public Raster getData() { return raster; }

    @Override
    public Raster getData(Rectangle rect) { return raster; }

    @Override
    public WritableRaster copyData(WritableRaster outRaster) { return outRaster; }

    @Override
    public int getMinTileX() { return 0; }

    @Override
    public int getMaxTileX() { return 0; }

    @Override
    public int getMinTileY() { return 0; }

    @Override
    public int getMaxTileY() { return 0; }

    @Override
    public int getTileGridXOffset() { return getMinX(); }

    @Override
    public int getTileGridYOffset() { return getMinY(); }

    @Override
    public int getNumXTiles() { return 1; }

    @Override
    public int getNumYTiles() { return 1; }

    @Override
    public int getTileWidth() { return width; }

    @Override
    public int getTileHeight() { return height; }

    @Override
    public SampleModel getSampleModel() { return raster != null ? raster.getSampleModel() : null; }

    @Override
    public Vector<RenderedImage> getSources() { return new Vector<>(); }
}
