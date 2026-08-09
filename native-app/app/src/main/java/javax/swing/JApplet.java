package javax.swing;

public class JApplet extends JComponent {
    public JApplet() { super(); }
    public java.awt.Container getContentPane() { return this; }
}
