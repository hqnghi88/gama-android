package java.awt.event;

import java.awt.Component;

public class MouseEvent extends InputEvent {
    public static final int MOUSE_FIRST = 500;
    public static final int MOUSE_CLICKED = MOUSE_FIRST + 1;
    public static final int MOUSE_PRESSED = MOUSE_FIRST + 2;
    public static final int MOUSE_RELEASED = MOUSE_FIRST + 3;
    public static final int MOUSE_ENTERED = MOUSE_FIRST + 4;
    public static final int MOUSE_EXITED = MOUSE_FIRST + 5;
    public static final int MOUSE_DRAGGED = MOUSE_FIRST + 6;
    public static final int MOUSE_MOVED = MOUSE_FIRST + 7;

    private int x, y;
    private int clickCount;
    private int button;

    public MouseEvent(Component source, int id, long when, int modifiers, int x, int y, int clickCount, boolean popupTrigger, int button) {
        super(source, id, when, modifiers);
        this.x = x;
        this.y = y;
        this.clickCount = clickCount;
        this.button = button;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getClickCount() { return clickCount; }
    public int getButton() { return button; }
}
