package com.gama.nativeapp;

import java.io.IOException;
import java.io.OutputStream;
import java.util.prefs.BackingStoreException;
import java.util.prefs.NodeChangeListener;
import java.util.prefs.PreferenceChangeListener;
import java.util.prefs.Preferences;
import java.util.prefs.PreferencesFactory;

public class NoOpPreferencesFactory implements PreferencesFactory {
    private static final Preferences NO_OP = new NoOpPreferences();

    @Override
    public Preferences userRoot() { return NO_OP; }

    @Override
    public Preferences systemRoot() { return NO_OP; }

    private static class NoOpPreferences extends Preferences {
        @Override public void put(String key, String value) {}
        @Override public String get(String key, String def) { return def; }
        @Override public void remove(String key) {}
        @Override public void clear() {}
        @Override public void putInt(String key, int value) {}
        @Override public int getInt(String key, int def) { return def; }
        @Override public void putLong(String key, long value) {}
        @Override public long getLong(String key, long def) { return def; }
        @Override public void putBoolean(String key, boolean value) {}
        @Override public boolean getBoolean(String key, boolean def) { return def; }
        @Override public void putFloat(String key, float value) {}
        @Override public float getFloat(String key, float def) { return def; }
        @Override public void putDouble(String key, double value) {}
        @Override public double getDouble(String key, double def) { return def; }
        @Override public void putByteArray(String key, byte[] value) {}
        @Override public byte[] getByteArray(String key, byte[] def) { return def; }
        @Override public String[] keys() { return new String[0]; }
        @Override public String[] childrenNames() { return new String[0]; }
        @Override public Preferences parent() { return null; }
        @Override public Preferences node(String pathName) { return this; }
        @Override public boolean nodeExists(String pathName) { return false; }
        @Override public void removeNode() {}
        @Override public String name() { return "no-op"; }
        @Override public String absolutePath() { return "/no-op"; }
        @Override public boolean isUserNode() { return true; }
        @Override public void flush() {}
        @Override public void sync() {}
        @Override public void addPreferenceChangeListener(PreferenceChangeListener listener) {}
        @Override public void removePreferenceChangeListener(PreferenceChangeListener listener) {}
        @Override public void addNodeChangeListener(NodeChangeListener listener) {}
        @Override public void removeNodeChangeListener(NodeChangeListener listener) {}
        @Override public void exportNode(OutputStream out) throws IOException {}
        @Override public void exportSubtree(OutputStream out) throws IOException {}
        @Override public String toString() { return "NoOpPreferences"; }
    }
}
