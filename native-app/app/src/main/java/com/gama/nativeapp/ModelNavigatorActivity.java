package com.gama.nativeapp;

import android.animation.Animator;
import android.animation.AnimatorInflater;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.format.Formatter;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ModelNavigatorActivity extends AppCompatActivity {

    private static final String TAG = "ModelNavigator";
    private MaterialToolbar toolbar;
    private TextView statusText;
    private LinearProgressIndicator progressIndicator;
    private RecyclerView recyclerView;
    private TextInputLayout searchLayout;
    private TextInputEditText searchInput;
    private FloatingActionButton fab;
    private View emptyState;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ModelTreeItem libraryRoot;
    private final List<ModelTreeItem> flatList = new ArrayList<>();
    private final List<ModelTreeItem> filteredList = new ArrayList<>();
    private TreeAdapter adapter;
    private int totalFiles;
    private int totalDirs;
    private long totalSize;
    private boolean isSearching = false;

    // Source switcher
    private static final int SOURCE_LIBRARY = 0;
    private static final int SOURCE_WORKSPACE = 1;
    private static final int REQUEST_PICK_WORKSPACE_FOLDER = 1001;
    private static final int REQUEST_MANAGE_ACCESS = 1002;
    private static final int REQUEST_WRITE_STORAGE = 1003;
    private int currentSource = SOURCE_LIBRARY;
    private MaterialButton libTabBtn;
    private MaterialButton wsTabBtn;
    private ModelTreeItem workspaceRootItem;
    private TextView emptyTitleText;
    private TextView emptySubtitleText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_GamaNative);
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        toolbar = new MaterialToolbar(this);
        toolbar.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.toolbar_background)));
        toolbar.setTitle("GAMA Models");
        toolbar.setTitleTextColor(0xFFFFFFFF);
        toolbar.setSubtitleTextColor(0xB3FFFFFF);
        toolbar.setNavigationIcon(ContextCompat.getDrawable(this, R.drawable.ic_folder));
        toolbar.setNavigationContentDescription("Open");
        toolbar.setNavigationOnClickListener(v -> {});

        ImageView searchIcon = new ImageView(this);
        searchIcon.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_search));
        searchIcon.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        searchIcon.setPadding(dp(12), dp(8), dp(12), dp(8));
        searchIcon.setOnClickListener(v -> toggleSearch());
        searchIcon.setContentDescription("Search");
        LinearLayout.LayoutParams searchIconLp = new LinearLayout.LayoutParams(dp(48), dp(48));
        toolbar.addView(searchIcon, searchIconLp);

        ImageView settingsIcon = new ImageView(this);
        settingsIcon.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_settings));
        settingsIcon.setColorFilter(0xFFFFFFFF, PorterDuff.Mode.SRC_IN);
        settingsIcon.setPadding(dp(12), dp(8), dp(12), dp(8));
        settingsIcon.setOnClickListener(v -> showWorkspaceLocationDialog());
        settingsIcon.setContentDescription("Workspace location");
        toolbar.addView(settingsIcon, new LinearLayout.LayoutParams(dp(48), dp(48)));

        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout switcher = new LinearLayout(this);
        switcher.setOrientation(LinearLayout.HORIZONTAL);
        switcher.setPadding(dp(16), dp(8), dp(16), dp(4));

        libTabBtn = buildSourceTab("Library", true);
        wsTabBtn = buildSourceTab("Workspace", false);
        switcher.addView(libTabBtn, sourceTabLp());
        switcher.addView(wsTabBtn, sourceTabLp());
        root.addView(switcher, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        searchLayout = new TextInputLayout(this);
        searchLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        searchLayout.setBoxCornerRadii(dp(12), dp(12), dp(12), dp(12));
        searchLayout.setHint("Search models...");
        searchLayout.setVisibility(View.GONE);
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        searchLp.setMargins(dp(16), dp(8), dp(16), dp(4));
        searchLayout.setLayoutParams(searchLp);

        searchInput = new TextInputEditText(this);
        searchInput.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        searchInput.setTextSize(14);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                filterModels(s.toString());
            }
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                filterModels(v.getText().toString());
                return true;
            }
            return false;
        });
        searchLayout.addView(searchInput);
        root.addView(searchLayout);

        LinearLayout statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        statusBar.setGravity(Gravity.CENTER_VERTICAL);
        statusBar.setPadding(dp(16), dp(12), dp(16), dp(12));
        statusBar.setBackgroundColor(0xFFF5F5F5);

        statusText = new TextView(this);
        statusText.setText("Initializing...");
        statusText.setTextSize(13);
        statusText.setTextColor(0xFF666666);
        statusText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusBar.addView(statusText);

        progressIndicator = new LinearProgressIndicator(this);
        progressIndicator.setIndeterminate(true);
        progressIndicator.setTrackThickness(dp(3));
        progressIndicator.setProgressTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary)));
        progressIndicator.setLayoutParams(new LinearLayout.LayoutParams(
                dp(24), dp(24)));
        statusBar.addView(progressIndicator);
        root.addView(statusBar);

        recyclerView = new RecyclerView(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setVisibility(View.GONE);
        recyclerView.setClipToPadding(false);
        recyclerView.setPadding(0, dp(8), 0, dp(80));
        root.addView(recyclerView, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        emptyState = createEmptyState();
        emptyState.setVisibility(View.GONE);
        root.addView(emptyState, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        fab = new FloatingActionButton(this);
        fab.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_folder));
        fab.setBackgroundTintList(ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary)));
        fab.setSize(FloatingActionButton.SIZE_MINI);
        fab.setContentDescription("Add");
        fab.setOnClickListener(v -> onFabClick());
        LinearLayout.LayoutParams fabLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        fabLp.gravity = Gravity.END | Gravity.BOTTOM;
        fabLp.setMargins(0, 0, dp(16), dp(16));
        root.addView(fab, fabLp);

        setContentView(root);
        setGuiActivity(this);

        adapter = new TreeAdapter();
        recyclerView.setAdapter(adapter);

        executor.execute(() -> bootstrap());
    }

    private View createEmptyState() {
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER);
        container.setPadding(dp(32), dp(48), dp(32), dp(48));

        TextView icon = new TextView(this);
        icon.setText("📂");
        icon.setTextSize(64);
        container.addView(icon);

        TextView title = new TextView(this);
        title.setText("No Models Found");
        title.setTextSize(18);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(0xFF888888);
        title.setPadding(0, dp(16), 0, dp(4));
        emptyTitleText = title;
        container.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Tap refresh to load models from library");
        subtitle.setTextSize(14);
        subtitle.setTextColor(0xFFAAAAAA);
        emptySubtitleText = subtitle;
        container.addView(subtitle);

        return container;
    }

    private void toggleSearch() {
        isSearching = !isSearching;
        searchLayout.setVisibility(isSearching ? View.VISIBLE : View.GONE);
        if (isSearching) {
            searchInput.requestFocus();
        } else {
            searchInput.setText("");
            filterModels("");
        }
    }

    private void filterModels(String query) {
        filteredList.clear();
        if (query.isEmpty()) {
            filteredList.addAll(flatList);
        } else {
            String lower = query.toLowerCase();
            collectMatching(libraryRoot, lower, filteredList);
        }
        adapter.notifyDataSetChanged();
        emptyState.setVisibility(filteredList.isEmpty() && !flatList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void collectMatching(ModelTreeItem node, String lower, List<ModelTreeItem> out) {
        if (node == null) return;
        for (ModelTreeItem child : node.getChildren()) {
            if (child.getName().toLowerCase().contains(lower)) {
                out.add(child);
            }
            if (child.isDirectory()) {
                collectMatching(child, lower, out);
            }
        }
    }

    private void refreshLibrary() {
        flatList.clear();
        filteredList.clear();
        adapter.notifyDataSetChanged();
        statusText.setText("Refreshing...");
        progressIndicator.setVisibility(View.VISIBLE);
        executor.execute(this::bootstrap);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (currentSource == SOURCE_WORKSPACE) {
            refreshWorkspace();
        }
    }

    private void showWorkspaceLocationDialog() {
        String current = WorkspaceManager.getConfigRootPath(this);
        boolean isDefault = WorkspaceManager.defaultRootPath(this).equals(current);
        String title = "Workspace location\n" + (isDefault ? "Using app storage" : current);

        final CharSequence[] items = {
                "Choose folder on device...",
                isDefault ? "Refresh current folder" : "Reset to app storage (default)"
        };

        new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setItems(items, (d, w) -> {
                    if (w == 0) pickWorkspaceFolder();
                    else if (isDefault) applyWorkspaceLocationChange();
                    else { WorkspaceManager.resetWorkspaceRoot(this); applyWorkspaceLocationChange(); }
                })
                .show();
    }

    private void applyWorkspaceLocationChange() {
        executor.execute(() -> {
            buildWorkspaceTree();
            mainHandler.post(() -> {
                if (currentSource == SOURCE_WORKSPACE) showWorkspace();
            });
        });
    }

    private void pickWorkspaceFolder() {
        if (hasStorageAccess()) {
            launchFolderPicker();
            return;
        }
        // On Android 11+ (API 30+) scoped storage blocks java.io.File access to
        // /storage/emulated/0/ without MANAGE_EXTERNAL_STORAGE. The GAMA engine
        // consumes model paths as real File paths, so request that permission.
        if (Build.VERSION.SDK_INT >= 30) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Storage permission needed")
                    .setMessage("To move the workspace to a folder on your device's storage, grant "
                            + "\"All files access\" to GAMA Native in Settings, then choose the folder again.")
                    .setPositiveButton("Open Settings", (d, w) -> openManageSettings())
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            // API 26-29: WRITE_EXTERNAL_STORAGE is the (runtime) gate here.
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_WRITE_STORAGE);
        }
    }

    private boolean hasStorageAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void openManageSettings() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        intent.setData(Uri.parse("package:" + getPackageName()));
        try {
            startActivityForResult(intent, REQUEST_MANAGE_ACCESS);
        } catch (Exception e) {
            Toast.makeText(this, "Open \"All files access\" for GAMA Native in Settings",
                    Toast.LENGTH_LONG).show();
        }
    }

    private void launchFolderPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        // Open the picker at the device's primary shared-storage root so the user lands on a
        // readable listing of folders rather than a creation prompt.
        try {
            intent.putExtra("android.intent.extra.INITIAL_URI",
                    Uri.parse("content://com.android.externalstorage.documents/tree/primary"));
        } catch (Exception ignored) {}
        try {
            startActivityForResult(intent, REQUEST_PICK_WORKSPACE_FOLDER);
        } catch (Exception e) {
            Toast.makeText(this, "No file manager available", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_WRITE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchFolderPicker();
            } else {
                Toast.makeText(this, "Storage permission is required to pick a folder",
                        Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_MANAGE_ACCESS) {
            if (hasStorageAccess()) {
                launchFolderPicker();
            } else {
                Toast.makeText(this, "All files access is required to use a device folder",
                        Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (requestCode != REQUEST_PICK_WORKSPACE_FOLDER || resultCode != RESULT_OK || data == null) return;
        Uri treeUri = data.getData();
        if (treeUri == null) return;
        try {
            getContentResolver().takePersistableUriPermission(treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            Log.w(TAG, "persistable permission: " + e.getMessage());
        }
        String localPath = WorkspaceManager.resolveLocalPathFromTreeUri(this, treeUri);
        if (localPath == null) {
            Toast.makeText(this, "Please pick a folder in device storage (not cloud)", Toast.LENGTH_LONG).show();
            return;
        }
        File root = new File(localPath);
        if (!root.exists() && !root.mkdirs()) root = null;
        if (root == null || !WorkspaceManager.isWritable(root)) {
            Toast.makeText(this, "Selected folder is not writable", Toast.LENGTH_LONG).show();
            return;
        }
        // Optionally migrate the existing app-private workspace if it has content.
        File defaultRoot = WorkspaceManager.defaultRoot(this);
        boolean hasContent = defaultRoot.exists()
                && defaultRoot.listFiles() != null && defaultRoot.listFiles().length > 0;
        File finalRoot = root;
        boolean isEmpty = root.listFiles() != null && root.listFiles().length == 0;
        if (hasContent && isEmpty) {
            new MaterialAlertDialogBuilder(this)
                    .setMessage("Copy your existing workspace content into the new folder?")
                    .setPositiveButton("Copy", (d, w) -> {
                        WorkspaceManager.setWorkspaceRoot(this, finalRoot);
                        copyExistingWorkspace(defaultRoot, finalRoot);
                        applyWorkspaceLocationChange();
                        Toast.makeText(this, "Workspace moved to " + finalRoot.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    })
                    .setNegativeButton("Skip", (d, w) -> {
                        WorkspaceManager.setWorkspaceRoot(this, finalRoot);
                        applyWorkspaceLocationChange();
                        Toast.makeText(this, "Workspace moved to " + finalRoot.getAbsolutePath(), Toast.LENGTH_LONG).show();
                    })
                    .show();
        } else {
            WorkspaceManager.setWorkspaceRoot(this, root);
            applyWorkspaceLocationChange();
            Toast.makeText(this, "Workspace moved to " + root.getAbsolutePath(), Toast.LENGTH_LONG).show();
        }
    }

    private void copyExistingWorkspace(File src, File dst) {
        executor.execute(() -> {
            try {
                WorkspaceManager.copyRecursively(src, dst);
            } catch (IOException e) {
                Log.e(TAG, "copy workspace failed", e);
            }
        });
    }

    private MaterialButton buildSourceTab(String label, boolean selected) {
        MaterialButton btn = new MaterialButton(this);
        btn.setText(label);
        btn.setTextSize(13);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setCornerRadius(dp(20));
        btn.setStrokeWidth(dp(1));
        btn.setMinimumHeight(0);
        btn.setMinimumWidth(0);
        applySourceTabStyle(btn, selected);
        btn.setOnClickListener(v -> {
            int target = btn == libTabBtn ? SOURCE_LIBRARY : SOURCE_WORKSPACE;
            switchSource(target);
        });
        return btn;
    }

    private LinearLayout.LayoutParams sourceTabLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), 1f);
        lp.setMargins(dp(2), 0, dp(2), 0);
        return lp;
    }

    private void applySourceTabStyle(MaterialButton btn, boolean selected) {
        if (selected) {
            btn.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.primary)));
            btn.setTextColor(0xFFFFFFFF);
            btn.setStrokeColor(ColorStateList.valueOf(
                    ContextCompat.getColor(this, R.color.primary)));
        } else {
            btn.setBackgroundTintList(ColorStateList.valueOf(0xFFEEEEEE));
            btn.setTextColor(0xFF555555);
            btn.setStrokeColor(ColorStateList.valueOf(0xFFCCCCCC));
        }
    }

    private void switchSource(int source) {
        if (source == currentSource) return;
        currentSource = source;
        applySourceTabStyle(libTabBtn, source == SOURCE_LIBRARY);
        applySourceTabStyle(wsTabBtn, source == SOURCE_WORKSPACE);
        isSearching = false;
        searchLayout.setVisibility(View.GONE);
        searchInput.setText("");
        flatList.clear();
        filteredList.clear();
        adapter.notifyDataSetChanged();
        emptyState.setVisibility(View.GONE);

        if (source == SOURCE_WORKSPACE) {
            statusText.setText("Loading workspace...");
            progressIndicator.setVisibility(View.VISIBLE);
            executor.execute(() -> {
                buildWorkspaceTree();
                mainHandler.post(this::showWorkspace);
            });
        } else {
            if (emptyTitleText != null) emptyTitleText.setText("No Models Found");
            if (emptySubtitleText != null) {
                emptySubtitleText.setText("Tap refresh to load models from library");
            }
            statusText.setText("Loading library...");
            progressIndicator.setVisibility(View.VISIBLE);
            executor.execute(() -> {
                extractLibraryInternal(() -> buildAndShowTree());
            });
        }
    }

    private void onFabClick() {
        if (currentSource == SOURCE_WORKSPACE) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Add to Workspace")
                    .setItems(new String[]{"New model", "New folder"}, (d, w) -> {
                        if (w == 0) showNewModelDialog(null);
                        else showNewFolderDialog(null);
                    })
                    .show();
        } else {
            refreshLibrary();
        }
    }

    private void showWorkspace() {
        progressIndicator.setVisibility(View.GONE);
        if (workspaceRootItem == null || workspaceRootItem.getChildren().isEmpty()) {
            statusText.setText("My Workspace · empty");
            flatList.clear();
            filteredList.clear();
            adapter.notifyDataSetChanged();
            recyclerView.setVisibility(View.GONE);
            if (emptyTitleText != null) emptyTitleText.setText("Empty Workspace");
            if (emptySubtitleText != null) {
                emptySubtitleText.setText("Tap + to create your first model");
            }
            emptyState.setVisibility(View.VISIBLE);
            fab.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_add));
            return;
        }
        workspaceRootItem.setExpanded(true);
        flatList.clear();
        flattenTree(workspaceRootItem);
        filteredList.clear();
        filteredList.addAll(flatList);
        statusText.setText("My Workspace \u00B7 " + workspaceRootItem.getChildren().size() + " items");
        recyclerView.setVisibility(View.VISIBLE);
        emptyState.setVisibility(View.GONE);
        adapter.notifyDataSetChanged();
        fab.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_add));
    }

    private void buildWorkspaceTree() {
        File root = WorkspaceManager.workspaceRoot(this);
        workspaceRootItem = new ModelTreeItem("My Workspace", root.getAbsolutePath(),
                ModelTreeItem.Type.CATEGORY, 0, null);
        File[] children = root.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isHidden() || child.getName().startsWith(".")) continue;
                addFileNode(child, workspaceRootItem);
            }
        }
        sortTree(workspaceRootItem);
    }

    private void addFileNode(File file, ModelTreeItem parent) {
        if (file.isDirectory()) {
            ModelTreeItem dir = new ModelTreeItem(file.getName(), file.getAbsolutePath(),
                    ModelTreeItem.Type.CATEGORY, parent.getDepth() + 1, parent);
            parent.getChildren().add(dir);
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (child.isHidden() || child.getName().startsWith(".")) continue;
                    addFileNode(child, dir);
                }
            }
        } else {
            String ext = "";
            int dot = file.getName().lastIndexOf('.');
            if (dot >= 0) ext = file.getName().substring(dot + 1).toLowerCase();
            ModelTreeItem.Type type = "gaml".equals(ext)
                    ? ModelTreeItem.Type.MODEL_FILE : ModelTreeItem.Type.FILE;
            ModelTreeItem item = new ModelTreeItem(file.getName(), file.getAbsolutePath(),
                    type, parent.getDepth() + 1, parent);
            item.setFileSize(file.length());
            parent.getChildren().add(item);
        }
    }

    private void showNewModelDialog(File parentDir) {
        TextInputEditText input = new TextInputEditText(this);
        input.setHint("Model name (e.g. MyModel)");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("New GAML Model")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText() != null ? input.getText().toString().trim() : "";
                    if (name.isEmpty()) { Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show(); return; }
                    createNewModel(parentDir, name);
                })
                .setNegativeButton("Cancel", null);
        builder.show();
    }

    private void createNewModel(File parentDir, String rawName) {
        executor.execute(() -> {
            try {
                String name = WorkspaceManager.sanitizeModelName(rawName);
                File file = WorkspaceManager.newModel(this, parentDir, name);
                mainHandler.post(() -> {
                    refreshWorkspace();
                    launchEditorFile(file);
                });
            } catch (Exception e) {
                Log.e(TAG, "create model failed", e);
                mainHandler.post(() -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void showNewFolderDialog(File parentDir) {
        TextInputEditText input = new TextInputEditText(this);
        input.setHint("Folder name");
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);

        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this)
                .setTitle("New Folder")
                .setView(input)
                .setPositiveButton("Create", (d, w) -> {
                    String name = input.getText() != null ? input.getText().toString().trim() : "";
                    if (name.isEmpty()) { Toast.makeText(this, "Enter a name", Toast.LENGTH_SHORT).show(); return; }
                    File folder = WorkspaceManager.newFolder(this, parentDir, name);
                    refreshWorkspace();
                    Toast.makeText(this, "Folder created", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showItemActions(ModelTreeItem item) {
        boolean isDir = item.isDirectory();
        List<String> actions = new ArrayList<>();
        if (!isDir) actions.add("Open");
        if (!isDir && item.getType() == ModelTreeItem.Type.MODEL_FILE) actions.add("Run");
        if (isDir) actions.add("New model here");
        if (isDir) actions.add("New folder here");
        actions.add("Rename");
        actions.add("Duplicate");
        actions.add("Delete");

        new MaterialAlertDialogBuilder(this)
                .setTitle(item.getName())
                .setItems(actions.toArray(new String[0]), (d, w) -> {
                    String action = actions.get(w);
                    switch (action) {
                        case "Open":
                            openItem(item);
                            break;
                        case "Run":
                            launchExperimentFile(item);
                            break;
                        case "New model here":
                            showNewModelDialog(new File(item.getFullPath()));
                            break;
                        case "New folder here":
                            showNewFolderDialog(new File(item.getFullPath()));
                            break;
                        case "Rename":
                            showRenameDialog(item);
                            break;
                        case "Duplicate":
                            duplicateItem(item);
                            break;
                        case "Delete":
                            confirmDelete(item);
                            break;
                    }
                })
                .show();
    }

    private void openItem(ModelTreeItem item) {
        if (item.isDirectory()) {
            item.setExpanded(!item.isExpanded());
            refreshFlatList();
            notifyAdapter();
            return;
        }
        String ext = item.getExtension();
        boolean textEditable = "gaml".equals(ext) || "txt".equals(ext) || "csv".equals(ext)
                || "xml".equals(ext) || "json".equals(ext) || "prefs".equals(ext) || "asc".equals(ext);
        if (textEditable) {
            launchEditorFile(item);
        } else {
            Toast.makeText(this, "Cannot open " + ext.toUpperCase() + " files yet", Toast.LENGTH_SHORT).show();
        }
    }

    private void showRenameDialog(ModelTreeItem item) {
        TextInputEditText input = new TextInputEditText(this);
        input.setText(item.getName());
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_DONE);

        new MaterialAlertDialogBuilder(this)
                .setTitle("Rename")
                .setView(input)
                .setPositiveButton("Rename", (d, w) -> {
                    String newName = input.getText() != null ? input.getText().toString().trim() : "";
                    if (newName.isEmpty() || newName.equals(item.getName())) return;
                    if (!item.isDirectory() && item.getType() == ModelTreeItem.Type.MODEL_FILE
                            && !newName.endsWith(".gaml")) {
                        newName = newName + ".gaml";
                    }
                    final String targetName = newName;
                    executor.execute(() -> {
                        File src = new File(item.getFullPath());
                        File dst = new File(src.getParentFile(), targetName);
                        if (dst.exists()) {
                            mainHandler.post(() -> Toast.makeText(this, "Name already exists", Toast.LENGTH_SHORT).show());
                            return;
                        }
                        if (src.renameTo(dst)) {
                            mainHandler.post(this::refreshWorkspace);
                        } else {
                            mainHandler.post(() -> Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show());
                        }
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void duplicateItem(ModelTreeItem item) {
        executor.execute(() -> {
            try {
                File src = new File(item.getFullPath());
                File dst = WorkspaceManager.uniqueFile(src.getParentFile(), src.getName());
                WorkspaceManager.copyRecursively(src, dst);
                mainHandler.post(this::refreshWorkspace);
            } catch (Exception e) {
                Log.e(TAG, "duplicate failed", e);
                mainHandler.post(() -> Toast.makeText(this, "Duplicate failed", Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void confirmDelete(ModelTreeItem item) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete " + item.getName() + "?")
                .setMessage("This cannot be undone.")
                .setPositiveButton("Delete", (d, w) -> executor.execute(() -> {
                    WorkspaceManager.deleteRecursively(new File(item.getFullPath()));
                    mainHandler.post(this::refreshWorkspace);
                }))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void refreshWorkspace() {
        if (currentSource != SOURCE_WORKSPACE) return;
        executor.execute(() -> {
            buildWorkspaceTree();
            mainHandler.post(this::showWorkspace);
        });
    }

    private void notifyAdapter() {
        if (!searchInput.getText().toString().isEmpty()) {
            filterModels(searchInput.getText().toString());
        } else {
            filteredList.clear();
            filteredList.addAll(flatList);
            adapter.notifyDataSetChanged();
        }
        emptyState.setVisibility(filteredList.isEmpty() && !flatList.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void launchEditorFile(ModelTreeItem item) {
        Intent intent = new Intent(this, ModelEditorActivity.class);
        intent.putExtra("model_name", item.getName());
        intent.putExtra("file_path", item.getFullPath());
        startActivity(intent);
    }

    private void launchEditorFile(File file) {
        Intent intent = new Intent(this, ModelEditorActivity.class);
        intent.putExtra("model_name", file.getName());
        intent.putExtra("file_path", file.getAbsolutePath());
        startActivity(intent);
    }

    private void launchExperimentFile(ModelTreeItem item) {
        Intent intent = new Intent(this, ExperimentActivity.class);
        intent.putExtra("model_name", item.getName());
        intent.putExtra("file_path", item.getFullPath());
        startActivity(intent);
    }

    private void extractLibraryInternal(Runnable done) {
        try {
            JarFile jarFile = findLibraryJar();
            if (jarFile == null) {
                mainHandler.post(() -> { statusText.setText("JAR not found"); progressIndicator.setVisibility(View.GONE); });
                return;
            }

            File cacheDir = getCacheDir();
            File cacheJar = new File(cacheDir, LibraryJarUtil.JAR_NAME);
            int[] counts = {0};
            long[] bytes = {0};

            boolean force = LibraryJarUtil.isExtractionStale(this);
            Enumeration<? extends JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF")) continue;
                if (entry.isDirectory()) continue;

                File outFile = new File(cacheDir, name);
                if (outFile.exists()) {
                    if (!force) continue;
                    if (outFile.lastModified() > cacheJar.lastModified()) continue;
                }
                outFile.getParentFile().mkdirs();

                try (InputStream is = jarFile.getInputStream(entry);
                     FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        fos.write(buf, 0, n);
                        bytes[0] += n;
                    }
                }
                counts[0]++;
            }
            LibraryJarUtil.markExtracted(this);
            jarFile.close();
        } catch (Exception e) {
            Log.e(TAG, "Extraction failed", e);
        } finally {
            mainHandler.post(() -> done.run());
        }
    }

    private void bootstrap() {
        try {
            GamaNativeBootstrap.initialize(this, new GamaNativeBootstrap.ProgressCallback() {
                @Override public void onProgress(String msg) {
                    mainHandler.post(() -> statusText.setText(msg));
                }
                @Override public void onSuccess(String msg) {
                    mainHandler.post(() -> {
                        statusText.setText("Extracting library...");
                        extractLibrary();
                    });
                }
                @Override public void onFailure(String msg, Throwable t) {
                    mainHandler.post(() -> statusText.setText("FAILED: " + msg));
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "Bootstrap failed", e);
            mainHandler.post(() -> statusText.setText("ERROR: " + e.getMessage()));
        }
    }

    private void extractLibrary() {
        executor.execute(() -> extractLibraryInternal(this::buildAndShowTree));
    }

    private void buildAndShowTree() {
        libraryRoot = buildTree();
        flatList.clear();
        if (libraryRoot != null) {
            libraryRoot.setExpanded(true);
            for (ModelTreeItem child : libraryRoot.getChildren()) {
                child.setExpanded(true);
            }
            flattenTree(libraryRoot);
        }
        filteredList.clear();
        filteredList.addAll(flatList);

        mainHandler.post(() -> {
            if (currentSource != SOURCE_LIBRARY) return;
            progressIndicator.setVisibility(View.GONE);
            statusText.setText(totalFiles + " files, " + totalDirs + " folders (" + Formatter.formatFileSize(this, totalSize) + ")");
            recyclerView.setVisibility(View.VISIBLE);
            adapter.notifyDataSetChanged();
            fab.setImageDrawable(ContextCompat.getDrawable(this, R.drawable.ic_folder));
        });
    }

    private ModelTreeItem buildTree() {
        ModelTreeItem root = new ModelTreeItem("GAMA Library", "", ModelTreeItem.Type.CATEGORY, 0, null);
        Map<String, ModelTreeItem> dirMap = new LinkedHashMap<>();
        dirMap.put("", root);

        totalFiles = 0;
        totalDirs = 0;
        totalSize = 0;

        try {
            JarFile jarFile = findLibraryJar();
            if (jarFile == null) return root;

            Enumeration<? extends JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.startsWith("META-INF")) continue;

                if (entry.isDirectory()) {
                    if (!name.endsWith("/")) name += "/";
                    String parentPath = name.substring(0, name.lastIndexOf('/', name.length() - 2) + 1);
                    String dirName = name.substring(parentPath.length(), name.length() - 1);
                    if (dirName.isEmpty() || dirName.startsWith(".")) continue;

                    ModelTreeItem parent = dirMap.get(parentPath);
                    if (parent != null) {
                        ModelTreeItem dir = new ModelTreeItem(dirName, name,
                                ModelTreeItem.Type.CATEGORY, parent.getDepth() + 1, parent);
                        parent.getChildren().add(dir);
                        dirMap.put(name, dir);
                        totalDirs++;
                    }
                } else {
                    int lastSlash = name.lastIndexOf('/');
                    String parentPath = (lastSlash >= 0) ? name.substring(0, lastSlash + 1) : "";
                    String fileName = name.substring(lastSlash + 1);
                    if (fileName.startsWith(".")) continue;
                    String ext = "";
                    int dot = fileName.lastIndexOf('.');
                    if (dot >= 0) ext = fileName.substring(dot + 1).toLowerCase();

                    ModelTreeItem parent = dirMap.get(parentPath);
                    if (parent != null) {
                        ModelTreeItem.Type fileType;
                        if ("gaml".equals(ext)) {
                            fileType = ModelTreeItem.Type.MODEL_FILE;
                        } else {
                            fileType = ModelTreeItem.Type.FILE;
                        }
                        ModelTreeItem file = new ModelTreeItem(fileName, name,
                                fileType, parent.getDepth() + 1, parent);
                        file.setFileSize(entry.getSize());
                        parent.getChildren().add(file);
                        totalFiles++;
                        totalSize += entry.getSize();
                    }
                }
            }
            jarFile.close();
        } catch (Exception e) {
            Log.e(TAG, "Error scanning library", e);
        }

        sortTree(root);
        pruneEmptyDirs(root);
        return root;
    }

    private JarFile findLibraryJar() {
        try {
            File cacheJar = LibraryJarUtil.ensureCached(this);
            if (cacheJar == null) return null;
            return new JarFile(cacheJar);
        } catch (Exception e) {
            Log.e(TAG, "JAR open failed", e);
        }
        return null;
    }

    private void sortTree(ModelTreeItem node) {
        node.getChildren().sort((a, b) -> {
            if (a.isDirectory() && !b.isDirectory()) return -1;
            if (!a.isDirectory() && b.isDirectory()) return 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (ModelTreeItem child : node.getChildren()) {
            if (child.isDirectory()) sortTree(child);
        }
    }

    private void pruneEmptyDirs(ModelTreeItem node) {
        for (ModelTreeItem child : new ArrayList<>(node.getChildren())) {
            if (child.isDirectory()) {
                pruneEmptyDirs(child);
                if (child.getChildren().isEmpty()) {
                    node.getChildren().remove(child);
                }
            }
        }
    }

    private void flattenTree(ModelTreeItem node) {
        flatList.add(node);
        if (node.isDirectory() && node.isExpanded()) {
            for (ModelTreeItem child : node.getChildren()) {
                flattenTree(child);
            }
        }
    }

    private void refreshFlatList() {
        flatList.clear();
        ModelTreeItem root = currentSource == SOURCE_WORKSPACE ? workspaceRootItem : libraryRoot;
        if (root != null) flattenTree(root);
        filteredList.clear();
        filteredList.addAll(flatList);
    }

    private void launchEditor(String name, String jarPath, boolean fromLibrary) {
        Intent intent = new Intent(this, ModelEditorActivity.class);
        intent.putExtra("model_name", name);
        intent.putExtra("jar_path", jarPath);
        intent.putExtra("from_library", fromLibrary);
        startActivity(intent);
    }

    private void launchExperiment(String name, String jarPath, boolean fromLibrary) {
        Intent intent = new Intent(this, ExperimentActivity.class);
        intent.putExtra("model_name", jarPath);
        if (fromLibrary) {
            intent.putExtra("jar_path", jarPath);
            intent.putExtra("from_library", true);
        } else {
            intent.putExtra("asset_path", jarPath);
        }
        startActivity(intent);
    }

    private static void setGuiActivity(ModelNavigatorActivity activity) {
        try {
            Class<?> handlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
            handlerClass.getMethod("setActivity", android.app.Activity.class).invoke(null, activity);
        } catch (Throwable e) {
            Log.w(TAG, "Could not set GUI activity", e);
        }
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics());
    }

    private class TreeAdapter extends RecyclerView.Adapter<TreeAdapter.ViewHolder> {

        class ViewHolder extends RecyclerView.ViewHolder {
            MaterialCardView card;
            ImageView icon;
            TextView nameText;
            TextView infoText;
            int currentType;

            ViewHolder(MaterialCardView card) {
                super(card);
                this.card = card;
                this.icon = card.findViewById(R.id.item_icon);
                this.nameText = card.findViewById(R.id.item_name);
                this.infoText = card.findViewById(R.id.item_info);
            }
        }

        @Override
        public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            MaterialCardView card = new MaterialCardView(ModelNavigatorActivity.this);
            card.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            card.setRadius(dp(8));
            card.setCardElevation(dp(1));
            card.setUseCompatPadding(true);
            card.setContentPadding(dp(12), dp(8), dp(12), dp(8));
            card.setLayoutParams(new RecyclerView.LayoutParams(
                    RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));

            LinearLayout content = new LinearLayout(ModelNavigatorActivity.this);
            content.setOrientation(LinearLayout.HORIZONTAL);
            content.setGravity(Gravity.CENTER_VERTICAL);

            ImageView icon = new ImageView(ModelNavigatorActivity.this);
            icon.setId(R.id.item_icon);
            icon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
            int iconSize = dp(32);
            icon.setLayoutParams(new LinearLayout.LayoutParams(iconSize, iconSize));

            LinearLayout textContainer = new LinearLayout(ModelNavigatorActivity.this);
            textContainer.setOrientation(LinearLayout.VERTICAL);
            textContainer.setPadding(dp(12), 0, 0, 0);
            textContainer.setLayoutParams(new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            TextView nameText = new TextView(ModelNavigatorActivity.this);
            nameText.setId(R.id.item_name);
            nameText.setTextSize(14);
            nameText.setTypeface(null, Typeface.BOLD);
            textContainer.addView(nameText);

            TextView infoText = new TextView(ModelNavigatorActivity.this);
            infoText.setId(R.id.item_info);
            infoText.setTextSize(11);
            infoText.setTextColor(0xFF888888);
            infoText.setPadding(0, dp(2), 0, 0);
            textContainer.addView(infoText);

            content.addView(icon);
            content.addView(textContainer);
            card.addView(content);

            int margin = dp(4);
            RecyclerView.LayoutParams lp = (RecyclerView.LayoutParams) card.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) lp).setMargins(dp(12), margin, dp(12), margin);
                card.setLayoutParams(lp);
            }

            return new ViewHolder(card);
        }

        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            ModelTreeItem item = filteredList.get(position);
            int depth = item.getDepth();
            int indent = Math.min(depth * dp(20), dp(120));
            holder.card.setContentPadding(dp(12) + indent, dp(8), dp(12), dp(8));

            if (item.isDirectory()) {
                String arrow = item.isExpanded() ? "\u25BC " : "\u25B6 ";
                int childCount = item.getChildren().size();
                holder.nameText.setText(arrow + item.getName());
                holder.nameText.setTextColor(0xFF333333);
                holder.nameText.setTextSize(14);
                holder.infoText.setText(childCount + " item" + (childCount == 1 ? "" : "s"));
                holder.icon.setImageDrawable(ContextCompat.getDrawable(
                        ModelNavigatorActivity.this, R.drawable.ic_folder));
                holder.icon.setImageTintList(ColorStateList.valueOf(0xFF888888));
                holder.card.setCardBackgroundColor(0xFFFFFFFF);
                holder.card.setClickable(true);
                holder.card.setOnClickListener(v -> {
                    item.setExpanded(!item.isExpanded());
                    refreshFlatList();
                    if (!searchInput.getText().toString().isEmpty()) {
                        filterModels(searchInput.getText().toString());
                    }
                    notifyDataSetChanged();
                });
                holder.card.setOnLongClickListener(currentSource == SOURCE_WORKSPACE
                        ? v -> { showItemActions(item); return true; }
                        : null);
            } else if (item.getType() == ModelTreeItem.Type.MODEL_FILE) {
                holder.nameText.setText(item.getName());
                holder.nameText.setTextColor(ContextCompat.getColor(
                        ModelNavigatorActivity.this, R.color.gaml_dark_green));
                holder.nameText.setTextSize(14);
                String sizeStr = item.getFileSize() > 0
                        ? Formatter.formatFileSize(ModelNavigatorActivity.this, item.getFileSize())
                        : "";
                holder.infoText.setText("GAML" + (sizeStr.isEmpty() ? "" : " · " + sizeStr));
                holder.infoText.setTextColor(0xFF4CAF50);
                holder.icon.setImageDrawable(ContextCompat.getDrawable(
                        ModelNavigatorActivity.this, R.drawable.ic_file_gaml));
                holder.icon.setImageTintList(ColorStateList.valueOf(
                        ContextCompat.getColor(ModelNavigatorActivity.this, R.color.primary)));
                holder.card.setCardBackgroundColor(0xFFFFFFFF);
                holder.card.setOnClickListener(v -> {
                    if (currentSource == SOURCE_WORKSPACE) {
                        launchEditorFile(item);
                    } else {
                        boolean fromLibrary = isFromLibrary(item);
                        launchEditor(item.getName(), item.getFullPath(), fromLibrary);
                    }
                });
                holder.card.setOnLongClickListener(v -> {
                    if (currentSource == SOURCE_WORKSPACE) {
                        showItemActions(item);
                    } else {
                        boolean fromLibrary = isFromLibrary(item);
                        launchExperiment(item.getName(), item.getFullPath(), fromLibrary);
                    }
                    return true;
                });
            } else {
                holder.nameText.setText(item.getName());
                holder.nameText.setTextColor(0xFF666666);
                holder.nameText.setTextSize(13);
                String ext = item.getExtension();
                String sizeStr = item.getFileSize() > 0
                        ? Formatter.formatFileSize(ModelNavigatorActivity.this, item.getFileSize())
                        : "";
                holder.infoText.setText(ext.toUpperCase() + " · " + sizeStr);
                holder.infoText.setTextColor(0xFF888888);
                holder.icon.setImageDrawable(null);
                holder.card.setCardBackgroundColor(0xFFFAFAFA);
                if (currentSource == SOURCE_WORKSPACE) {
                    holder.card.setClickable(true);
                    holder.card.setOnClickListener(v -> openItem(item));
                    holder.card.setOnLongClickListener(v -> { showItemActions(item); return true; });
                } else {
                    holder.card.setClickable(false);
                    holder.card.setOnClickListener(null);
                    holder.card.setOnLongClickListener(null);
                }
            }
        }

        @Override
        public int getItemCount() { return filteredList.size(); }

        private boolean isFromLibrary(ModelTreeItem item) {
            ModelTreeItem p = item.getParent();
            while (p != null && p.getParent() != null) {
                p = p.getParent();
            }
            return p == libraryRoot;
        }
    }
}
