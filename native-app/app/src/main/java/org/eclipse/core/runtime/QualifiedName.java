package org.eclipse.core.runtime;

public class QualifiedName {
    private final String qualifier;
    private final String localName;

    public QualifiedName(String qualifier, String localName) {
        this.qualifier = qualifier;
        this.localName = localName;
    }

    public String getQualifier() { return qualifier; }
    public String getLocalName() { return localName; }
}
