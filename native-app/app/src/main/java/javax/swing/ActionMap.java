package javax.swing;

import java.io.Serializable;
import java.util.HashMap;

public class ActionMap implements Serializable {
    private java.util.Map<Object, Action> map = new HashMap<>();
    public ActionMap() {}
    public void put(Object key, Action a) { map.put(key, a); }
    public Action get(Object key) { return map.get(key); }
    public void remove(Object key) { map.remove(key); }
    public Action[] allActions() { return map.values().toArray(new Action[0]); }
}
