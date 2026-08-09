package java.awt;

public class Point extends java.awt.geom.Point2D {
    public int x, y;
    public Point() { this(0, 0); }
    public Point(int x, int y) { this.x = x; this.y = y; }
    public Point(java.awt.Point p) { this(p.x, p.y); }
    public double getX() { return x; }
    public double getY() { return y; }
    public void setLocation(double x, double y) { this.x = (int) x; this.y = (int) y; }
    public void setLocation(int x, int y) { this.x = x; this.y = y; }
    public void move(int x, int y) { this.x = x; this.y = y; }
    public void translate(int dx, int dy) { this.x += dx; this.y += dy; }
    public boolean equals(Object obj) { if (obj instanceof Point) { Point p = (Point) obj; return p.x == x && p.y == y; } return false; }
    public String toString() { return "java.awt.Point[x=" + x + ",y=" + y + "]"; }
}
