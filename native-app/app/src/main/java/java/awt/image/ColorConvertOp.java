package java.awt.image;

import java.awt.RenderingHints;
import java.awt.color.ColorSpace;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

public class ColorConvertOp implements BufferedImageOp {

    private final ColorSpace srcCspace;
    private final ColorSpace dstCspace;
    private final RenderingHints hints;

    public ColorConvertOp(ColorSpace cspace, RenderingHints hints) {
        this(null, cspace, hints);
    }

    public ColorConvertOp(ColorSpace srcCspace, ColorSpace dstCspace, RenderingHints hints) {
        this.srcCspace = srcCspace;
        this.dstCspace = dstCspace;
        this.hints = hints;
    }

    public ColorSpace getDestinationColorSpace() {
        return dstCspace;
    }

    public ColorSpace getSourceColorSpace() {
        return srcCspace;
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
        boolean toGray = dstCspace != null && dstCspace.getType() == ColorSpace.TYPE_GRAY;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = src.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (toGray) {
                    int r = (argb >> 16) & 0xFF;
                    int g = (argb >> 8) & 0xFF;
                    int b = argb & 0xFF;
                    int gray = Math.round(0.299f * r + 0.587f * g + 0.114f * b);
                    dst.setRGB(x, y, (a << 24) | (gray << 16) | (gray << 8) | gray);
                } else {
                    dst.setRGB(x, y, argb);
                }
            }
        }
        return dst;
    }
}