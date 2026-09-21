package java.awt;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;

public class LinearGradientPaint extends MultipleGradientPaint implements java.awt.Paint {

    private final Point2D start;
    private final Point2D end;
    private final float[] fractions;
    private final Color[] colors;
    private final CycleMethod cycleMethod;
    private final ColorSpaceType colorSpace;
    private final AffineTransform gradientTransform;

    public LinearGradientPaint(Point2D start, Point2D end, float[] fractions, Color[] colors) {
        this(start, end, fractions, colors, CycleMethod.NO_CYCLE, ColorSpaceType.SRGB, new AffineTransform());
    }

    public LinearGradientPaint(float x1, float y1, float x2, float y2, float[] fractions, Color[] colors,
            CycleMethod cycleMethod) {
        this(new Point2D.Float(x1, y1), new Point2D.Float(x2, y2), fractions, colors, cycleMethod, ColorSpaceType.SRGB,
                new AffineTransform());
    }

    public LinearGradientPaint(Point2D start, Point2D end, float[] fractions, Color[] colors,
            CycleMethod cycleMethod) {
        this(start, end, fractions, colors, cycleMethod, ColorSpaceType.SRGB, new AffineTransform());
    }

    public LinearGradientPaint(Point2D start, Point2D end, float[] fractions, Color[] colors,
            CycleMethod cycleMethod, ColorSpaceType colorSpace) {
        this(start, end, fractions, colors, cycleMethod, colorSpace, new AffineTransform());
    }

    public LinearGradientPaint(Point2D start, Point2D end, float[] fractions, Color[] colors,
            CycleMethod cycleMethod, ColorSpaceType colorSpace, AffineTransform gradientTransform) {
        if (fractions == null || colors == null || fractions.length != colors.length || fractions.length < 2) {
            throw new IllegalArgumentException("fractions and colors must be non-null, same length and >= 2");
        }
        if (start.getX() == end.getX() && start.getY() == end.getY()) {
            throw new IllegalArgumentException("start and end points must be distinct");
        }
        this.start = new Point2D.Double(start.getX(), start.getY());
        this.end = new Point2D.Double(end.getX(), end.getY());
        this.fractions = fractions.clone();
        this.colors = colors.clone();
        this.cycleMethod = cycleMethod;
        this.colorSpace = colorSpace;
        this.gradientTransform = gradientTransform == null ? new AffineTransform() : new AffineTransform(gradientTransform);
    }

    public Point2D getStartPoint() { return new Point2D.Double(start.getX(), start.getY()); }
    public Point2D getEndPoint() { return new Point2D.Double(end.getX(), end.getY()); }
    public float[] getFractions() { return fractions.clone(); }
    public Color[] getColors() { return colors.clone(); }
    public CycleMethod getCycleMethod() { return cycleMethod; }
    public ColorSpaceType getColorSpace() { return colorSpace; }
    public AffineTransform getTransform() { return new AffineTransform(gradientTransform); }

    @Override
    public int getTransparency() {
        for (Color c : colors) {
            if (c.getAlpha() < 255) return Transparency.TRANSLUCENT;
        }
        return Transparency.OPAQUE;
    }
}