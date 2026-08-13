package java.awt.geom;

import java.awt.Shape;

/**
 * Functional GeneralPath (the awt-stubs.jar version was a broken stub whose
 * getPathIterator returned an always-empty iterator and getBounds2D returned
 * (0,0,0,0), which silently erased every shape JFreeChart draws as a GeneralPath
 * -- e.g. the LongSeries line). This extends the real Path2D.Float implementation
 * that lives in source alongside the other geom classes.
 */
public class GeneralPath extends Path2D.Float implements Cloneable {

    public GeneralPath() {
        super();
    }

    public GeneralPath(int rule) {
        super(rule);
    }

    public GeneralPath(int rule, int initialCapacity) {
        super(rule, initialCapacity);
    }

    public GeneralPath(Shape s) {
        this();
        if (s != null) {
            append(s, false);
        }
    }

    public GeneralPath(PathIterator pi) {
        this();
        append(pi, false);
    }

    @Override
    public PathIterator getPathIterator(AffineTransform at) {
        return super.getPathIterator(at);
    }

    @Override
    public PathIterator getPathIterator(AffineTransform at, double flatness) {
        return super.getPathIterator(at, flatness);
    }

    @Override
    public GeneralPath clone() {
        return (GeneralPath) super.clone();
    }
}
