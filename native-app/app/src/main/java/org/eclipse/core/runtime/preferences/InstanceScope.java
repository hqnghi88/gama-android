package org.eclipse.core.runtime.preferences;

import java.util.HashMap;
import java.util.Map;

public class InstanceScope implements IScopeContext {
    public static final IScopeContext INSTANCE = new InstanceScope();

    @Override
    public IEclipsePreferences getNode(String qualifier) {
        return new MemPreferences(qualifier);
    }

    private static class MemPreferences implements IEclipsePreferences {
        private final String qualifier;
        private final Map<String, String> store = new HashMap<>();

        MemPreferences(String qualifier) { this.qualifier = qualifier; }

        @Override public String get(String key, String def) { return store.containsKey(key) ? store.get(key) : def; }
        @Override public void put(String key, String value) { store.put(key, value); }
        @Override public int getInt(String key, int def) { String v = store.get(key); return v != null ? Integer.parseInt(v) : def; }
        @Override public void putInt(String key, int value) { store.put(key, String.valueOf(value)); }
        @Override public boolean getBoolean(String key, boolean def) { String v = store.get(key); return v != null ? Boolean.parseBoolean(v) : def; }
        @Override public void putBoolean(String key, boolean value) { store.put(key, String.valueOf(value)); }
        @Override public double getDouble(String key, double def) { String v = store.get(key); return v != null ? Double.parseDouble(v) : def; }
        @Override public void putDouble(String key, double value) { store.put(key, String.valueOf(value)); }
        @Override public long getLong(String key, long def) { String v = store.get(key); return v != null ? Long.parseLong(v) : def; }
        @Override public void putLong(String key, long value) { store.put(key, String.valueOf(value)); }
        @Override public void remove(String key) { store.remove(key); }
        @Override public void flush() {}
        @Override public void sync() {}
        @Override public String[] keys() { return store.keySet().toArray(new String[0]); }
        @Override public String[] childrenNames() { return new String[0]; }
        @Override public java.util.prefs.Preferences parent() { return null; }
        @Override public java.util.prefs.Preferences node(String pathName) { return null; }
        @Override public boolean nodeExists(String pathName) { return false; }
        @Override public void removeNode() {}
        @Override public String name() { return qualifier; }
        @Override public String absolutePath() { return "/" + qualifier; }
        @Override public void clear() { store.clear(); }
        @Override public void putFloat(String key, float value) { store.put(key, String.valueOf(value)); }
        @Override public float getFloat(String key, float def) { String v = store.get(key); return v != null ? Float.parseFloat(v) : def; }
        @Override public void putByteArray(String key, byte[] value) {}
        @Override public byte[] getByteArray(String key, byte[] def) { return def; }
    }
}
