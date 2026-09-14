package com.gama.nativeapp;

import android.content.Context;
import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import androidx.documentfile.provider.DocumentFile;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;

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
    private static final String KEY_TREE_URI_TREE = "workspace_tree_uri_tree";
    private static final String KEY_TREE_URI_DOCUMENT = "workspace_tree_uri_document";
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
     * Returns the current workspace root. If the user has chosen a folder on the
     * device via the Storage Access Framework, that folder is mirrored (see the
     * sync* methods below) into the app-private workspace, which is what the
     * engine consumes as a real, permission-free path. Otherwise a previously
     * configured custom real path (API 26-28) is honoured; if it is no longer
     * valid we fall back to the app-private storage.
     */
    public static File workspaceRoot(Context context) {
        if (getTreeUri(context) != null) return defaultRoot(context);
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

    /** Resets the workspace to the app-private storage location. */
    public static void resetWorkspaceRoot(Context context) {
        getPrefs(context).edit().putString(KEY_ROOT_PATH, VALUE_DEFAULT).apply();
        clearTreeUri(context);
    }

    /** Persists the SAF tree URI granted for the chosen workspace folder. */
    public static void setTreeUri(Context context, String treeUri) {
        getPrefs(context).edit().putString(KEY_TREE_URI_TREE, treeUri != null ? treeUri : "").apply();
    }

    /** Returns the persisted SAF tree URI as a Uri, or null if none was granted. */
    public static Uri getTreeUri(Context context) {
        String s = getPrefs(context).getString(KEY_TREE_URI_TREE, "");
        if ("".equals(s)) return null;
        return Uri.parse(s);
    }

    /** Clears the persisted SAF tree URI (e.g. when the user resets the workspace). */
    public static void clearTreeUri(Context context) {
        getPrefs(context).edit().putString(KEY_TREE_URI_TREE, "").apply();
    }

    /** True when the workspace is bound to a SAF-picked device folder. */
    public static boolean isSafeWorkspace(Context context) {
        return getTreeUri(context) != null;
    }

    /** The app-private directory the SAF folder is mirrored into. */
    public static File mirrorRoot(Context context) {
        return defaultRoot(context);
    }

    /** Pulls the entire SAF tree into the local mirror (content-URI reads, no
     *  storage permissions needed). Off the UI thread. */
    public static void syncPull(Context context) {
        Uri tree = getTreeUri(context);
        if (tree == null) return;
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, tree);
            if (root == null) return;
            pullTree(context.getContentResolver(), root, mirrorRoot(context));
            Log.i(TAG, "syncPull complete from " + tree);
        } catch (Exception e) {
            Log.e(TAG, "syncPull failed", e);
        }
    }

    /** Pushes one local file (or directory subtree) into the SAF tree. */
    public static void syncPush(Context context, File local) {
        Uri tree = getTreeUri(context);
        if (tree == null || local == null) return;
        String rel = relativePath(mirrorRoot(context), local);
        if (rel == null) return;
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, tree);
            if (root == null) return;
            String[] segs = rel.split("/");
            DocumentFile parent = navigate(root, segs, 0, segs.length - 1, true);
            if (parent == null) return;
            pushNode(context.getContentResolver(), parent, local);
            Log.i(TAG, "syncPush " + rel + " (" + local.length() + "b)");
        } catch (Exception e) {
            Log.e(TAG, "syncPush failed for " + local, e);
        }
    }

    /** Deletes one local file/dir from the SAF tree too. */
    public static void syncPushDelete(Context context, File local) {
        Uri tree = getTreeUri(context);
        if (tree == null || local == null) return;
        String rel = relativePath(mirrorRoot(context), local);
        if (rel == null) return;
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, tree);
            if (root == null) return;
            String[] segs = rel.split("/");
            DocumentFile parent = navigate(root, segs, 0, segs.length - 1, false);
            if (parent == null) return;
            DocumentFile node = parent.findFile(segs[segs.length - 1]);
            if (node != null && node.delete()) Log.i(TAG, "syncPushDelete " + rel);
        } catch (Exception e) {
            Log.e(TAG, "syncPushDelete failed for " + local, e);
        }
    }

    /** Renames the local file and mirrors the rename into the SAF tree. */
    public static void syncPushRename(Context context, File oldLocal, String newName) {
        Uri tree = getTreeUri(context);
        if (tree == null || oldLocal == null) return;
        String rel = relativePath(mirrorRoot(context), oldLocal);
        if (rel == null) return;
        try {
            DocumentFile root = DocumentFile.fromTreeUri(context, tree);
            if (root == null) return;
            String[] segs = rel.split("/");
            DocumentFile parent = navigate(root, segs, 0, segs.length - 1, false);
            if (parent == null) return;
            DocumentFile node = parent.findFile(segs[segs.length - 1]);
            if (node != null && node.renameTo(newName)) Log.i(TAG, "syncPushRename " + rel + " -> " + newName);
        } catch (Exception e) {
            Log.e(TAG, "syncPushRename failed", e);
        }
    }

    private static void pullTree(ContentResolver cr, DocumentFile dir, File localDir) {
        if (!localDir.exists()) localDir.mkdirs();
        for (DocumentFile doc : dir.listFiles()) {
            if (doc.isDirectory()) {
                pullTree(cr, doc, new File(localDir, doc.getName()));
            } else if (doc.isFile()) {
                copyDoc(cr, doc, new File(localDir, doc.getName()));
            }
        }
    }

    private static void copyDoc(ContentResolver cr, DocumentFile doc, File target) {
        try (InputStream in = cr.openInputStream(doc.getUri());
             OutputStream out = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (Exception e) {
            Log.w(TAG, "copyDoc failed for " + doc.getUri() + ": " + e);
        }
    }

    private static void pushNode(ContentResolver cr, DocumentFile parent, File local) {
        if (local.isDirectory()) {
            File[] children = local.listFiles();
            if (children == null) return;
            for (File child : children) {
                DocumentFile sub = parent.findFile(child.getName());
                if (sub == null) sub = parent.createDirectory(child.getName());
                if (sub != null) pushNode(cr, sub, child);
            }
        } else {
            String mime = mimeFor(local.getName());
            DocumentFile target = parent.findFile(local.getName());
            if (target == null) target = parent.createFile(mime, local.getName());
            if (target == null) return;
            try (InputStream in = new FileInputStream(local);
                 OutputStream out = cr.openOutputStream(target.getUri(), "w")) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            } catch (Exception e) {
                Log.w(TAG, "pushNode failed for " + local.getName() + ": " + e);
            }
        }
    }

    /** Walks segments of a relative path starting from the tree root, optionally
     *  creating missing directories. Returns the leaf's parent. */
    private static DocumentFile navigate(DocumentFile root, String[] segs, int start, int end, boolean createDirs) {
        DocumentFile cur = root;
        for (int i = start; i < end; i++) {
            DocumentFile next = cur.findFile(segs[i]);
            if (next == null) {
                if (!createDirs) return null;
                next = cur.createDirectory(segs[i]);
            }
            if (next == null) return null;
            cur = next;
        }
        return cur;
    }

    private static String mimeFor(String name) {
        String mime = URLConnection.guessContentTypeFromName(name);
        if (mime == null) mime = "application/octet-stream";
        return mime;
    }

    private static String relativePath(File base, File file) {
        String b = base.getAbsolutePath();
        String f = file.getAbsolutePath();
        if (!b.endsWith("/")) b = b + "/";
        if (!f.startsWith(b)) return null;
        return f.substring(b.length());
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
