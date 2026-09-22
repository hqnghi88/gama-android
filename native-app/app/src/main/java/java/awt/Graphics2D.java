package java.awt;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.BufferedImageOp;
import java.awt.image.ColorModel;
import java.awt.image.ImageObserver;

/**
 * Functional java.awt.Graphics2D base class shimmed for Android.
 *
 * The real class comes from the desktop JDK. On Android, {@link CanvasGraphics2D}
 * (an Android Bitmap-backed implementation in this package) is the concrete subclass;
 * this base declares the full standard API so code compiled against JDK (notably
 * gama.extension.image's ImageHelper/ImageOperators) links and dispatches. Methods not
 * implemented by the concrete subclass degrade to safe no-ops instead of throwing.
 */
public abstract class Graphics2D extends Graphics {

    public static final int RENDER_HINT_PARAM_A = 0;
    public static final int RENDER_HINT_PARAM_B = 1;

    public Graphics2D() {}

    @Override public void dispose() {}
    @Override public Graphics create() { return this; }
    @Override public Graphics create(int x, int y, int width, int height) { return this; }

    public void draw(Shape s) {}
    public void fill(Shape s) {}

    public void drawString(String str, int x, int y) {}
    public void drawString(String str, float x, float y) {}
    public void drawString(java.text.AttributedString as, int x, int y) {}
    public void drawString(java.text.AttributedString as, float x, float y) {}

    @Override public void drawLine(int x1, int y1, int x2, int y2) {}
    @Override public void fillRect(int x, int y, int width, int height) {}
    @Override public void drawRect(int x, int y, int width, int height) {}
    @Override public void clearRect(int x, int y, int width, int height) {}
    @Override public void fillOval(int x, int y, int width, int height) {}
    @Override public void drawOval(int x, int y, int width, int height) {}
    @Override public void fillArc(int x, int y, int width, int height, int startAngle, int arcAngle) {}
    @Override public void drawArc(int x, int y, int width, int height, int startAngle, int arcAngle) {}
    @Override public void fillRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {}
    @Override public void drawRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {}
    @Override public void drawPolygon(int[] xPoints, int[] yPoints, int nPoints) {}
    @Override public void fillPolygon(int[] xPoints, int[] yPoints, int nPoints) {}
    @Override public void drawPolyline(int[] xPoints, int[] yPoints, int nPoints) {}
    @Override public void fill3DRect(int x, int y, int width, int height, boolean raised) {}
    @Override public void draw3DRect(int x, int y, int width, int height, boolean raised) {}
    @Override public void copyArea(int x, int y, int width, int height, int dx, int dy) {}

    public void setRenderingHint(RenderingHints.Key hintKey, Object hintValue) {}
    public Object getRenderingHint(RenderingHints.Key hintKey) { return null; }
    public void setRenderingHints(java.util.Map<?, ?> hints) {}
    public void addRenderingHints(java.util.Map<?, ?> hints) {}
    public RenderingHints getRenderingHints() { return new RenderingHints((java.util.Map<RenderingHints.Key, Object>) null); }

    @Override public void setColor(Color c) {}
    @Override public Color getColor() { return Color.BLACK; }
    public void setBackground(Color color) {}
    public Color getBackground() { return Color.WHITE; }
    @Override public void setFont(Font font) {}
    @Override public Font getFont() { return Font.DIALOG; }
    @Override public FontMetrics getFontMetrics(Font f) { return new FontMetrics(f != null ? f : Font.DIALOG); }
    public FontMetrics getFontMetrics() { return getFontMetrics(getFont()); }

    public void setStroke(Stroke s) {}
    public Stroke getStroke() { return new BasicStroke(); }
    public void setComposite(Composite comp) {}
    public Composite getComposite() { return AlphaComposite.SrcOver; }
    public void setPaint(Paint paint) {}
    public Paint getPaint() { return Color.BLACK; }
    public void setPaintMode() {}
    public void setXORMode(Color c1) {}

    public void setTransform(AffineTransform Tx) {}
    public AffineTransform getTransform() { return new AffineTransform(); }
    public void transform(AffineTransform Tx) {}
    public void translate(int x, int y) {}
    public void translate(double tx, double ty) {}
    public void rotate(double theta) {}
    public void rotate(double theta, double x, double y) {}
    public void scale(double sx, double sy) {}
    public void shear(double shx, double shy) {}

    public void clip(Shape s) {}
    @Override public Shape getClip() { return null; }
    @Override public void setClip(Shape clip) {}
    public void setClip(int x, int y, int width, int height) {}
    public void clipRect(int x, int y, int width, int height) {}
    @Override public Rectangle getClipBounds() { return new Rectangle(); }
    public Rectangle getClipBounds(Rectangle r) { return getClipBounds(); }

    public boolean hit(Rectangle rect, Shape s, boolean onStroke) { return false; }
    @Override public Rectangle getBounds() { return new Rectangle(); }
    public GraphicsConfiguration getDeviceConfiguration() {
        return new GraphicsConfiguration();
    }
    public ColorModel getColorModel() {
        return new java.awt.image.DirectColorModel(32, 0xFF0000, 0xFF00, 0xFF, 0xFF000000);
    }

    @Override public boolean drawImage(Image img, int x, int y, ImageObserver observer) { return false; }
    @Override public boolean drawImage(Image img, int x, int y, int width, int height, ImageObserver observer) { return false; }
    public boolean drawImage(Image img, int x, int y, Color bgcolor, ImageObserver observer) { return false; }
    public boolean drawImage(Image img, int x, int y, int width, int height, Color bgcolor, ImageObserver observer) { return false; }
    public boolean drawImage(Image img, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, ImageObserver observer) { return false; }
    public boolean drawImage(Image img, AffineTransform xform, ImageObserver observer) { return false; }
    public void drawImage(BufferedImage img, BufferedImageOp op, int x, int y) {}

    public BufferedImage createCompatibleImage(int width, int height, int transparency) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }
    public BufferedImage createCompatibleImage(int width, int height) {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
    }
}