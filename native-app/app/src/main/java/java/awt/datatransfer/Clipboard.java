package java.awt.datatransfer;

import java.util.LinkedList;
import java.util.List;

public class Clipboard {

    protected static final Object FLOCK = new Object();

    protected Transferable contents;
    protected final String name;
    private final List<FlavorListener> flavorListeners = new LinkedList<>();

    public Clipboard(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public synchronized Transferable getContents(Object requestor) {
        return contents;
    }

    public synchronized void setContents(Transferable contents, FlavorListener flavorListener) {
        this.contents = contents;
        if (flavorListener != null) {
            addFlavorListener(flavorListener);
        }
        for (FlavorListener l : flavorListeners) {
            l.flavorsChanged(new FlavorEvent(this));
        }
    }

    public synchronized void addFlavorListener(FlavorListener listener) {
        if (listener != null) {
            flavorListeners.add(listener);
        }
    }

    public synchronized void removeFlavorListener(FlavorListener listener) {
        flavorListeners.remove(listener);
    }

    public synchronized DataFlavor[] getAvailableDataFlavors() {
        if (contents == null) return new DataFlavor[0];
        DataFlavor[] f = contents.getTransferDataFlavors();
        return f != null ? f : new DataFlavor[0];
    }

    public synchronized boolean isDataFlavorAvailable(DataFlavor flavor) {
        return contents != null && contents.isDataFlavorSupported(flavor);
    }

    public Object getData(DataFlavor flavor) throws UnsupportedFlavorException, java.io.IOException {
        if (contents == null) {
            throw new UnsupportedFlavorException(flavor);
        }
        return contents.getTransferData(flavor);
    }
}