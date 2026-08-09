package java.awt;

public class Container extends Component {
    public Container() {}
    public Component add(Component comp) { return comp; }
    public void add(Component comp, Object constraints) {}
    public void remove(Component comp) {}
    public void removeAll() {}
    public Component[] getComponents() { return new Component[0]; }
    public int getComponentCount() { return 0; }
    public LayoutManager getLayout() { return null; }
}
