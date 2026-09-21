package java.awt.datatransfer;

public interface Transferable {

    DataFlavor[] getTransferDataFlavors();

    boolean isDataFlavorSupported(DataFlavor flavor);

    Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, java.io.IOException;
}