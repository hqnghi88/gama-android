package java.awt;

public class GraphicsDevice {
    public static final int TYPE_WINDOW = 1;
    public static final int TYPE_BUFFERedImage = 2;
    public static final int TYPE_DRAWABLE = 3;
    public int getType() { return TYPE_DRAWABLE; }
    public String getIDstring() { return "Screen 0"; }
    public DisplayMode getDisplayMode() { return new DisplayMode(); }
    public GraphicsConfiguration getDefaultConfiguration() { return new GraphicsConfiguration(); }
    public DisplayMode[] getDisplayModes() { return new DisplayMode[0]; }
}
