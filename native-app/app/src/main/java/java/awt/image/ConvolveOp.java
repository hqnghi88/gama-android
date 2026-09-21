package java.awt.image;

import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

public class ConvolveOp implements BufferedImageOp {

    public static final int EDGE_ZERO_FILL = 0;
    public static final int EDGE_NO_OP = 1;

    private final Kernel kernel;
    private final int edgeCondition;
    private final RenderingHints hints;

    public ConvolveOp(Kernel kernel) {
        this(kernel, EDGE_NO_OP, null);
    }

    public ConvolveOp(Kernel kernel, int edgeCondition, RenderingHints hints) {
        this.kernel = kernel;
        this.edgeCondition = edgeCondition;
        this.hints = hints;
    }

    public Kernel getKernel() {
        return kernel;
    }

    public int getEdgeCondition() {
        return edgeCondition;
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
        double x = srcPt.getX() - kernel.getXOrigin();
        double y = srcPt.getY() - kernel.getYOrigin();
        if (dstPt == null) {
            return new Point2D.Float((float) x, (float) y);
        }
        dstPt.setLocation(x, y);
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
        if (dst.getWidth() != w || dst.getHeight() != h) {
            throw new ImagingOpException("ConvolveOp: destination dimensions differ from source");
        }
        int kw = kernel.getWidth();
        int kh = kernel.getHeight();
        int xo = kernel.getXOrigin();
        int yo = kernel.getYOrigin();
        float[] k = kernel.getKernelData(null);
        int maxX = w - 1;
        int maxY = h - 1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float r = 0, g = 0, b = 0;
                int idx = 0;
                for (int ky = 0; ky < kh; ky++) {
                    int sy = y + ky - yo;
                    if (sy < 0) sy = 0;
                    if (sy > maxY) sy = maxY;
                    for (int kx = 0; kx < kw; kx++, idx++) {
                        int sx = x + kx - xo;
                        if (sx < 0) sx = 0;
                        if (sx > maxX) sx = maxX;
                        int argb = src.getRGB(sx, sy);
                        r += k[idx] * ((argb >> 16) & 0xFF);
                        g += k[idx] * ((argb >> 8) & 0xFF);
                        b += k[idx] * (argb & 0xFF);
                    }
                }
                int a = (edgeCondition == EDGE_NO_OP) ? (src.getRGB(x, y) >>> 24) & 0xFF : 255;
                int rI = clamp(Math.round(r));
                int gI = clamp(Math.round(g));
                int bI = clamp(Math.round(b));
                dst.setRGB(x, y, (a << 24) | (rI << 16) | (gI << 8) | bI);
            }
        }
        return dst;
    }

    private static int clamp(int v) {
        if (v < 0) return 0;
        if (v > 255) return 255;
        return v;
    }
}