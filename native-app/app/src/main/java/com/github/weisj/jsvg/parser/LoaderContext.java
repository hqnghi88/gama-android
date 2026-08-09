package com.github.weisj.jsvg.parser;

public class LoaderContext {
    private final DocumentLimits documentLimits;

    private LoaderContext(DocumentLimits documentLimits) {
        this.documentLimits = documentLimits;
    }

    public DocumentLimits documentLimits() { return documentLimits; }

    public static Builder builder() { return new BuilderImpl(); }

    public interface Builder {
        Builder documentLimits(DocumentLimits limits);
        LoaderContext build();
    }

    private static class BuilderImpl implements Builder {
        private DocumentLimits documentLimits;

        @Override
        public Builder documentLimits(DocumentLimits limits) {
            this.documentLimits = limits;
            return this;
        }

        @Override
        public LoaderContext build() { return new LoaderContext(documentLimits); }
    }
}
