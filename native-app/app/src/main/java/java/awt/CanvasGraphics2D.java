package java.awt;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.Typeface;

import java.awt.geom.AffineTransform;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;

public class CanvasGraphics2D extends Graphics2D {
    private final Bitmap bitmap;
    private final Canvas canvas;
    private final Paint fillPaint;
    private final Paint strokePaint;
    private final Paint textPaint;
    private final Matrix currentMatrix;
    private final Matrix savedMatrix;
    private final java.awt.image.BufferedImage ownerImage;
    private AffineTransform awtTransform;
    private Shape clipShape;
    private Color currentColor = Color.BLACK;
    private Font currentFont = Font.DIALOG;
    private float strokeWidth = 1f;
    private boolean antialias = true;
    private java.awt.Composite composite;

    public CanvasGraphics2D(Bitmap bitmap) {
        this(bitmap, null);
    }

    public CanvasGraphics2D(Bitmap bitmap, java.awt.image.BufferedImage ownerImage) {
        this.bitmap = bitmap;
        this.ownerImage = ownerImage;
        this.canvas = new Canvas(bitmap);
        this.fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.fillPaint.setStyle(Paint.Style.FILL);
        this.strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.strokePaint.setStyle(Paint.Style.STROKE);
        this.strokePaint.setStrokeWidth(1f);
        this.textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        this.textPaint.setTextSize(12f);
        this.currentMatrix = new Matrix();
        this.savedMatrix = new Matrix();
        this.awtTransform = new AffineTransform();
        canvas.drawColor(android.graphics.Color.WHITE);
    }

    private android.graphics.Color toAwtAndroidColor(Color c) {
        return android.graphics.Color.valueOf(c.getRed() / 255f, c.getGreen() / 255f, c.getBlue() / 255f, c.getAlpha() / 255f);
    }

    private int toArgb(Color c) {
        return android.graphics.Color.argb(c.getAlpha(), c.getRed(), c.getGreen(), c.getBlue());
    }

    private void applyColorToPaint(Paint p, Color c) {
        p.setColor(toArgb(c));
    }

    @Override
    public void setColor(Color c) {
        if (c == null) return;
        currentColor = c;
        fillPaint.setColor(toArgb(c));
        strokePaint.setColor(toArgb(c));
        textPaint.setColor(toArgb(c));
    }

    @Override
    public Color getColor() { return currentColor; }

    @Override
    public void setPaint(java.awt.Paint paint) {
        if (paint instanceof Color c) {
            trace("setPaint:Color");
            setColor(c);
        } else {
            trace("setPaint:" + (paint != null ? paint.getClass().getSimpleName() : "null"));
        }
        if (callCount < logCap) android.util.Log.w("AWT_CHART", "     color=" + Integer.toHexString(toArgb(paint instanceof Color c ? c : currentColor)));
    }

    @Override
    public void setFont(Font font) {
        trace("setFont");
        if (font == null) return;
        currentFont = font;
        textPaint.setTextSize(font.getSize());
        int style = 0;
        if (font.isBold()) style |= Typeface.BOLD;
        if (font.isItalic()) style |= Typeface.ITALIC;
        textPaint.setTypeface(Typeface.create(font.getName(), style));
    }

    @Override
    public Font getFont() { return currentFont; }

    @Override
    public void setStroke(Stroke s) {
        trace("setStroke");
        if (s instanceof BasicStroke) {
            strokeWidth = 1f;
            strokePaint.setStrokeWidth(strokeWidth);
        }
    }

    @Override
    public Stroke getStroke() { return new BasicStroke(strokeWidth); }

