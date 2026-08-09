package com.gama.nativeapp;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;

public final class LibraryJarUtil {

    public static final String JAR_NAME = "gama.library.jar";

    private static final String TAG = "LibraryJarUtil";

    private LibraryJarUtil() {}

    public static File ensureCached(Context context) {
        File cacheJar = new File(context.getCacheDir(), JAR_NAME);
        boolean fresh = cacheJar.exists();
        if (fresh) {
            long apkTime = new File(context.getApplicationInfo().sourceDir).lastModified();
            long cacheTime = cacheJar.lastModified();
            fresh = cacheTime >= apkTime;
        }
        if (!fresh) {
            try (InputStream is = context.getAssets().open(JAR_NAME);
                 FileOutputStream fos = new FileOutputStream(cacheJar)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                Log.i(TAG, "Refreshed library jar from assets");
            } catch (Exception e) {
                Log.e(TAG, "Failed to refresh library jar", e);
                if (!cacheJar.exists()) return null;
            }
        }
        return cacheJar.exists() ? cacheJar : null;
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
