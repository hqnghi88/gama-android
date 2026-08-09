package com.github.weisj.jsvg.parser;

import java.io.InputStream;
import java.net.URI;

import com.github.weisj.jsvg.SVGDocument;

public class SVGLoader {
    public SVGDocument load(InputStream is, URI uri, LoaderContext context) {
        return new SVGDocument();
    }
}
