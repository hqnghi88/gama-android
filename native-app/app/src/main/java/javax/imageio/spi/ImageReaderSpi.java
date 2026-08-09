package javax.imageio.spi;

public abstract class ImageReaderSpi extends ImageReaderWriterSpi {
    public ImageReaderSpi() {}
    public ImageReaderSpi(String vendorName, String version,
                          String[] formatNames, String[] fileSuffixes,
                          String[] MIMETypes, String className,
                          Class[] inputTypes, String[] writerSpiNames,
                          boolean supportsStandardStreamMetadataFormat,
                          String nativeStreamMetadataFormatName,
                          String nativeStreamMetadataFormatClassName,
                          String[] extraStreamMetadataFormatNames,
                          String[] extraStreamMetadataFormatClassNames,
                          boolean supportsStandardImageMetadataFormat,
                          String nativeImageMetadataFormatName,
                          String nativeImageMetadataFormatClassName,
                          String[] extraImageMetadataFormatNames,
                          String[] extraImageMetadataFormatClassNames) {
        this.vendorName = vendorName;
        this.version = version;
    }
}
