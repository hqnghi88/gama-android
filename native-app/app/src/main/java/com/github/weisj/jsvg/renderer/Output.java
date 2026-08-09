package com.github.weisj.jsvg.renderer;

import java.awt.Stroke;
import java.awt.geom.AffineTransform;

public interface Output {
    interface SafeState {
        Stroke stroke();
        AffineTransform transform();
        void restore();
    }
    SafeState safeState();
    boolean supportsFilters();
    boolean supportsColors();
    boolean isSoftClippingEnabled();
}
