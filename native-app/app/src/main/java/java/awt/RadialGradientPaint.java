package java.awt;

import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;

public class RadialGradientPaint extends MultipleGradientPaint implements java.awt.Paint {

    private final Point2D center;
    private final Point2D focus;
    private final float radius;
    private final float[] fractions;
    private final Color[] colors;
    private final CycleMethod cycleMethod;
    private final ColorSpaceType colorSpace;
    private final AffineTransform gradientTransform;

    public RadialGradientPaint(float cx, float cy, float radius, float[] fractions, Color[] colors,
            CycleMethod cycleMethod) {
        this(new Point2D.Float(cx, cy), radius, fractions, colors, cycleMethod);
    }

    public RadialGradientPaint(Point2D center, float radius, float[] fractions, Color[] colors,
            CycleMethod cycleMethod) {
        this(center, radius, center, fractions, colors, cycleMethod, ColorSpaceType.SRGB, new AffineTransform());
    }

    public RadialGradientPaint(Point2D center, float radius, float[] fractions, Color[] colors,
            CycleMethod cycleMethod, ColorSpaceType colorSpace) {
        this(center, radius, center, fractions, colors, cycleMethod, colorSpace, new AffineTransform());
    }

    public RadialGradientPaint(Point2D center, float radius, Point2D focus, float[] fractions, Color[] colors,
            CycleMethod cycleMethod, ColorSpaceType colorSpace, AffineTransform gradientTransform) {
        if (fractions == null || colors == null || fractions.length != colors.length || fractions.length < 2) {
            throw new IllegalArgumentException("fractions and colors must be non-null, same length and >= 2");
        }
        if (radius <= 0) throw new IllegalArgumentException("radius must be > 0");
        this.center = new Point2D.Double(center.getX(), center.getY());
        this.focus = new Point2D.Double(focus.getX(), focus.getY());
        this.radius = radius;
        this.fractions = fractions.clone();
        this.colors = colors.clone();
        this.cycleMethod = cycleMethod;
        this.colorSpace = colorSpace;
        this.gradientTransform = gradientTransform == null ? new AffineTransform() : new AffineTransform(gradientTransform);
    }

    public Point2D getCenterPoint() { return new Point2D.Double(center.getX(), center.getY()); }
    public Point2D getFocusPoint() { return new Point2D.Double(focus.getX(), focus.getY()); }
    public float getRadius() { return radius; }
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