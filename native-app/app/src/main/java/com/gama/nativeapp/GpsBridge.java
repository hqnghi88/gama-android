package com.gama.nativeapp;

import android.content.Context;
import android.location.Location;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks the device's GPS position and, whenever the phone has moved more than a
 * {@link #MIN_MOVE_METERS} from the last point that was downloaded, triggers an async
 * OpenStreetMap (Overpass) download of the surrounding area. The result is written to
 * {@code map.osm} inside the running model's directory and a monotonically increasing
 * version counter is bumped into {@code map.version} alongside it, so a GAML model can
 * detect that fresh geographic data is available and rebuild its 3D city.
 */
public final class GpsBridge {

    private static final String TAG = "GpsBridge";
    private static final float MIN_MOVE_METERS = 50f;
    private static final float OVERPASS_RADIUS_METERS = 100f;

    /** Last position that was successfully fetched as OSM. */
    private static volatile double lastFetchedLat = Double.NaN;
    private static volatile double lastFetchedLon = Double.NaN;

    /** Directory of the running model (where map.osm / map.version are written). */
    private static volatile File dataDir;

    /** Monotonic version bumped every time a fresh map is written. */
    private static final AtomicInteger VERSION = new AtomicInteger(0);
/** True once a first fix has been received. */
    private static volatile boolean hasFix = false;

    /** Last known fix, retained so a fetch can be triggered once the model dir is known. */
    private static volatile double pendingLat = Double.NaN;
    private static volatile double pendingLon = Double.NaN;

    /** True while a fetch is in progress, to avoid overlapping downloads. */
    private static volatile boolean fetchInFlight = false;

    private GpsBridge() {
    }

    /** Sets the directory (the model's folder) that receives map.osm / map.version. */
    public static void setDataDir(Context context, File modelDir) {
        File base = modelDir != null ? modelDir : context.getFilesDir();
        dataDir = base;
        // The first GPS fix often arrives before the model directory is known (during bootstrap),
        // so if a fix is pending and a fetch was never initiated, download the map now.
        if (hasFix && Double.isNaN(lastFetchedLat)) {
            fetch(pendingLat, pendingLon);
        }
    }

    /** The absolute path to map.osm, or null if not yet known. */
    public static File mapFile() {
        return dataDir == null ? null : new File(dataDir, "map.osm");
    }

    /** The current map version (bumped each time a fresh map is written). */
    public static int mapVersion() {
        return VERSION.get();
    }

    /** Called from the sensor listener on each new location fix. */
    public static void onLocation(Location location) {
        double lat = location.getLatitude();
        double lon = location.getLongitude();
        pendingLat = lat;
        pendingLon = lon;
        if (!hasFix) {
            hasFix = true;
            fetch(lat, lon);
            return;
        }
        float[] dist = new float[1];
        Location.distanceBetween(lastFetchedLat, lastFetchedLon, lat, lon, dist);
        if (dist[0] > MIN_MOVE_METERS) {
            fetch(lat, lon);
        }
    }

    /**
     * Ensures GAMA's global GIS CRS is WGS84 (EPSG:4326) so OSM lat/lon data is NOT reprojected into a
     * distant projected CRS (which triggers GAMA's "transform result may be N meters away" failure). The
     * authoritative value is set at bootstrap before any simulation is created; this call just reinforces it.
     */
    private static void setGamaCRSForLocation(double lat, double lon) {
        try {
            Class<?> external = Class.forName("gama.api.utils.prefs.GamaPreferences$External");
            Object targeted = external.getField("LIB_TARGETED").get(null);
            Object defaultCrs = external.getField("LIB_TARGET_CRS").get(null);
            targeted.getClass().getMethod("setValueNoCheckNoNotification", Object.class).invoke(targeted, false);
            defaultCrs.getClass().getMethod("setValueNoCheckNoNotification", Object.class).invoke(defaultCrs, 4326);
            android.util.Log.i(TAG, "set GAMA target CRS to EPSG:4326");
        } catch (Throwable e) {
            android.util.Log.w(TAG, "failed to set GAMA CRS preference", e);
        }
    }

    private static void fetch(double lat, double lon) {
        setGamaCRSForLocation(lat, lon);
        final File dir = dataDir;
        // The model directory may not be known yet; setDataDir() retries once it is.
        if (dir == null) {
            return;
        }
        if (fetchInFlight) {
            return;
        }
        float[] dist = new float[1];
        if (!Double.isNaN(lastFetchedLat)) {
            Location.distanceBetween(lastFetchedLat, lastFetchedLon, lat, lon, dist);
            if (dist[0] <= MIN_MOVE_METERS) {
                return;
            }
        }
        lastFetchedLat = lat;
        lastFetchedLon = lon;
        fetchInFlight = true;
        final File out = new File(dir, "map.osm");
        OverpassFetcher.fetchAsync(lat, lon, OVERPASS_RADIUS_METERS, out, success -> {
            fetchInFlight = false;
            if (success) {
                int v = VERSION.incrementAndGet();
                try {
                    java.io.FileWriter fw = new java.io.FileWriter(new File(dir, "map.version"));
                    fw.write(String.valueOf(v));
                    fw.flush();
                    fw.close();
                } catch (Exception e) {
                    android.util.Log.w(TAG, "write version failed", e);
                }
                android.util.Log.i(TAG, "fresh map written, version=" + v);
            } else {
                // Reset so that a subsequent GPS report or setDataDir() can retry the download.
                lastFetchedLat = Double.NaN;
                lastFetchedLon = Double.NaN;
            }
        });
    }
}
