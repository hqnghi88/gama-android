package javax.swing.event;

import java.io.Serializable;
import java.lang.reflect.Array;

/**
 * Minimal EventListenerList implementation for Android/JFreeChart compatibility.
 * Android's desugared version is missing getListenerList().
 */
public class EventListenerList implements Serializable {
    private static final Object[] EMPTY_LIST = new Object[0];
    protected transient Object[] listenerList = EMPTY_LIST;

    public synchronized Object[] getListenerList() {
        return listenerList.clone();
    }

    public synchronized <T extends java.util.EventListener> void add(Class<T> t, T l) {
        if (l == null) return;
        if (!t.isInstance(l)) throw new IllegalArgumentException("Listener " + l + " is not of type " + t);
        int i = listenerList.length;
        Object[] tmp = new Object[i + 2];
        if (i > 0) System.arraycopy(listenerList, 0, tmp, 0, i);
        tmp[i] = t;
        tmp[i + 1] = l;
        listenerList = tmp;
    }

    public synchronized <T extends java.util.EventListener> void remove(Class<T> t, T l) {
        if (l == null) return;
        int i = listenerList.length - 2;
        while (i >= 0) {
            if (listenerList[i] == t && listenerList[i + 1].equals(l)) {
                Object[] tmp = new Object[i];
                if (i > 0) System.arraycopy(listenerList, 0, tmp, 0, i);
                if (i < listenerList.length - 2)
                    System.arraycopy(listenerList, i + 2, tmp, i, listenerList.length - i - 2);
                listenerList = tmp;
                return;
            }
            i -= 2;
        }
    }

    @SuppressWarnings("unchecked")
    public synchronized <T extends java.util.EventListener> T[] getListeners(Class<T> t) {
        int n = 0;
        for (int i = 0; i < listenerList.length; i += 2) {
            if (listenerList[i] == t) n++;
        }
        @SuppressWarnings("rawtypes")
        T[] result = (T[]) Array.newInstance(t, n);
        int j = 0;
        for (int i = 0; i < listenerList.length; i += 2) {
            if (listenerList[i] == t) {
                result[j++] = (T) listenerList[i + 1];
            }
        }
        return result;
    }

    public int getListenerCount() {
        return listenerList.length / 2;
    }

    public int getListenerCount(Class<?> t) {
        int n = 0;
        for (int i = 0; i < listenerList.length; i += 2) {
            if (listenerList[i] == t) n++;
        }
        return n;
    }

    public String toString() {
        Object[] lList = listenerList;
        StringBuilder s = new StringBuilder();
        s.append("EventListenerList: ");
        for (int i = 0; i < lList.length; i += 2) {
            s.append((Class<?>) lList[i]).append("#" + lList[i + 1] + " ");
        }
        return s.toString();
    }
}
