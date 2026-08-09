package javax.swing;

import java.io.Serializable;
import java.util.HashMap;

public class InputMap implements Serializable {
    private java.util.Map<Object, Object> map = new HashMap<>();
    public InputMap() {}
    public void put(javax.swing.KeyStroke keyStroke, Object actionMapKey) { map.put(keyStroke, actionMapKey); }
    public Object get(javax.swing.KeyStroke keyStroke) { return map.get(keyStroke); }
    public void remove(javax.swing.KeyStroke keyStroke) { map.remove(keyStroke); }
    public javax.swing.KeyStroke[] allKeys() { return map.keySet().toArray(new javax.swing.KeyStroke[0]); }
}
