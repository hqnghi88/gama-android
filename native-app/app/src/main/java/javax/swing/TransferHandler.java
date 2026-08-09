package javax.swing;

public abstract class TransferHandler implements java.io.Serializable {
    public interface HasGetTransferHandler {
        TransferHandler getTransferHandler();
    }
}
