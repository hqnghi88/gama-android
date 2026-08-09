package java.awt.event;

import java.awt.Component;

public class KeyEvent extends InputEvent {
    public static final int KEY_FIRST = 400;
    public static final int KEY_TYPED = KEY_FIRST + 1;
    public static final int KEY_PRESSED = KEY_FIRST + 2;
    public static final int KEY_RELEASED = KEY_FIRST + 3;

    private int keyCode;
    private char keyChar;

    public KeyEvent(Component source, int id, long when, int modifiers, int keyCode, char keyChar) {
        super(source, id, when, modifiers);
        this.keyCode = keyCode;
        this.keyChar = keyChar;
    }

    public int getKeyCode() { return keyCode; }
    public char getKeyChar() { return keyChar; }
}
