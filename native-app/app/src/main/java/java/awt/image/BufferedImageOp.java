package java.awt.image;

import java.awt.RenderingHints;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

public interface BufferedImageOp {

    BufferedImage filter(BufferedImage src, BufferedImage dst);

    Rectangle2D getBounds2D(BufferedImage src);

    BufferedImage createCompatibleDestImage(BufferedImage src, ColorModel destCM);

    Point2D getPoint2D(Point2D srcPt, Point2D dstPt);

    RenderingHints getRenderingHints();
}