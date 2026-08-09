package javax.swing;

import java.io.Serializable;

public class KeyStroke implements Serializable {
    private int keyCode;
    private int modifiers;
    private boolean onKeyRelease;

    protected KeyStroke(int keyCode, int modifiers, boolean onKeyRelease) {
        this.keyCode = keyCode;
        this.modifiers = modifiers;
        this.onKeyRelease = onKeyRelease;
    }

    public static KeyStroke getKeyStroke(int keyCode, int modifiers, boolean onKeyRelease) {
        return new KeyStroke(keyCode, modifiers, onKeyRelease);
    }
    public static KeyStroke getKeyStroke(int keyCode, int modifiers) {
        return new KeyStroke(keyCode, modifiers, false);
    }
    public static KeyStroke getKeyStroke(String representation) {
        return new KeyStroke(0, 0, false);
    }
    public static KeyStroke getKeyStrokeForEvent(Object event) {
        return new KeyStroke(0, 0, false);
    }

    public int getKeyCode() { return keyCode; }
    public int getModifiers() { return modifiers; }
    public boolean isOnKeyRelease() { return onKeyRelease; }
}
