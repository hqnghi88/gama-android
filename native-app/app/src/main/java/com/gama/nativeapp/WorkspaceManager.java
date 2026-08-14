package com.gama.nativeapp;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Manages the user's personal workspace where they can create, edit and save
 * their own GAML models. The workspace lives in app-private storage so it needs
 * no permissions and survives across sessions.
 *
 * The location can be changed by the user to point at a folder on the device's
 * local storage (selected via the Storage Access Framework). In that case the
 * chosen folder is resolved to a real filesystem path and used as the workspace
 * root; all file operations remain java.io.File-based.
 *
 * Layout follows GAMA's project convention: &lt;project&gt;/models/*.gaml plus
 * optional &lt;project&gt;/includes and &lt;project&gt;/images.
 */
public final class WorkspaceManager {

    private static final String TAG = "WorkspaceManager";

    public static final String DIR_NAME = "workspace";

    private static final String PREFS_NAME = "gama_workspace_prefs";
    private static final String KEY_ROOT_PATH = "workspace_root_path";
    private static final String VALUE_DEFAULT = "__default__";

    private WorkspaceManager() {}

    private static volatile String engineWorkspacePath;

    /** Points the GAMA engine's workspace root (used e.g. for the download cache)
     *  at the real Android workspace folder. Set during engine bootstrap. */
    public static void setEngineWorkspacePath(String path) {
        engineWorkspacePath = path;
    }

