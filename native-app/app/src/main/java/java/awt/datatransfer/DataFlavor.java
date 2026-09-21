package java.awt.datatransfer;

import java.awt.Image;

public class DataFlavor {

    public static final DataFlavor imageFlavor = new DataFlavor("image/x-java-image; class=java.awt.Image", "Image");
    public static final DataFlavor stringFlavor = new DataFlavor("application/x-java-serialized-object; class=java.lang.String", "Unicode String");

    private final String mimeType;
    private final String humanPresentableName;
    private final Class<?> representationClass;

    public DataFlavor(String mimeType, String humanPresentableName) {
        this(mimeType, humanPresentableName, Object.class);
    }

    public DataFlavor(String mimeType, String humanPresentableName, Class<?> representationClass) {
        this.mimeType = mimeType;
        this.humanPresentableName = humanPresentableName;
        this.representationClass = representationClass;
    }

    public String getMimeType() {
        return mimeType;
    }

    public String getHumanPresentableName() {
        return humanPresentableName;
    }

    public Class<?> getRepresentationClass() {
        return representationClass;
    }

    public boolean isFlavorJavaImageFlavorClass() {
        return representationClass == Image.class;
    }

    public boolean isFlavorSerializedObjectType() {
        return mimeType != null && mimeType.contains("serialized-object");
    }

    public boolean isFlavorTextType() {
        return mimeType != null && mimeType.contains("text/") || mimeType != null && mimeType.contains("character");
    }

    @Override
    public String toString() {
        return mimeType;
    }

    @Override
    public boolean equals(Object o) {
        if (o == this) return true;
        if (!(o instanceof DataFlavor)) return false;
        DataFlavor other = (DataFlavor) o;
        return mimeType != null && mimeType.equals(other.mimeType);
    }

    @Override
    public int hashCode() {
        return mimeType != null ? mimeType.hashCode() : 0;
    }
}