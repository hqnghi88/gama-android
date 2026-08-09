package java.awt.event;

import java.awt.Component;

public class ComponentEvent extends java.util.EventObject {
    public static final int COMPONENT_FIRST = 100;
    public static final int COMPONENT_RESIZED = COMPONENT_FIRST + 4;
    public static final int COMPONENT_MOVED = COMPONENT_FIRST + 3;
    public static final int COMPONENT_SHOWN = COMPONENT_FIRST + 2;
    public static final int COMPONENT_HIDDEN = COMPONENT_FIRST + 1;

    public ComponentEvent(Component source, int id) {
        super(source);
    }
    public Component getComponent() { return (Component) getSource(); }
}
