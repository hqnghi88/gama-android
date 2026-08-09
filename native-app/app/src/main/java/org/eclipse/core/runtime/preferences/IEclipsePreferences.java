package org.eclipse.core.runtime.preferences;

public interface IEclipsePreferences {
    String get(String key, String def);
    void put(String key, String value);
    int getInt(String key, int def);
    void putInt(String key, int value);
    boolean getBoolean(String key, boolean def);
    void putBoolean(String key, boolean value);
    double getDouble(String key, double def);
    void putDouble(String key, double value);
    long getLong(String key, long def);
    void putLong(String key, long value);
    void remove(String key);
    void flush() throws Exception;
    void sync() throws Exception;
    String[] keys() throws Exception;
    String[] childrenNames() throws Exception;
    java.util.prefs.Preferences parent();
    java.util.prefs.Preferences node(String pathName);
    boolean nodeExists(String pathName) throws Exception;
    void removeNode() throws Exception;
    String name();
    String absolutePath();
    void clear();
    void putFloat(String key, float value);
    float getFloat(String key, float def);
    void putByteArray(String key, byte[] value);
    byte[] getByteArray(String key, byte[] def);
}
