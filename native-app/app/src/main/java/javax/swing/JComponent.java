package javax.swing;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.LayoutManager;

public class JComponent extends java.awt.Container implements java.io.Serializable, TransferHandler.HasGetTransferHandler {
    protected transient javax.swing.plaf.ComponentUI ui;

    public JComponent() {}
    public JComponent(LayoutManager layout) { setLayout(layout); }

    public void setUI(javax.swing.plaf.ComponentUI newUI) { this.ui = newUI; }
    public javax.swing.plaf.ComponentUI getUI() { return ui; }
    public void updateUI() {}

    public void setOpaque(boolean isOpaque) {}
    public boolean isOpaque() { return false; }

    public void setTransferHandler(TransferHandler newHandler) {}
    public TransferHandler getTransferHandler() { return null; }

    public void revalidate() {}

    public void putClientProperty(Object key, Object value) {}
    public Object getClientProperty(Object key) { return null; }

    public javax.swing.ActionMap getActionMap() { return new ActionMap(); }
    public void setActionMap(ActionMap map) {}
    public javax.swing.InputMap getInputMap(int condition) { return new InputMap(); }
    public void setInputMap(int condition, InputMap map) {}
    public static final int WHEN_FOCUSED = 0;
    public static final int WHEN_ANCESTOR_OF_FOCUSED_COMPONENT = 1;
    public static final int WHEN_IN_FOCUSED_WINDOW = 2;

    public void setToolTipText(String text) {}
    public String getToolTipText() { return null; }

    protected void paintComponent(Graphics g) {}
    protected void paintBorder(Graphics g) {}
    protected void paintChildren(Graphics g) {}

    public java.awt.Rectangle getVisibleRect() { return new java.awt.Rectangle(getWidth(), getHeight()); }
    public void scrollRectToVisible(java.awt.Rectangle aRect) {}
    public boolean isDoubleBuffered() { return false; }
    public void setDoubleBuffered(boolean isDoubleBuffered) {}
}
