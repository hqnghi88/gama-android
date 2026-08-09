package javax.imageio.spi;

public abstract class ServiceRegistry {
    public enum Category { IMAGE_READER, IMAGE_WRITER, IMAGE_STREAM_IMAGEInputStreamSPI, IMAGE_STREAM_IMAGEOutputStreamSPI }

    public <T> boolean registerServiceProvider(T provider, Class<T> category) { return true; }
    public <T> boolean deregisterServiceProvider(T provider, Class<T> category) { return true; }
    public <T> java.util.Iterator<T> getServiceProviders(Class<T> category, boolean useServiceLoaderFirst) {
        return java.util.Collections.<T>emptyIterator();
    }
    public void finalize() throws Throwable {}
}