    @Override
    public void setRenderingHint(RenderingHints.Key key, Object value) {
        trace("setHint");
        if (key == RenderingHints.KEY_ANTIALIASING ||
            key == RenderingHints.KEY_TEXT_ANTIALIASING) {
            antialias = (value != RenderingHints.VALUE_ANTIALIAS_OFF && value != RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            fillPaint.setAntiAlias(antialias);
            strokePaint.setAntiAlias(antialias);
            textPaint.setAntiAlias(antialias);
        }
    }

    @Override
    public Object getRenderingHint(RenderingHints.Key key) {
        if (key == RenderingHints.KEY_ANTIALIASING) {
            return antialias ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF;
        }
        return null;
    }

    @Override
    public void setRenderingHints(java.util.Map<?, ?> hints) {}

    @Override
    public void addRenderingHints(java.util.Map<?, ?> hints) {}

    @Override
    public RenderingHints getRenderingHints() { return new RenderingHints(null); }

    @Override
    public void setComposite(java.awt.Composite comp) { 
        trace("setComposite:" + (comp != null ? comp.getClass().getSimpleName() : "null"));
        this.composite = comp; 
    }

    public java.awt.Composite getComposite() { trace("getComposite"); return composite; }

    @Override
    public void setBackground(Color color) {}

    @Override
    public Color getBackground() { return Color.WHITE; }

    private int fillCount = 0;
    private int callCount = 0;
    private int logCap = 60;
    private void markDrawn() {
        if (ownerImage != null) ownerImage.markGraphicsDrawn();
    }

    private int argb(Paint p) {
        if (p == null) return 0;
        try { return p.getColor(); } catch (Throwable t) { return -1; }
    }

    private void trace(String method) {
        if (callCount < 500) {
            android.util.Log.w("AWT_CHART", callCount + " " + method);
        }
        callCount++;
    }

    private String hex(int c) { return Integer.toHexString(c); }

    @Override
    public void fill(Shape s) {
        if (s == null) return;
        markDrawn();
        trace("fill:" + s.getClass().getSimpleName());
        if (callCount < 60) android.util.Log.w("AWT_CHART", "  fillColor=" + hex(fillPaint.getColor()) + " shape=" + s.getClass().getSimpleName() + " owner=" + (ownerImage == null ? "null" : System.identityHashCode(ownerImage)));
        try {
            if (s instanceof Arc2D a) {
                android.util.Log.w("AWT_CHART", "  Arc2D: cx=" + a.getCenterX() + " cy=" + a.getCenterY() +
                    " w=" + a.getWidth() + " h=" + a.getHeight() +
                    " start=" + a.getAngleStart() + " extent=" + a.getAngleExtent() +
                    " type=" + a.getArcType());
            }
            Path path = shapeToPath(s);
            if (path != null) {
                canvas.drawPath(path, fillPaint);
            }
        } catch (Exception e) {
            android.util.Log.e("AWT_CHART", "fill FAILED: " + e);
        }
    }

    @Override
    public void draw(Shape s) {
        if (s == null) return;
        markDrawn();
        trace("draw:" + s.getClass().getSimpleName());
        if (callCount < 60) android.util.Log.w("AWT_CHART", "  drawColor=" + hex(strokePaint.getColor()) + " shape=" + s.getClass().getSimpleName() + " owner=" + (ownerImage == null ? "null" : System.identityHashCode(ownerImage)));
        try {
            Path path = shapeToPath(s);
            if (path != null) {
                canvas.drawPath(path, strokePaint);
            }
        } catch (Throwable e) {
            android.util.Log.e("AWT_CHART", "draw FAILED for " + s.getClass().getSimpleName() + ": " + e);
        }
    }

    @Override
    public void drawString(String str, float x, float y) {
        if (str == null || str.isEmpty()) return;
        markDrawn();
        trace("drawStr:" + str);
        canvas.drawText(str, x, y, textPaint);
    }

    @Override
    public void drawString(String str, int x, int y) {
        if (str == null) return;
        markDrawn();
        canvas.drawText(str, (float) x, (float) y, textPaint);
    }

    @Override
    public void drawString(java.text.AttributedString as, float x, float y) {
        if (as == null) return;
        markDrawn();
        canvas.drawText(as.toString(), x, y, textPaint);
    }

    @Override
    public void drawLine(int x1, int y1, int x2, int y2) {
        markDrawn();
        canvas.drawLine(x1, y1, x2, y2, strokePaint);
    }

    @Override
    public void fillRect(int x, int y, int width, int height) {
        markDrawn();
        canvas.drawRect(x, y, x + width, y + height, fillPaint);
    }

    @Override
    public void drawRect(int x, int y, int width, int height) {
        markDrawn();
        canvas.drawRect(x, y, x + width, y + height, strokePaint);
    }

    @Override
    public void clearRect(int x, int y, int width, int height) {
        markDrawn();
        Paint clearPaint = new Paint();
        clearPaint.setColor(android.graphics.Color.WHITE);
        clearPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(x, y, x + width, y + height, clearPaint);
    }

    @Override
    public void fillOval(int x, int y, int width, int height) {
        markDrawn();
        canvas.drawOval(x, y, x + width, y + height, fillPaint);
    }

    @Override
    public void drawOval(int x, int y, int width, int height) {
        markDrawn();
        canvas.drawOval(x, y, x + width, y + height, strokePaint);
    }

    @Override
    public void fillArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
        markDrawn();
        Path path = new Path();
        RectF oval = new RectF(x, y, x + width, y + height);
        path.moveTo(x + width / 2f, y + height / 2f);
        path.arcTo(oval, -startAngle, -arcAngle);
        path.close();
        canvas.drawPath(path, fillPaint);
    }

