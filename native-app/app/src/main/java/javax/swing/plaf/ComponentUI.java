package javax.swing.plaf;

public abstract class ComponentUI {
    public void installUI(javax.swing.JComponent c) {}
    public void uninstallUI(javax.swing.JComponent c) {}
    public void paint(java.awt.Graphics g, javax.swing.JComponent c) {}
    public java.awt.Dimension getPreferredSize(javax.swing.JComponent c) { return new java.awt.Dimension(0, 0); }
}
