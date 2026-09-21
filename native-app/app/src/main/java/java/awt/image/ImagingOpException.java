package java.awt.image;

public class ImagingOpException extends RuntimeException {

    public ImagingOpException(String message) {
        super(message);
    }

    public ImagingOpException(String message, Throwable cause) {
        super(message, cause);
    }
}