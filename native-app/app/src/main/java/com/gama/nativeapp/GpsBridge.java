package com.gama.nativeapp;

import android.content.Context;
import android.location.Location;

import java.io.File;

/**
 * Tracks the device's GPS position for future use. The download of the surrounding
 * OpenStreetMap data no longer happens here: the GAML model fetches it directly from the
 * Overpass API with the {@code osm_file} operator, so this class only records the location.
 */
public final class GpsBridge {

    private static final String TAG = "GpsBridge";

    /** Directory of the running model (kept for API compatibility). */
    private static volatile File dataDir;

    /** Last known fix. */
    private static volatile Location lastFix;

    private GpsBridge() {
    }

    /** Sets the directory (the model's folder) that used to receive map.osm / map.version. */
    public static void setDataDir(Context context, File modelDir) {
        dataDir = modelDir != null ? modelDir : context.getFilesDir();
    }

    /** The last GPS fix, or null if none was received yet. */
    public static Location lastFix() {
        return lastFix;
    }

    /** Called from the sensor listener on each new location fix. */
    public static void onLocation(Location location) {
        lastFix = location;
        android.util.Log.d(TAG,
                "GPS fix " + location.getLatitude() + "," + location.getLongitude()
                        + " (OSM download is handled by the GAML model)");
    }
}