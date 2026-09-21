package java.awt.datatransfer;

public class UnsupportedFlavorException extends Exception {

    public UnsupportedFlavorException(DataFlavor flavor) {
        super("flavor = " + String.valueOf(flavor));
    }

    public UnsupportedFlavorException(String message) {
        super(message);
    }
}