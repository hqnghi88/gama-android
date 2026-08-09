package java.awt;

import java.awt.event.ComponentListener;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;

public class Component implements java.io.Serializable {
    public void setBounds(int x, int y, int w, int h) {}
    public Rectangle getBounds() { return new Rectangle(); }
    public void setVisible(boolean aFlag) {}
    public boolean isVisible() { return false; }
    public void setBackground(Color bg) {}
    public Color getBackground() { return Color.black; }
    public void setFont(Font font) {}
    public Font getFont() { return new Font("default", 0, 12); }
    public void setForeground(Color fg) {}
    public Color getForeground() { return Color.black; }
    public Graphics getGraphics() { return null; }
    public Point getLocationOnScreen() { return new Point(); }
    public int getWidth() { return 0; }
    public int getHeight() { return 0; }
    public String getName() { return ""; }
    public void setName(String name) {}
    public void setLayout(LayoutManager mgr) {}
    public void addComponentListener(ComponentListener l) {}
    public void removeComponentListener(ComponentListener l) {}
    public void addMouseListener(MouseListener l) {}
    public void removeMouseListener(MouseListener l) {}
    public void addMouseMotionListener(MouseMotionListener l) {}
    public void removeMouseMotionListener(MouseMotionListener l) {}
    public void repaint() {}
    public void repaint(long tm, int x, int y, int width, int height) {}
    public void validate() {}
    public boolean isDisplayable() { return false; }
    public Container getParent() { return null; }
}
