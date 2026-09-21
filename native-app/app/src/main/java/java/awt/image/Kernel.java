package java.awt.image;

public class Kernel implements Cloneable {

    private final int width;
    private final int height;
    private final float[] data;
    private final int xOrigin;
    private final int yOrigin;

    public Kernel(int width, int height, float[] data) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Kernel must have positive dimensions");
        }
        int size = width * height;
        if (data.length < size) {
            throw new IllegalArgumentException("Kernel data has too few elements");
        }
        this.width = width;
        this.height = height;
        this.data = new float[size];
        System.arraycopy(data, 0, this.data, 0, size);
        this.xOrigin = (width - 1) / 2;
        this.yOrigin = (height - 1) / 2;
    }

    public int getXOrigin() {
        return xOrigin;
    }

    public int getYOrigin() {
        return yOrigin;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public float[] getKernelData(float[] data) {
        if (data == null) {
            return this.data.clone();
        }
        System.arraycopy(this.data, 0, data, 0, this.data.length);
        return data;
    }

    public float[] getKernelData() {
        return data.clone();
    }

    @Override
    public Object clone() {
        return new Kernel(width, height, data);
    }
}