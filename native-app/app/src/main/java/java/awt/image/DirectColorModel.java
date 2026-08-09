package java.awt.image;

public class DirectColorModel extends ColorModel {
    private int rmask, gmask, bmask, amask;
    public DirectColorModel(int bits, int rmask, int gmask, int bmask) {
        super(bits);
        this.rmask = rmask; this.gmask = gmask; this.bmask = bmask; this.amask = 0;
    }
    public DirectColorModel(int bits, int rmask, int gmask, int bmask, int amask) {
        super(bits, amask != 0);
        this.rmask = rmask; this.gmask = gmask; this.bmask = bmask; this.amask = amask;
    }
    public int getRedMask() { return rmask; }
    public int getGreenMask() { return gmask; }
    public int getBlueMask() { return bmask; }
    public int getAlphaMask() { return amask; }
}
