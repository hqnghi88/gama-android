package com.github.weisj.jsvg;

import java.awt.Graphics2D;

import com.github.weisj.jsvg.attributes.ViewBox;
import com.github.weisj.jsvg.geometry.size.FloatSize;
import com.github.weisj.jsvg.renderer.Output;
import com.github.weisj.jsvg.renderer.awt.PlatformSupport;

public class SVGDocument {
    public void renderWithPlatform(PlatformSupport platform, Output output, ViewBox viewBox) {}
    public void renderWithPlatform(PlatformSupport platform, Graphics2D g2, ViewBox viewBox) {}
    public FloatSize size() { return new FloatSize(0, 0); }
}
