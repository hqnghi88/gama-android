package java.awt;

public class Polygon implements Shape {
    public int[] xpoints;
    public int[] ypoints;
    public int npoints;

    public Polygon() { this(new int[0], new int[0], 0); }
    public Polygon(int[] xpoints, int[] ypoints, int npoints) {
        this.npoints = npoints;
        this.xpoints = new int[Math.max(npoints, 0)];
        this.ypoints = new int[Math.max(npoints, 0)];
        if (npoints > 0) {
            System.arraycopy(xpoints, 0, this.xpoints, 0, npoints);
            System.arraycopy(ypoints, 0, this.ypoints, 0, npoints);
        }
    }

    public void addPoint(int x, int y) {
        if (npoints >= xpoints.length) {
            int[] newX = new int[Math.max(npoints * 2, 4)];
            int[] newY = new int[Math.max(npoints * 2, 4)];
            System.arraycopy(xpoints, 0, newX, 0, npoints);
            System.arraycopy(ypoints, 0, newY, 0, npoints);
            xpoints = newX;
            ypoints = newY;
        }
        xpoints[npoints] = x;
        ypoints[npoints] = y;
        npoints++;
    }

    public java.awt.Rectangle getBounds() {
        if (npoints == 0) return new java.awt.Rectangle();
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
        for (int i = 0; i < npoints; i++) {
            minX = Math.min(minX, xpoints[i]);
            minY = Math.min(minY, ypoints[i]);
            maxX = Math.max(maxX, xpoints[i]);
            maxY = Math.max(maxY, ypoints[i]);
        }
        return new java.awt.Rectangle(minX, minY, maxX - minX, maxY - minY);
    }

    public java.awt.geom.Rectangle2D getBounds2D() {
        java.awt.Rectangle b = getBounds();
        return new java.awt.geom.Rectangle2D.Double(b.getX(), b.getY(), b.getWidth(), b.getHeight());
    }
    public boolean contains(double x, double y) { return false; }
    public boolean contains(double x, double y, double w, double h) { return false; }
    public boolean contains(java.awt.geom.Point2D p) { return contains(p.getX(), p.getY()); }
    public boolean contains(java.awt.geom.Rectangle2D r) { return false; }
    public boolean intersects(double x, double y, double w, double h) { return false; }
    public boolean intersects(java.awt.geom.Rectangle2D r) { return false; }
    public java.awt.geom.PathIterator getPathIterator(java.awt.geom.AffineTransform at) { return null; }
    public java.awt.geom.PathIterator getPathIterator(java.awt.geom.AffineTransform at, double flatness) { return null; }
}
