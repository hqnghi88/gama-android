package javax.imageio.spi;

public abstract class ImageReaderWriterSpi {
    protected String vendorName;
    protected String version;
    protected String[] formatNames;
    protected String[] fileSuffixes;
    protected String[] MIMETypes;

    public ImageReaderWriterSpi() {}
    public String getVendorName() { return vendorName; }
    public String getVersion() { return version; }
}
