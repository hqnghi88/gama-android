package com.gama.nativeapp;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dalvik.system.DexClassLoader;

public class PluginManager {

    public static class Plugin {
        public final String name;
        public final File file;
        public final ClassLoader classLoader;

        Plugin(String name, File file, ClassLoader classLoader) {
            this.name = name;
            this.file = file;
            this.classLoader = classLoader;
        }
    }

    private static final String TAG = "PluginManager";
    private static final String PLUGINS_DIR = "plugins";

    public static File pluginsDir(Context context) {
        File dir = new File(context.getFilesDir(), PLUGINS_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            Log.w(TAG, "Could not create plugins dir " + dir.getAbsolutePath());
        }
        return dir;
    }

    public static List<File> installedFiles(Context context) {
        List<File> out = new ArrayList<>();
        File[] files = pluginsDir(context).listFiles();
        if (files != null) {
            for (File f : files) {
                String n = f.getName();
                if (f.isFile() && !n.startsWith(".")
                        && (n.endsWith(".jar") || n.endsWith(".dex"))) {
                    out.add(f);
                }
            }
        }
        return out;
    }

    public static List<Plugin> load(Context context) {
        List<File> files = installedFiles(context);
        if (files.isEmpty()) return new ArrayList<>();

        String optimizedDir = context.getCacheDir().getAbsolutePath();
        ClassLoader parent = context.getClassLoader();
        List<Plugin> plugins = new ArrayList<>();
        for (File f : files) {
            // DexClassLoader requires its input dex/jar files to be read-only (API 28+).
            if (f.canWrite()) f.setReadOnly();

            String name = symbolicName(f);
            if (name == null || name.isEmpty()) {
                Log.w(TAG, "Skipping plugin without a symbolic name: " + f.getName());
                continue;
            }
            ClassLoader loader;
            try {
                // Per-file loader so a bad file cannot break the other plugins.
                loader = new DexClassLoader(f.getAbsolutePath(), optimizedDir, null, parent);
                Log.i(TAG, "Built DexClassLoader for " + f.getName());
            } catch (Throwable t) {
                Log.e(TAG, "Skipping unloadable plugin " + f.getName() + ": " + t.getMessage());
                continue;
            }
            plugins.add(new Plugin(name, f, loader));
        }
        return plugins;
    }

    public static String symbolicName(File file) {
        String name = null;
        String fx = file.getName();
        if (fx.endsWith(".jar")) {
            try (ZipFile zip = new ZipFile(file)) {
                ZipEntry mf = zip.getEntry("META-INF/MANIFEST.MF");
                if (mf != null) {
                    Properties props = new Properties();
                    try (InputStream in = zip.getInputStream(mf)) {
                        props.load(in);
                    }
                    name = props.getProperty("Bundle-SymbolicName");
                }
            } catch (Exception e) {
                Log.w(TAG, "Could not read manifest of " + fx, e);
            }
        }
        if (name == null) name = bundleNameFromFile(fx);
        if (name != null) {
            int semi = name.indexOf(';');
            if (semi >= 0) name = name.substring(0, semi).trim();
            name = name.trim();
        }
        return name;
    }

    static String bundleNameFromFile(String fileName) {
        String n = fileName;
        int ext = n.lastIndexOf('.');
        if (ext >= 0) n = n.substring(0, ext);
        if (n.endsWith(".jar")) n = n.substring(0, n.length() - 4);
        if (n.endsWith(".dex")) n = n.substring(0, n.length() - 4);
        int idx = n.lastIndexOf('_');
        if (idx > 0 && idx < n.length() - 1 && Character.isDigit(n.charAt(idx + 1))) {
            n = n.substring(0, idx);
        }
        return n;
    }

    public static boolean isValidPlugin(File file) {
        String n = file.getName();
        if (n.endsWith(".dex")) return true;
        if (!n.endsWith(".jar")) return false;
        try (ZipFile zip = new ZipFile(file)) {
            return zip.getEntry("classes.dex") != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static File install(Context context, Uri uri) throws Exception {
        String displayName = queryDisplayName(context.getContentResolver(), uri);
        if (displayName == null || displayName.isEmpty()) displayName = "plugin.jar";
        if (!displayName.endsWith(".jar") && !displayName.endsWith(".dex")) displayName += ".jar";

        File pluginsDir = pluginsDir(context);
        File target = new File(pluginsDir, displayName);
        if (target.exists()) {
            target.setWritable(true);
            target.delete();
        }
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(target)) {
            if (in == null) throw new IllegalArgumentException("Cannot read the selected file");
            byte[] buf = new byte[64 * 1024];
            int r;
            while ((r = in.read(buf)) != -1) out.write(buf, 0, r);
        }

        if (!isValidPlugin(target)) {
            target.delete();
            throw new IllegalArgumentException(
                    "Not a GAMA extension: expected a plugin jar containing classes.dex");
        }

        String sym = symbolicName(target);
        if (sym == null || sym.isEmpty()) {
            target.delete();
            throw new IllegalArgumentException("Cannot determine the extension's symbolic name");
        }

        String ext = target.getName().endsWith(".dex") ? ".dex" : ".jar";
        File finalTarget = new File(pluginsDir, "plugin_" + sym + ext);
        if (finalTarget.exists()) {
            finalTarget.setWritable(true);
            finalTarget.delete();
        }
        if (!finalTarget.equals(target) && target.exists()) {
            if (!target.renameTo(finalTarget)) {
                Log.w(TAG, "Rename to " + finalTarget.getName() + " failed; keeping original name");
                finalTarget = target;
            }
        }
        return finalTarget;
    }

    private static String queryDisplayName(ContentResolver resolver, Uri uri) {
        try (android.database.Cursor c = resolver.query(uri,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to query display name for " + uri, e);
        }
        return null;
    }
}