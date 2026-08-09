package javax.imageio.event;

public interface IIOReadProgressListener {
    void imageStarted(javax.imageio.ImageReader source, int imageIndex);
    void imageProgress(javax.imageio.ImageReader source, float percentageDone);
    void imageComplete(javax.imageio.ImageReader source);
    void readStarted(javax.imageio.ImageReader source, int imageIndex);
    void readPassStarted(javax.imageio.ImageReader source, int pass, int minPass, int maxPass, int minX, int minY, int width, int height, int periodX, int periodY);
    void readPassComplete(javax.imageio.ImageReader source, int pass);
    void readAborted(javax.imageio.ImageReader source);
    void sequenceStarted(javax.imageio.ImageReader source, int imageIndex);
    void sequenceComplete(javax.imageio.ImageReader source);
}
