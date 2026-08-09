package javax.swing;

public class JPanel extends JComponent {
    public JPanel() { super(); }
    public JPanel(boolean isDoubleBuffered) { super(); }
    public JPanel(java.awt.LayoutManager layout) { super(layout); }
    public JPanel(java.awt.LayoutManager layout, boolean isDoubleBuffered) { super(layout); }

    @Override protected void paintComponent(java.awt.Graphics g) {}
}