    @Override
    public void drawArc(int x, int y, int width, int height, int startAngle, int arcAngle) {
        markDrawn();
        RectF oval = new RectF(x, y, x + width, y + height);
        canvas.drawArc(oval, -startAngle, -arcAngle, false, strokePaint);
    }

    @Override
    public void fillRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
        markDrawn();
        RectF rect = new RectF(x, y, x + width, y + height);
        canvas.drawRoundRect(rect, arcWidth / 2f, arcHeight / 2f, fillPaint);
    }

    @Override
    public void drawRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {
        markDrawn();
        RectF rect = new RectF(x, y, x + width, y + height);
        canvas.drawRoundRect(rect, arcWidth / 2f, arcHeight / 2f, strokePaint);
    }

    @Override
    public void drawPolygon(int[] xPoints, int[] yPoints, int nPoints) {
        if (nPoints < 2) return;
        markDrawn();
        Path path = new Path();
        path.moveTo(xPoints[0], yPoints[0]);
        for (int i = 1; i < nPoints; i++) {
            path.lineTo(xPoints[i], yPoints[i]);
        }
        path.close();
        canvas.drawPath(path, strokePaint);
    }

    @Override
    public void fillPolygon(int[] xPoints, int[] yPoints, int nPoints) {
        if (nPoints < 2) return;
        markDrawn();
        Path path = new Path();
        path.moveTo(xPoints[0], yPoints[0]);
        for (int i = 1; i < nPoints; i++) {
            path.lineTo(xPoints[i], yPoints[i]);
        }
        path.close();
        canvas.drawPath(path, fillPaint);
    }

    @Override
    public void drawPolyline(int[] xPoints, int[] yPoints, int nPoints) {
        if (nPoints < 2) return;
        markDrawn();
        Path path = new Path();
        path.moveTo(xPoints[0], yPoints[0]);
        for (int i = 1; i < nPoints; i++) {
            path.lineTo(xPoints[i], yPoints[i]);
        }
        canvas.drawPath(path, strokePaint);
    }

    @Override
    public void translate(int x, int y) {
        canvas.translate(x, y);
        awtTransform.translate(x, y);
    }

    @Override
    public void translate(double tx, double ty) {
        canvas.translate((float) tx, (float) ty);
        awtTransform.translate(tx, ty);
    }

    @Override
    public void rotate(double theta) {
        canvas.rotate((float) Math.toDegrees(theta));
        awtTransform.rotate(theta);
    }

    @Override
    public void rotate(double theta, double x, double y) {
        canvas.rotate((float) Math.toDegrees(theta), (float) x, (float) y);
        awtTransform.rotate(theta, x, y);
    }

    @Override
    public void scale(double sx, double sy) {
        canvas.scale((float) sx, (float) sy);
        awtTransform.scale(sx, sy);
    }

    @Override
    public AffineTransform getTransform() {
        return new AffineTransform(awtTransform);
    }

    @Override
    public void setTransform(AffineTransform Tx) {
        if (Tx == null) Tx = new AffineTransform();
        this.awtTransform = new AffineTransform(Tx);
        applyTransformToCanvas(Tx);
    }

    private void applyTransformToCanvas(AffineTransform Tx) {
        float[] values = new float[9];
        values[0] = (float) Tx.getScaleX();
        values[1] = (float) Tx.getShearX();
        values[2] = (float) Tx.getTranslateX();
        values[3] = (float) Tx.getShearY();
        values[4] = (float) Tx.getScaleY();
        values[5] = (float) Tx.getTranslateY();
        values[6] = 0; values[7] = 0; values[8] = 1;
        Matrix m = new Matrix();
        m.setValues(values);
        canvas.setMatrix(m);
    }

    @Override
    public void transform(AffineTransform Tx) {
        if (Tx == null) return;
        awtTransform.concatenate(Tx);
        applyTransformToCanvas(awtTransform);
    }

    private int clipSaveCount = 0;
    
    @Override
    public void clip(Shape s) {
        if (s == null) return;
        trace("clip:" + s.getClass().getSimpleName());
        Path path = shapeToPath(s);
        if (path != null) {
            canvas.save();
            canvas.clipPath(path);
            clipShape = s;
            clipSaveCount++;
        }
    }

    @Override
    public Shape getClip() { trace("getClip"); return clipShape; }

    @Override
    public void setClip(Shape clip) {
        trace("setClip:" + (clip != null ? clip.getClass().getSimpleName() : "null"));
        while (clipSaveCount > 0) {
            canvas.restore();
            clipSaveCount--;
        }
        clipShape = clip;
        if (clip == null) {
            canvas.clipRect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        } else {
            Path path = shapeToPath(clip);
            if (path != null) {
                canvas.save();
                canvas.clipPath(path);
                clipSaveCount++;
            }
        }
    }

    @Override
    public java.awt.Rectangle getClipBounds() {
        if (clipShape != null) return clipShape.getBounds();
        return new java.awt.Rectangle(0, 0, bitmap.getWidth(), bitmap.getHeight());
    }

    @Override
    public java.awt.Rectangle getClipBounds(java.awt.Rectangle r) { return getClipBounds(); }

    @Override
    public boolean hit(java.awt.Rectangle rect, Shape s, boolean onStroke) { return false; }

    @Override
    public java.awt.Rectangle getBounds() { return new java.awt.Rectangle(0, 0, bitmap.getWidth(), bitmap.getHeight()); }

    @Override
    public java.awt.image.BufferedImage createCompatibleImage(int width, int height, int transparency) {
        return new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    }

    @Override
    public java.awt.image.BufferedImage createCompatibleImage(int width, int height) {
        return new java.awt.image.BufferedImage(width, height, java.awt.image.BufferedImage.TYPE_INT_ARGB);
    }

    @Override
    public java.awt.image.ColorModel getColorModel() { return new java.awt.image.DirectColorModel(32, 0xFF0000, 0xFF00, 0xFF, 0xFF000000); }

    @Override
    public FontMetrics getFontMetrics() { return getFontMetrics(currentFont); }

    @Override
    public FontMetrics getFontMetrics(Font f) {
        if (f == null) f = currentFont;
        return new FontMetrics(f);
    }

    @Override
    public void copyArea(int x, int y, int width, int height, int dx, int dy) {}

    @Override
    public boolean drawImage(java.awt.Image img, int x, int y, java.awt.image.ImageObserver observer) {
        markDrawn();
        trace("drawImage:x" + x + "y" + y);
        Bitmap src = toBitmap(img);
        if (src == null) return false;
        canvas.drawBitmap(src, (float) x, (float) y, fillPaint);
        return true;
    }

    @Override
    public boolean drawImage(java.awt.Image img, int x, int y, int width, int height, java.awt.image.ImageObserver observer) {
        markDrawn();
        trace("drawImage:x" + x + "y" + y + "w" + width + "h" + height);
        Bitmap src = toBitmap(img);
        if (src == null) return false;
        Matrix m = new Matrix();
        m.postScale(width / (float) src.getWidth(), height / (float) src.getHeight());
        m.postTranslate(x, y);
        canvas.drawBitmap(src, m, fillPaint);
        return true;
    }

    @Override
    public boolean drawImage(java.awt.Image img, int x, int y, java.awt.Color bgcolor, java.awt.image.ImageObserver observer) {
        return drawImage(img, x, y, observer);
    }

    @Override
    public boolean drawImage(java.awt.Image img, int x, int y, int width, int height, java.awt.Color bgcolor, java.awt.image.ImageObserver observer) {
        return drawImage(img, x, y, width, height, observer);
    }

    public boolean drawImage(java.awt.Image img, AffineTransform xform, java.awt.image.ImageObserver observer) {
        markDrawn();
        Bitmap src = toBitmap(img);
        if (src == null) return false;
        Matrix m = new Matrix();
        m.postTranslate((float) xform.getTranslateX(), (float) xform.getTranslateY());
        m.postScale((float) xform.getScaleX(), (float) xform.getScaleY());
        canvas.drawBitmap(src, m, fillPaint);
        return true;
    }

    private static Bitmap toBitmap(java.awt.Image img) {
        if (!(img instanceof java.awt.image.BufferedImage bi)) return null;
        int w = bi.getWidth();
        int h = bi.getHeight();
        if (w <= 0 || h <= 0) return null;
        int[] pixels = new int[w * h];
        bi.getRGB(0, 0, w, h, pixels, 0, w);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        return bmp;
    }

    public Bitmap getBitmap() { return bitmap; }

    @SuppressWarnings("deprecation")
    private Path shapeToPath(Shape s) {
        Path path = new Path();
        if (s instanceof Rectangle2D r) {
            path.addRect((float) r.getX(), (float) r.getY(),
                    (float) (r.getX() + r.getWidth()), (float) (r.getY() + r.getHeight()),
                    Path.Direction.CW);
        } else if (s instanceof Ellipse2D e) {
            path.addOval((float) e.getX(), (float) e.getY(),
                    (float) (e.getX() + e.getWidth()), (float) (e.getY() + e.getHeight()),
                    Path.Direction.CW);
        } else if (s instanceof Arc2D a) {
            double cx = a.getCenterX();
            double cy = a.getCenterY();
            double rx = a.getWidth() / 2.0;
            double ry = a.getHeight() / 2.0;
            RectF oval = new RectF((float)(cx - rx), (float)(cy - ry),
                    (float)(cx + rx), (float)(cy + ry));
            float start = (float) -a.getAngleStart();
            float sweep = (float) -a.getAngleExtent();
            if (a.getArcType() == Arc2D.PIE) {
                path.moveTo((float) cx, (float) cy);
                path.arcTo(oval, start, sweep);
                path.close();
            } else if (a.getArcType() == Arc2D.CHORD) {
                path.arcTo(oval, start, sweep);
                path.close();
            } else {
                path.arcTo(oval, start, sweep);
            }
        } else if (s instanceof java.awt.Polygon p) {
            if (p.npoints > 0) {
                path.moveTo(p.xpoints[0], p.ypoints[0]);
                for (int i = 1; i < p.npoints; i++) {
                    path.lineTo(p.xpoints[i], p.ypoints[i]);
                }
                path.close();
            }
        } else {
            try {
                PathIterator pi = s.getPathIterator(null);
                if (pi == null) {
                    Rectangle2D b2d = s.getBounds2D();
                    if (b2d != null && b2d.getWidth() > 0 && b2d.getHeight() > 0) {
                        path.addRect((float) b2d.getX(), (float) b2d.getY(),
                                (float) (b2d.getX() + b2d.getWidth()), (float) (b2d.getY() + b2d.getHeight()),
                                Path.Direction.CW);
                    }
                    return path;
                }
                float[] coords = new float[6];
                while (!pi.isDone()) {
                    int type = pi.currentSegment(coords);
                    switch (type) {
                        case PathIterator.SEG_MOVETO:
                            path.moveTo(coords[0], coords[1]);
                            break;
                        case PathIterator.SEG_LINETO:
                            path.lineTo(coords[0], coords[1]);
                            break;
                        case PathIterator.SEG_QUADTO:
                            path.quadTo(coords[0], coords[1], coords[2], coords[3]);
                            break;
                        case PathIterator.SEG_CUBICTO:
                            path.cubicTo(coords[0], coords[1], coords[2], coords[3], coords[4], coords[5]);
                            break;
                        case PathIterator.SEG_CLOSE:
                            path.close();
                            break;
                    }
                    pi.next();
                }
            } catch (Exception e) {
                Rectangle2D b2d = s.getBounds2D();
                if (b2d != null && b2d.getWidth() > 0 && b2d.getHeight() > 0) {
                    path.addRect((float) b2d.getX(), (float) b2d.getY(),
                            (float) (b2d.getX() + b2d.getWidth()), (float) (b2d.getY() + b2d.getHeight()),
                            Path.Direction.CW);
                }
            }
        }
        return path;
    }
}
