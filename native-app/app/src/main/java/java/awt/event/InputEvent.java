package java.awt.event;

import java.awt.Component;

public class InputEvent extends ComponentEvent {
    public static final int SHIFT_MASK = 1 << 0;
    public static final int CTRL_MASK = 1 << 1;
    public static final int META_MASK = 1 << 2;
    public static final int ALT_MASK = 1 << 3;
    public static final int ALT_GRAPH_MASK = 1 << 5;

    protected InputEvent(Component source, int id, long when, int modifiers) {
        super(source, id);
    }
    public int getModifiersEx() { return 0; }
}
