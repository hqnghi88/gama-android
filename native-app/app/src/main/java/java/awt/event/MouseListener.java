package java.awt.event;

public interface MouseListener extends java.util.EventListener {
    void mouseClicked(java.awt.event.MouseEvent e);
    void mousePressed(java.awt.event.MouseEvent e);
    void mouseReleased(java.awt.event.MouseEvent e);
    void mouseEntered(java.awt.event.MouseEvent e);
    void mouseExited(java.awt.event.MouseEvent e);
}
