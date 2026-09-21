package java.awt.image;

import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

public class RescaleOp implements BufferedImageOp {

    private final float scaleFactor;
    private final float offset;
    private final RenderingHints hints;

    public RescaleOp(float scaleFactor, float offset, RenderingHints hints) {
        this.scaleFactor = scaleFactor;
        this.offset = offset;
        this.hints = hints;
    }

    public RescaleOp(float scaleFactor, float offset) {
        this(scaleFactor, offset, null);
    }

    public float getScaleFactor() {
        return scaleFactor;
    }

    public float getOffset() {
        return offset;
    }

    @Override
    public RenderingHints getRenderingHints() {
        return hints;
    }

    @Override
    public Rectangle2D getBounds2D(BufferedImage src) {
        return new Rectangle2D.Float(0, 0, src.getWidth(), src.getHeight());
    }

    @Override
    public Point2D getPoint2D(Point2D srcPt, Point2D dstPt) {
        if (dstPt == null) {
            return (Point2D) srcPt.clone();
        }
        dstPt.setLocation(srcPt.getX(), srcPt.getY());
        return dstPt;
    }

    @Override
    public BufferedImage createCompatibleDestImage(BufferedImage src, ColorModel destCM) {
        return new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
    }

    @Override
    public BufferedImage filter(BufferedImage src, BufferedImage dst) {
        if (src == null) {
            throw new NullPointerException("src image is null");
        }
        int w = src.getWidth();
        int h = src.getHeight();
        if (dst == null) {
            dst = createCompatibleDestImage(src, null);
        }
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = clamp(scaleFactor * ((argb >> 16) & 0xFF) + offset);
                int g = clamp(scaleFactor * ((argb >> 8) & 0xFF) + offset);
                int b = clamp(scaleFactor * (argb & 0xFF) + offset);
                dst.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return dst;
    }

    private static int clamp(float v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return Math.round(v);
    }
}