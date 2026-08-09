package java.awt;

public abstract class Graphics {
    public Graphics() {}
    public abstract void dispose();
    public void drawString(String str, int x, int y) {}
    public void drawLine(int x1, int y1, int x2, int y2) {}
    public void fillRect(int x, int y, int width, int height) {}
    public void drawRect(int x, int y, int width, int height) {}
    public void clearRect(int x, int y, int width, int height) {}
    public void setColor(Color c) {}
    public Color getColor() { return Color.BLACK; }
    public void setFont(Font font) {}
    public Font getFont() { return Font.DIALOG; }
    public Graphics create() { return this; }
    public Graphics create(int x, int y, int width, int height) { return this; }
    public void copyArea(int x, int y, int width, int height, int dx, int dy) {}
    public void drawOval(int x, int y, int width, int height) {}
    public void fillOval(int x, int y, int width, int height) {}
    public void drawArc(int x, int y, int width, int height, int startAngle, int arcAngle) {}
    public void fillArc(int x, int y, int width, int height, int startAngle, int arcAngle) {}
    public void drawPolyline(int[] xPoints, int[] yPoints, int nPoints) {}
    public void drawPolygon(int[] xPoints, int[] yPoints, int nPoints) {}
    public void fillPolygon(int[] xPoints, int[] yPoints, int nPoints) {}
    public void drawRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {}
    public void fillRoundRect(int x, int y, int width, int height, int arcWidth, int arcHeight) {}
    public void draw3DRect(int x, int y, int width, int height, boolean raised) {}
    public void fill3DRect(int x, int y, int width, int height, boolean raised) {}
    public boolean drawImage(java.awt.Image img, int x, int y, java.awt.image.ImageObserver observer) { return false; }
    public boolean drawImage(java.awt.Image img, int x, int y, int width, int height, java.awt.image.ImageObserver observer) { return false; }
    public abstract java.awt.FontMetrics getFontMetrics(Font f);
    public java.awt.FontMetrics getFontMetrics() { return getFontMetrics(getFont()); }
    public Rectangle getClipBounds() { return new Rectangle(); }
    public void setClip(Shape clip) {}
    public Shape getClip() { return null; }
    public Rectangle getBounds() { return new Rectangle(); }
    public void translate(int x, int y) {}
    public void draw(Shape s) {}
    public void fill(Shape s) {}
}