    /** Absolute path the engine should treat as the workspace root. Falls back to
     *  the default app-private workspace when bootstrap has not run yet. */
    public static String engineWorkspacePath() {
        if (engineWorkspacePath != null) return engineWorkspacePath;
        Context ctx = GamaApplication.getAppContext();
        if (ctx != null) return defaultRootPath(ctx);
        return new File(System.getProperty("user.home", "/"), DIR_NAME).getAbsolutePath();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static File defaultRoot(Context context) {
        File root = new File(context.getFilesDir(), DIR_NAME);
        if (!root.exists()) root.mkdirs();
        return root;
    }

    /**
     * Returns the current workspace root. If the user has chosen a custom folder
     * on the device, that folder is used. If it is no longer present or valid,
     * we transparently fall back to the app-private storage so the workspace is
     * always usable.
     */
    public static File workspaceRoot(Context context) {
        String custom = getPrefs(context).getString(KEY_ROOT_PATH, VALUE_DEFAULT);
        if (VALUE_DEFAULT.equals(custom) || TextUtils.isEmpty(custom)) return defaultRoot(context);
        File root = new File(custom);
        if (!root.exists() || !root.isDirectory()) {
            Log.w(TAG, "configured workspace root no longer valid: " + custom + " -> falling back to default");
            return defaultRoot(context);
        }
        return root;
    }

    /** Returns the absolute path currently configured as the workspace root. */
    public static String getConfigRootPath(Context context) {
        String custom = getPrefs(context).getString(KEY_ROOT_PATH, VALUE_DEFAULT);
        if (VALUE_DEFAULT.equals(custom) || TextUtils.isEmpty(custom)) return defaultRoot(context).getAbsolutePath();
        return custom;
    }

    /** Persists a writable local root chosen by the user. */
    public static void setWorkspaceRoot(Context context, File root) {
        getPrefs(context).edit().putString(KEY_ROOT_PATH, root.getAbsolutePath()).apply();
    }

    /** Resets the workspace to the app-private storage location. */
    public static void resetWorkspaceRoot(Context context) {
        getPrefs(context).edit().putString(KEY_ROOT_PATH, VALUE_DEFAULT).apply();
    }

    /**
     * Tries to resolve a Storage Access Framework tree URI to a real local
     * filesystem path (java.io.File). Only local storage providers are
     * supported: the primary shared volume and secondary/external volumes.
     * Cloud-only providers (Drive, etc.) cannot be mapped to a real path and
     * return null.
     */
    public static String resolveLocalPathFromTreeUri(Context context, Uri treeUri) {
        if (treeUri == null) return null;
        String authority = treeUri.getAuthority();
        String docId;
        try {
            docId = DocumentsContract.getTreeDocumentId(treeUri);
        } catch (Exception e) {
            return null;
        }
        if (docId == null) return null;

        if ("com.android.externalstorage.documents".equals(authority)) {
            // "primary:..." or "<volumeSerial>:..."
            if (docId.startsWith("primary:")) {
                String rest = docId.substring("primary:".length());
                return new File(Environment.getExternalStorageDirectory(), rest).getAbsolutePath();
            }
            int sep = docId.indexOf(':');
            if (sep > 0) {
                String volume = docId.substring(0, sep);
                String rest = docId.substring(sep + 1);
                File storage = new File("/storage");
                File[] vols = storage.listFiles();
                if (vols != null) {
                    for (File v : vols) {
                        if (v.getName().equals(volume)) return new File(v, rest).getAbsolutePath();
                    }
                }
            }
            return null;
        }
        if ("com.android.providers.downloads.documents".equals(authority)) {
            int sep = docId.indexOf(':');
            String rest = sep >= 0 ? docId.substring(sep + 1) : docId;
            return new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), rest).getAbsolutePath();
        }
        return null;
    }

    /** Quick probe that a folder is writable (create + delete a probe file). */
    public static boolean isWritable(File dir) {
        if (dir == null) return false;
        try {
            if (!dir.exists() && !dir.mkdirs()) return false;
            File probe = new File(dir, ".gama_probe");
            if (!probe.createNewFile()) return false;
            if (!probe.delete()) return false;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Returns the absolute path of the default app-private workspace. */
    public static String defaultRootPath(Context context) {
        return defaultRoot(context).getAbsolutePath();
    }

    public static boolean isValidIdentifier(String name) {
        if (name == null || name.isEmpty()) return false;
        if (!Character.isLetter(name.charAt(0))) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') return false;
        }
        return true;
    }

    public static String sanitizeModelName(String name) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_') {
                sb.append(c);
            } else if (sb.length() > 0) {
                sb.append('_');
            }
        }
        String s = sb.toString().replaceAll("_+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "model" : s;
    }

    public static final String MODEL_TEMPLATE =
            "model %s\n" +
            "\n" +
            "global {\n" +
            "    init {\n" +
            "        create demo_species number: 10;\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "species demo_species {\n" +
            "    aspect base {\n" +
            "        draw circle(3) color: #orange;\n" +
            "    }\n" +
            "}\n" +
            "\n" +
            "experiment main type: gui {\n" +
            "    output {\n" +
            "        display screen type: opengl {\n" +
            "            species demo_species aspect: base;\n" +
            "        }\n" +
            "    }\n" +
            "}\n";

    public static File newModel(Context context, File parentDir, String name) throws IOException {
        File dir = parentDir != null ? parentDir : workspaceRoot(context);
        dir.mkdirs();
        File file = new File(dir, name + ".gaml");
        writeText(file, String.format(MODEL_TEMPLATE, name));
        return file;
    }

    public static File newFolder(Context context, File parentDir, String name) {
        File dir = parentDir != null ? parentDir : workspaceRoot(context);
        File folder = new File(dir, name);
        folder.mkdirs();
        return folder;
    }

    /** Unique path inside parentDir for a given base name (e.g. "Model (2).gaml"). */
    public static File uniqueFile(File parentDir, String baseName) {
        File f = new File(parentDir, baseName);
        if (!f.exists()) return f;
        int dot = baseName.lastIndexOf('.');
        String stem = dot > 0 ? baseName.substring(0, dot) : baseName;
        String ext = dot > 0 ? baseName.substring(dot) : "";
        for (int i = 2; ; i++) {
            File candidate = new File(parentDir, stem + " (" + i + ")" + ext);
            if (!candidate.exists()) return candidate;
        }
    }

    public static void writeText(File file, String content) throws IOException {
        file.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(content.getBytes("UTF-8"));
        }
    }

    public static String readText(File file) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = fis.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
        }
        return sb.toString();
    }

    public static boolean deleteRecursively(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) deleteRecursively(child);
            }
        }
        return file.delete();
    }

    public static boolean copyRecursively(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.exists() && !dst.mkdirs()) return false;
            File[] children = src.listFiles();
            if (children == null) return true;
            for (File child : children) copyRecursively(child, new File(dst, child.getName()));
            return true;
        }
        dst.getParentFile().mkdirs();
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return true;
    }

    /**
     * Copies a whole library project (the extracted directory under the app cache that
     * corresponds to a jar entry path like "models/X/models/Model.gaml") into the user's
     * workspace so they own a fully self-contained, editable copy. Returns the copied
     * model file (unique-ified), or null on failure.
     */
    public static File copyProjectToWorkspace(Context context, String jarEntryPath) {
        File cacheDir = context.getCacheDir();
        File srcModel = new File(cacheDir, jarEntryPath);
        if (!srcModel.exists()) return null;

        int idx = jarEntryPath.lastIndexOf("/models/");
        String projectKey = (idx >= 0 ? jarEntryPath.substring(0, idx) : "project");
        String relativeModel = idx >= 0 ? jarEntryPath.substring(idx + 1) : jarEntryPath;

        File workspace = workspaceRoot(context);
        File targetProject = new File(workspace, sanitizePath(projectKey));
        if (targetProject.exists()) {
            int n = 2;
            while (new File(workspace, sanitizePath(projectKey) + " (" + n + ")").exists()) n++;
            targetProject = new File(workspace, sanitizePath(projectKey) + " (" + n + ")");
        }

        try {
            File srcProject = idx >= 0 ? new File(cacheDir, projectKey) : srcModel.getParentFile();
            copyRecursively(srcProject, targetProject);
        } catch (IOException e) {
            Log.e(TAG, "copyProjectToWorkspace failed", e);
            return null;
        }

        return new File(targetProject, relativeModel);
    }

    private static String sanitizePath(String path) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == ' ') {
                sb.append(c);
            } else {
                sb.append('_');
            }
        }
        return sb.toString().trim();
    }
}
