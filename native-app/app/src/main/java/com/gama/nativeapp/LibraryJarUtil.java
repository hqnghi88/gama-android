package com.gama.nativeapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class LibraryJarUtil {

    public static final String JAR_NAME = "gama.library.jar";

    private static final String TAG = "LibraryJarUtil";

    private LibraryJarUtil() {}

    private static final String PREFS = "gama_library_cache";
    private static final String KEY_VERSION = "cached_version_code";

    public static File ensureCached(Context context) {
        File cacheJar = new File(context.getCacheDir(), JAR_NAME);
        boolean fresh = cacheJar.exists();
        if (fresh) {
            long apkTime = new File(context.getApplicationInfo().sourceDir).lastModified();
            long cacheTime = cacheJar.lastModified();
            fresh = cacheTime >= apkTime;
            // Also refresh when the embedded jar's size no longer matches the cached copy,
            // so library content changes (e.g. newly injected models) always take effect even
            // when the APK file's modification time is preserved by `adb install -r`.
            if (fresh) {
                long assetSize = -1;
                try {
                    assetSize = context.getAssets().openFd(JAR_NAME).getLength();
                } catch (Exception ignored) {
                }
                if (assetSize > 0 && assetSize != cacheJar.length()) fresh = false;
            }
        }
        // A new app version (versionCode bump) always carries a potentially different embedded
        // jar, so wipe any previously cached/extracted library and re-extract from scratch.
        if (isAppVersionNew(context)) {
            fresh = false;
            clearLibraryCache(context);
        }
        if (!fresh) {
            try (InputStream is = context.getAssets().open(JAR_NAME);
                 FileOutputStream fos = new FileOutputStream(cacheJar)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
            } catch (Exception e) {
                Log.e(TAG, "Failed to refresh library jar", e);
                if (!cacheJar.exists()) return null;
            }
        }
        return cacheJar.exists() ? cacheJar : null;
    }

    /** True when this app version differs from the one that populated the library cache. */
    private static boolean isAppVersionNew(Context context) {
        try {
            int current = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0).versionCode;
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            int cached = prefs.getInt(KEY_VERSION, -1);
            if (cached != current) {
                prefs.edit().putInt(KEY_VERSION, current).apply();
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Delete the cached jar and any previously extracted library files. */
    private static void clearLibraryCache(Context context) {
        try {
            File cacheDir = context.getCacheDir();
            File cacheJar = new File(cacheDir, JAR_NAME);
            if (cacheJar.exists()) cacheJar.delete();
            File stamp = stampFile(context);
            if (stamp.exists()) stamp.delete();
            File modelsDir = new File(cacheDir, "models");
            if (modelsDir.exists()) deleteRecursively(modelsDir);
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear library cache", e);
        }
    }

    private static void deleteRecursively(File f) {
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteRecursively(c);
            }
        }
        f.delete();
    }

    private static File stampFile(Context context) {
        return new File(context.getCacheDir(), JAR_NAME + ".version");
    }

    /** True when previously extracted library files may no longer match the cached jar. */
    public static boolean isExtractionStale(Context context) {
        try {
            File cacheJar = new File(context.getCacheDir(), JAR_NAME);
            if (!cacheJar.exists()) return true;
            File stamp = stampFile(context);
            if (!stamp.exists()) return true;
            long jarTime = cacheJar.lastModified();
            long stamped = Long.parseLong(new String(java.nio.file.Files.readAllBytes(stamp.toPath())).trim());
            return stamped != jarTime;
        } catch (Exception e) {
            return true;
        }
    }

    /** Record that the currently cached jar has been fully extracted. */
    public static void markExtracted(Context context) {
        try {
            File cacheJar = new File(context.getCacheDir(), JAR_NAME);
            java.nio.file.Files.write(stampFile(context).toPath(),
                    String.valueOf(cacheJar.lastModified()).getBytes());
        } catch (Exception e) {
            Log.e(TAG, "Failed to write extraction stamp", e);
        }
    }
}
