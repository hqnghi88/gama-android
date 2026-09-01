package com.gama.nativeapp;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Downloads the OpenStreetMap area around a GPS fix from the Overpass API and writes the
 * result as standard OSM XML to a file that the GAML {@code osm_file} operator can import.
 */
public final class OverpassFetcher {

    private static final String TAG = "OverpassFetcher";

    private static final String OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter";

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "overpass-download");
        t.setDaemon(true);
        return t;
    });

    public interface Callback {
        void onResult(boolean success);
    }

    private OverpassFetcher() {
    }

    /**
     * Downloads OSM data (buildings + roads) within {@code radiusMeters} of (lat, lon) to
     * {@code outFile}, invoking {@code callback} on the main thread when finished.
     */
    public static void fetchAsync(double lat, double lon, double radiusMeters, File outFile, Callback callback) {
        EXECUTOR.execute(() -> {
            boolean ok = download(lat, lon, radiusMeters, outFile);
            new Handler(Looper.getMainLooper()).post(() -> callback.onResult(ok));
        });
    }

    private static boolean download(double lat, double lon, double radiusMeters, File outFile) {
        String query = "[out:xml][timeout:40];("
                + "way[\"building\"](around:" + (long) radiusMeters + "," + lat + "," + lon + ");"
                + "way[\"highway\"](around:" + (long) radiusMeters + "," + lat + "," + lon + ");"
                + ");out body;>;out skel qt;";
        Exception lastError = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                String xml = doPost(query);
                // Overpass can return a 200 wrapper page even when the query was rejected; guard against
                // HTML responses and empty payloads.
                if (xml == null || !xml.contains("<osm")) {
                    Log.w(TAG, "overpass returned non-OSM payload (attempt " + attempt + ")");
                    Thread.sleep(2000L * attempt);
                    continue;
                }
                xml = ensureOsmVersions(xml);
                // With GAMA's target CRS pinned to WGS84 (EPSG:4326) the osm_file loader does NO reprojection,
                // so coordinates pass through verbatim. We therefore project the raw WGS84 lat/lon into a local
                // metric grid anchored at the GPS fix: the small meter values center the 3D city near the origin,
                // making it visible and naturally sized in the world (instead of sitting ~122 degrees away at
                // WGS84 scale where the default camera cannot see it).
                xml = projectToLocalMeters(xml, lat, lon);
                File parent = outFile.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    Log.w(TAG, "could not create dir " + parent);
                    return false;
                }
                try (FileOutputStream fos = new FileOutputStream(outFile)) {
                    fos.write(xml.getBytes(StandardCharsets.UTF_8));
                }
                Log.i(TAG, "downloaded " + outFile.length() + " bytes to " + outFile);
                return true;
            } catch (Exception e) {
                lastError = e;
                Log.w(TAG, "overpass fetch attempt " + attempt + " failed", e);
                try { Thread.sleep(2000L * attempt); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
        }
        Log.e(TAG, "giving up on overpass after retries", lastError);
        return false;
    }

    /** Performs one POST to the Overpass interpreter and returns the raw response body. */
    private static String doPost(String query) throws Exception {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(OVERPASS_ENDPOINT);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(90000);
            conn.setInstanceFollowRedirects(true);
            conn.getOutputStream().write(("data=" + java.net.URLEncoder.encode(query, "UTF-8"))
                    .getBytes(StandardCharsets.UTF_8));
            int code = conn.getResponseCode();
            java.io.InputStream in = code == HttpURLConnection.HTTP_OK
                    ? conn.getInputStream() : conn.getErrorStream();
            if (in == null) return null;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) != -1) { sb.append(buf, 0, n); }
                return sb.toString();
            }
        } finally {
            if (conn != null) { conn.disconnect(); }
        }
    }

    /**
     * The OSM osmosis parser used by GAML requires every {@code <node>}, {@code <way>} and
     * {@code <relation>} element to declare a {@code version} attribute. Overpass responses can
     * omit it, so inject {@code version="1"} into any element start tag that lacks one.
     */
    private static String ensureOsmVersions(String xml) {
        Pattern tagRe = Pattern.compile("<(node|way|relation)([^>]*)>");
        Matcher m = tagRe.matcher(xml);
        StringBuilder sb = new StringBuilder(xml.length() + 4096);
        int last = 0;
        while (m.find()) {
            sb.append(xml, last, m.start());
            String name = m.group(1);
            String attrs = m.group(2);
            if (!attrs.contains("version")) {
                if (attrs.trim().endsWith("/")) {
                    String inner = attrs.substring(0, attrs.lastIndexOf('/'));
                    sb.append('<').append(name).append(inner).append(" version=\"1\"/>");
                } else {
                    sb.append('<').append(name).append(attrs).append(" version=\"1\">");
                }
            } else {
                sb.append(m.group());
            }
            last = m.end();
        }
        sb.append(xml, last, xml.length());
        return sb.toString();
    }

    /**
     * Converts each {@code <node>}'s WGS84 lat/lon into a local metric grid anchored at the GPS fix
     * (lat0, lon0) using an equirectangular approximation. Because GAMA is configured to use WGS84 as its
     * target CRS (no reprojection), these local meter values reach the model verbatim and center the city
     * near the world origin at a natural, viewable scale.
     */
    private static String projectToLocalMeters(String xml, double lat0, double lon0) {
        final double R = 6371000.0;
        final double cosLat = Math.cos(Math.toRadians(lat0));
        final double mPerDegLat = R * Math.PI / 180.0;
        final double mPerDegLon = mPerDegLat * cosLat;
        Pattern nodeRe = Pattern.compile("<node\\b([^>]*?)(/?)>");
        Matcher m = nodeRe.matcher(xml);
        StringBuilder sb = new StringBuilder(xml.length() + 4096);
        int last = 0;
        while (m.find()) {
            sb.append(xml, last, m.start());
            String attrs = m.group(1);
            String slash = m.group(2);
            boolean selfClose = "/".equals(slash);
            if (!selfClose && attrs.trim().endsWith("/")) {
                attrs = attrs.substring(0, attrs.lastIndexOf('/'));
                selfClose = true;
            }
            String transformed = transformNodeAttrs(attrs, lat0, lon0, mPerDegLat, mPerDegLon);
            sb.append("<node").append(transformed);
            sb.append(selfClose ? "/>" : ">");
            last = m.end();
        }
        sb.append(xml, last, xml.length());
        return sb.toString();
    }

    private static String transformNodeAttrs(String attrs, double lat0, double lon0,
            double mPerDegLat, double mPerDegLon) {
        double lat = Double.NaN;
        double lon = Double.NaN;
        Matcher lm = Pattern.compile("lat\\s*=\\s*\"([-0-9.]+)\"").matcher(attrs);
        if (lm.find()) { lat = Double.parseDouble(lm.group(1)); }
        Matcher lom = Pattern.compile("lon\\s*=\\s*\"([-0-9.]+)\"").matcher(attrs);
        if (lom.find()) { lon = Double.parseDouble(lom.group(1)); }
        if (Double.isNaN(lat) || Double.isNaN(lon)) { return attrs; }
        double x = (lon - lon0) * mPerDegLon;
        double y = (lat - lat0) * mPerDegLat;
        String res = attrs.replaceFirst("lat\\s*=\\s*\"[^\"]*\"", "lat=\"" + fmt(y) + "\"");
        res = res.replaceFirst("lon\\s*=\\s*\"[^\"]*\"", "lon=\"" + fmt(x) + "\"");
        return res;
    }

    private static String fmt(double v) {
        return String.format(java.util.Locale.US, "%.2f", v);
    }
}
