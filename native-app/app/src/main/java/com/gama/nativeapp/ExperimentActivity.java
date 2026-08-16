package com.gama.nativeapp;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;

import com.gama.nativeapp.display.AndroidDisplaySurface;
import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.slider.Slider;
import com.google.android.material.tabs.TabLayout;


import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public class ExperimentActivity extends Activity {

    private static final String TAG = "ExperimentActivity";

    // All model compilations run serially on this executor. Compiles started by a
    // destroyed activity must not overlap a new activity's compile: GAML's builder
    // serializes itself, but other shared statics (e.g. GamlResourceServices'
    // resource sets) are unsynchronized, and concurrent use corrupts them, causing
    // random "Compilation FAILED (N errors)" on the model launched right after a
    // back-navigation during (or just after) a previous compile.
    private static final java.util.concurrent.ExecutorService COMPILE_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    // UI components
    private MaterialToolbar toolbar;
    private TextView toolbarTitle;
    private TabLayout tabLayout;
    private FrameLayout displayContainer;
    private LinearLayout consolePanel;
    private LinearLayout layersPanel;
    private LinearLayout paramsPanel;
    private TextView logView;
    private ScrollView logScroll;
    private TextView cycleText;
    private LinearLayout rootLayout;
    private ViewGroup contentArea;

    // Transport control bar (play/pause, step, stop) replacing the floating FABs
    private LinearLayout transportBar;
    private ImageView playPauseBtn;
    private ImageView stepBtn;
    private ImageView stopBtn;

    // Display tabs
    private HorizontalScrollView displayTabScroll;
    private LinearLayout displayTabBar;
    private String activeDisplayName;

    // The display column holds the transport bar, display tabs/toolbar and the
    // surface container. It is stacked with the bottom panel in portrait and
    // placed next to a side column (tabs + panel) in landscape.
    private LinearLayout displayColumn;
    private LinearLayout.LayoutParams displayColumnLp;
    private LinearLayout mainRow;
    private LinearLayout rightCol;

    // Drag handle for resizing (portrait only)
    private View dragHandle;
    private LinearLayout bottomPanel;
    private LinearLayout.LayoutParams bottomPanelLp;
    private boolean isLandscape = false;

    // Fullscreen
    private boolean isFullscreen = false;
    // The display/console split ratio before entering fullscreen, restored on exit
    private float savedDisplayWeight = 3f;
    private TextView fullscreenBtn;
    private int displayTabScrollVisibility = View.GONE;

    // State
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed = false;
    private Object compiledModel;
    private String modelName;
    private boolean isDarkTheme = false;
    private volatile boolean isRunning = false;
    private volatile boolean isPaused = false;
    private Object currentExpPlan;
    private Object currentController;
    private Runnable statePollRunnable;
    private Runnable clockUpdateRunnable;

    // Redirect target for System.out/System.err, stored so onDestroy can restore
    // the originals. Capturing the live System.out each launch chained the
    // anonymous OutputStreams together, and each stream pinned a destroyed
    // activity forever (static System.out -> ... -> old activity).
    private PrintStream originalOut;
    private PrintStream originalErr;

    // Sensor bridge
    private SensorBridge sensorBridge;

    // Cached reflection
    private java.lang.reflect.Field aliveField;
    private java.lang.reflect.Field scopeField;
    private java.lang.reflect.Field execThreadField;
    private java.lang.reflect.Method getClockMethod;
    private java.lang.reflect.Method getCycleMethod;

    // Layer state
    private final List<LayerInfo> layerInfos = new ArrayList<>();

    static class LayerInfo {
        String name;
        boolean visible = true;
        float opacity = 1.0f;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_GamaNative);
        super.onCreate(savedInstanceState);
        setGuiActivity(this);
        modelName = getIntent().getStringExtra("model_name");
        isDarkTheme = getSharedPreferences("gama_prefs", 0).getBoolean("dark_theme", false);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(thc(0xFFFAFAFA, 0xFF121212));
        rootLayout = root;

        buildToolbar(root);

        tabLayout = new TabLayout(this);
        tabLayout.setBackgroundColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
        tabLayout.setSelectedTabIndicatorColor(ContextCompat.getColor(this, R.color.primary));
        tabLayout.setTabTextColors(thc(0xFF888888, 0xFF999999), ContextCompat.getColor(this, R.color.primary));
        tabLayout.addTab(tabLayout.newTab().setText("Display").setIcon(
                ContextCompat.getDrawable(this, R.drawable.ic_fit)));
        tabLayout.addTab(tabLayout.newTab().setText("Console").setIcon(
                ContextCompat.getDrawable(this, R.drawable.ic_console)));
        tabLayout.addTab(tabLayout.newTab().setText("Layers").setIcon(
                ContextCompat.getDrawable(this, R.drawable.ic_layers)));
        tabLayout.addTab(tabLayout.newTab().setText("Params"));
        tabLayout.setTabGravity(TabLayout.GRAVITY_FILL);
        tabLayout.setTabMode(TabLayout.MODE_FIXED);
        tabLayout.setElevation(dp(4));

        contentArea = new FrameLayout(this);

        buildTransportBar();
        buildDisplayArea();
        buildConsolePanel();
        buildLayersPanel();
        buildParamsPanel();

        FrameLayout contentFrame = new FrameLayout(this);
        contentFrame.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
        contentFrame.addView(contentArea, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        root.addView(contentFrame);

        isLandscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        applyOrientation();

        setContentView(root);

        sensorBridge = new SensorBridge(this);
        sensorBridge.start();

        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override public void onTabSelected(TabLayout.Tab tab) {
                showPanel(tab.getPosition());
            }
            @Override public void onTabUnselected(TabLayout.Tab tab) {}
            @Override public void onTabReselected(TabLayout.Tab tab) {}
        });

        startEngine();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        isLandscape = newConfig.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        // The layout is rebuilt with the new orientation; leave fullscreen so the
        // system bars/layout don't fight the re-arrangement.
        if (isFullscreen) {
            isFullscreen = false;
            View decor = getWindow().getDecorView();
            decor.setSystemUiVisibility(0);
            if (fullscreenBtn != null) {
                fullscreenBtn.setTextColor(thc(0xFFAAAAAA, 0xFF999999));
            }
        }
        applyOrientation();
    }

    private void buildToolbar(LinearLayout root) {
        toolbar = new MaterialToolbar(this);
        toolbar.setBackgroundColor(ContextCompat.getColor(this, R.color.toolbar_background));
        toolbar.setNavigationIcon(null);

        LinearLayout toolbarContent = new LinearLayout(this);
        toolbarContent.setOrientation(LinearLayout.HORIZONTAL);
        toolbarContent.setGravity(Gravity.CENTER_VERTICAL);
        toolbarContent.setPadding(dp(4), 0, dp(8), 0);

        TextView backBtn = new TextView(this);
        backBtn.setText("\u2190 Back");
        backBtn.setTextSize(14);
        backBtn.setTextColor(Color.WHITE);
        backBtn.setTypeface(null, Typeface.BOLD);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        backBtn.setMinWidth(0);
        backBtn.setMinHeight(0);
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setCornerRadius(dp(8));
        bg.setColor(0xFF2196F3);
        backBtn.setBackground(bg);
        backBtn.setOnClickListener(v -> finish());
        toolbarContent.addView(backBtn);

        toolbarTitle = new TextView(this);
        toolbarTitle.setText(modelName != null ? modelName : "GAMA");
        toolbarTitle.setTextSize(16);
        toolbarTitle.setTextColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
        toolbarTitle.setTypeface(null, Typeface.BOLD);
        toolbarTitle.setPadding(dp(8), 0, 0, 0);
        toolbarTitle.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        toolbarContent.addView(toolbarTitle);

        cycleText = new TextView(this);
        cycleText.setText("0 cycles");
        cycleText.setTextSize(11);
        cycleText.setTextColor(thc(0xB3FFFFFF, 0xB3E0E0E0));
        cycleText.setTypeface(Typeface.MONOSPACE);
        cycleText.setPadding(dp(8), dp(4), dp(8), dp(4));
        cycleText.setBackgroundColor(thc(0x33000000, 0x33FFFFFF));
        cycleText.setMaxLines(1);
        cycleText.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_pill));
        toolbarContent.addView(cycleText);

        TextView themeBtn = new TextView(this);
        themeBtn.setText(isDarkTheme ? "\u2600" : "\u263E");
        themeBtn.setTextSize(16);
        themeBtn.setTextColor(thc(0xB3FFFFFF, 0xB3E0E0E0));
        themeBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
        themeBtn.setOnClickListener(v -> toggleTheme());
        toolbarContent.addView(themeBtn);

        toolbar.addView(toolbarContent);
        root.addView(toolbar, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
    }

    /** Builds the transport control bar (play/pause, step, stop) shown above the
     *  display. The floating FABs were removed: these buttons stay in a fixed,
     *  relevant position next to the display in every orientation. */
    private void buildTransportBar() {
        transportBar = new LinearLayout(this);
        transportBar.setOrientation(LinearLayout.HORIZONTAL);
        transportBar.setGravity(Gravity.CENTER_VERTICAL);
        transportBar.setPadding(dp(6), dp(2), dp(6), dp(2));
        transportBar.setBackgroundColor(thc(0xFFEEEEEE, thc(0xFF1E1E2E, 0xFF2D2D2D)));

        playPauseBtn = makeTransportButton(R.drawable.ic_play, "Play/Pause",
                thc(0xFF006847, 0xFF2E7D32), v -> togglePlayPause());
        stepBtn = makeTransportButton(R.drawable.ic_step, "Step",
                thc(0xFFFF8F00, 0xFFE65100), v -> stepSimulation());
        stopBtn = makeTransportButton(R.drawable.ic_stop, "Stop",
                thc(0xFFE53935, 0xFFCF6679), v -> stopSimulation());
        transportBar.addView(playPauseBtn);
        transportBar.addView(stepBtn);
        transportBar.addView(stopBtn);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
        transportBar.addView(spacer);

        TextView hint = new TextView(this);
        hint.setText("1 finger: pan  |  2 fingers: rotate  |  pinch: zoom");
        hint.setTextSize(10);
        hint.setTextColor(thc(0xFF888888, 0xFF777777));
        hint.setPadding(dp(4), 0, dp(4), 0);
        transportBar.addView(hint);
    }

    private ImageView makeTransportButton(int drawableRes, String desc, int bgColor,
                                          View.OnClickListener listener) {
        ImageView b = new ImageView(this);
        b.setImageResource(drawableRes);
        b.setContentDescription(desc);
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        bg.setColor(bgColor);
        b.setBackground(bg);
        DrawableCompat.setTint(b.getDrawable(), Color.WHITE);
        b.setOnClickListener(listener);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    private void buildDisplayArea() {
        displayColumn = new LinearLayout(this);
        displayColumn.setOrientation(LinearLayout.VERTICAL);
        displayColumn.setBackgroundColor(thc(0xFFF5F5F5, thc(0xFF2D2D2D, 0xFF37474F)));

        displayColumn.addView(transportBar, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        displayTabScroll = new HorizontalScrollView(this);
        displayTabScroll.setHorizontalScrollBarEnabled(false);
        displayTabScroll.setBackgroundColor(thc(0xFFFAFAFA, 0xFF121212));
        displayTabScroll.setVisibility(View.GONE);
        displayTabBar = new LinearLayout(this);
        displayTabBar.setOrientation(LinearLayout.HORIZONTAL);
        displayTabBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        displayTabScroll.addView(displayTabBar);
        displayColumn.addView(displayTabScroll, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        LinearLayout displayToolbar = new LinearLayout(this);
        displayToolbar.setOrientation(LinearLayout.HORIZONTAL);
        displayToolbar.setPadding(dp(4), dp(2), dp(4), dp(2));
        displayToolbar.setBackgroundColor(thc(0xFF333333, thc(0xFFE0E0E0, 0xFF424242)));
        displayToolbar.setGravity(Gravity.CENTER);
        displayToolbar.setVisibility(View.GONE);

        String[][] tools = {
            {"Zoom+", "+"}, {"Zoom-", "\u2212"}, {"Fit", "\u2195"}, {"Fullscreen", "\u26F6"}
        };
        for (String[] tool : tools) {
            TextView btn = new TextView(this);
            btn.setText(" " + tool[0] + " ");
            btn.setTextColor(thc(0xFFAAAAAA, 0xFF999999));
            btn.setTextSize(11);
            btn.setPadding(dp(6), dp(4), dp(6), dp(4));
            btn.setGravity(Gravity.CENTER);
            btn.setOnClickListener(v -> handleDisplayAction(tool[1]));
            if ("\u26F6".equals(tool[1])) fullscreenBtn = btn;
            displayToolbar.addView(btn);
        }
        displayToolbar.setTag("displayToolbar");
        displayColumn.addView(displayToolbar, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        displayContainer = new FrameLayout(this);
        displayContainer.setBackgroundColor(thc(0xFFE8E8E8, thc(0xFF333333, thc(0xFFE0E0E0, 0xFF424242))));
        displayContainer.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        displayColumn.addView(displayContainer, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

        dragHandle = new View(this);
        dragHandle.setBackgroundColor(thc(0xFFDDDDDD, 0xFF424242));
        dragHandle.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, dp(4)));
        setupDragHandle();

        bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanelLp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f);
    }

    /** Arranges the shared views for the current orientation. Portrait stacks the
     *  display above the bottom panel (with a drag handle); landscape places the
     *  display next to a side column holding the tabs and the bottom panel so the
     *  display keeps as much space as possible. */
    private void applyOrientation() {
        contentArea.removeAllViews();

        if (isLandscape) {
            mainRow = new LinearLayout(this);
            mainRow.setOrientation(LinearLayout.HORIZONTAL);
            mainRow.setBackgroundColor(thc(0xFFF5F5F5, thc(0xFF2D2D2D, 0xFF37474F)));

            displayColumnLp = new LinearLayout.LayoutParams(0, MATCH_PARENT, 2f);
            detachFromParent(displayColumn);
            mainRow.addView(displayColumn, displayColumnLp);

            rightCol = new LinearLayout(this);
            rightCol.setOrientation(LinearLayout.VERTICAL);
            rightCol.setBackgroundColor(thc(0xFFFAFAFA, 0xFF121212));
            LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f);
            mainRow.addView(rightCol, rightLp);

            detachFromParent(tabLayout);
            rightCol.addView(tabLayout, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            bottomPanelLp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f);
            detachFromParent(bottomPanel);
            rightCol.addView(bottomPanel, bottomPanelLp);

            contentArea.addView(mainRow, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        } else {
            LinearLayout displayLayout = new LinearLayout(this);
            displayLayout.setOrientation(LinearLayout.VERTICAL);
            displayLayout.setBackgroundColor(thc(0xFFF5F5F5, thc(0xFF2D2D2D, 0xFF37474F)));

            displayColumnLp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 3f);
            detachFromParent(displayColumn);
            displayLayout.addView(displayColumn, displayColumnLp);
            detachFromParent(dragHandle);
            displayLayout.addView(dragHandle);
            detachFromParent(bottomPanel);
            displayLayout.addView(bottomPanel, bottomPanelLp);

            contentArea.addView(displayLayout, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

            // Move the tabs back under the toolbar in portrait
            detachFromParent(tabLayout);
            int toolbarIndex = 0;
            for (int i = 0; i < rootLayout.getChildCount(); i++) {
                if (rootLayout.getChildAt(i) == toolbar) { toolbarIndex = i + 1; break; }
            }
            rootLayout.addView(tabLayout, toolbarIndex, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
        }

        // Restore the panel visibility state for the currently selected tab
        int pos = tabLayout.getSelectedTabPosition();
        if (pos < 0) pos = 0;
        showPanel(pos);
        displayColumn.requestLayout();
    }

    private void detachFromParent(View v) {
        ViewGroup parent = (ViewGroup) v.getParent();
        if (parent != null) parent.removeView(v);
    }

    private void setupDragHandle() {
        final float[] startY = new float[1];
        final float[] startDisplayWeight = new float[1];
        final float[] startBottomWeight = new float[1];

        dragHandle.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    startY[0] = event.getRawY();
                    startDisplayWeight[0] = displayColumnLp.weight;
                    startBottomWeight[0] = bottomPanelLp.weight;
                    v.setBackgroundColor(thc(0xFF006847, 0xFF2E7D32));
                    return true;
                case MotionEvent.ACTION_MOVE:
                    float dy = startY[0] - event.getRawY();
                    float totalHeight = ((View) v.getParent()).getHeight();
                    if (totalHeight <= 0) return true;
                    float deltaWeight = (dy / totalHeight) * 6f;
                    float newDisplay = Math.max(0.5f, startDisplayWeight[0] + deltaWeight);
                    float newBottom = Math.max(0.5f, startBottomWeight[0] - deltaWeight);
                    displayColumnLp.weight = newDisplay;
                    bottomPanelLp.weight = newBottom;
                    ((View) v.getParent()).requestLayout();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setBackgroundColor(thc(0xFFDDDDDD, 0xFF424242));
                    return true;
            }
            return false;
        });
    }

    private void buildConsolePanel() {
        consolePanel = new LinearLayout(this);
        consolePanel.setOrientation(LinearLayout.VERTICAL);
        consolePanel.setBackgroundColor(thc(0xFF1E1E1E, thc(0xFF0D0D0D, 0xFF000000)));
        consolePanel.setVisibility(View.GONE);

        LinearLayout consoleHeader = new LinearLayout(this);
        consoleHeader.setOrientation(LinearLayout.HORIZONTAL);
        consoleHeader.setPadding(dp(12), dp(8), dp(12), dp(8));
        consoleHeader.setBackgroundColor(thc(0xFF2D2D2D, 0xFF37474F));

        TextView consoleTab = new TextView(this);
        consoleTab.setText("Console");
        consoleTab.setTextColor(thc(0xFFCCCCCC, 0xFFBDBDBD));
        consoleTab.setTextSize(12);
        consoleTab.setTypeface(null, Typeface.BOLD);
        consoleHeader.addView(consoleTab);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
        consoleHeader.addView(spacer);

        MaterialButton clearBtn = new MaterialButton(this);
        clearBtn.setText("Clear");
        clearBtn.setTextSize(10);
        clearBtn.setCornerRadius(dp(12));
        clearBtn.setTextColor(thc(0xFF888888, thc(0xFFAAAAAA, 0xFF999999)));
        clearBtn.setBackgroundTintList(ColorStateList.valueOf(thc(0xFF444444, 0xFF666666)));
        clearBtn.setMinimumHeight(0);
        clearBtn.setMinimumWidth(0);
        clearBtn.setPadding(dp(8), dp(2), dp(8), dp(2));
        clearBtn.setOnClickListener(v -> logView.setText(""));
        consoleHeader.addView(clearBtn);
        consolePanel.addView(consoleHeader, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        logScroll = new ScrollView(this);
        logScroll.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        logView = new TextView(this);
        logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextColor(0xFF00FF00);
        logView.setPadding(dp(12), dp(8), dp(12), dp(8));
        logView.setBackgroundColor(thc(0xFF0D0D0D, 0xFF000000));
        logView.setMovementMethod(new ScrollingMovementMethod());
        logView.setVerticalScrollBarEnabled(true);
        logScroll.addView(logView);
        consolePanel.addView(logScroll, new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        bottomPanel.addView(consolePanel, new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    private void buildLayersPanel() {
        layersPanel = new LinearLayout(this);
        layersPanel.setOrientation(LinearLayout.VERTICAL);
        layersPanel.setBackgroundColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
        layersPanel.setVisibility(View.GONE);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(12));
        header.setBackgroundColor(thc(0xFFF5F5F5, thc(0xFF2D2D2D, 0xFF37474F)));

        TextView title = new TextView(this);
        title.setText("Layer Controls");
        title.setTextSize(15);
        title.setTextColor(thc(0xFF333333, thc(0xFFE0E0E0, 0xFF424242)));
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        header.addView(title);

        layersPanel.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        ScrollView layerScroll = new ScrollView(this);
        layerScroll.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        LinearLayout layerList = new LinearLayout(this);
        layerList.setId(R.id.layer_list);
        layerList.setOrientation(LinearLayout.VERTICAL);
        layerList.setPadding(dp(16), dp(8), dp(16), dp(8));
        layerScroll.addView(layerList);

        layersPanel.addView(layerScroll, new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        bottomPanel.addView(layersPanel, new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    private void buildParamsPanel() {
        paramsPanel = new LinearLayout(this);
        paramsPanel.setOrientation(LinearLayout.VERTICAL);
        paramsPanel.setBackgroundColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
        paramsPanel.setVisibility(View.GONE);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(16), dp(12), dp(16), dp(12));
        header.setBackgroundColor(thc(0xFFF5F5F5, thc(0xFF2D2D2D, 0xFF37474F)));

        TextView title = new TextView(this);
        title.setText("Parameters");
        title.setTextSize(15);
        title.setTextColor(thc(0xFF333333, thc(0xFFE0E0E0, 0xFF424242)));
        title.setTypeface(null, Typeface.BOLD);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        header.addView(title);
        paramsPanel.addView(header, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        ScrollView paramScroll = new ScrollView(this);
        paramScroll.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));

        LinearLayout paramList = new LinearLayout(this);
        paramList.setOrientation(LinearLayout.VERTICAL);
        paramList.setPadding(dp(16), dp(8), dp(16), dp(8));
        paramScroll.addView(paramList);

        // Speed slider
        MaterialCardView speedCard = new MaterialCardView(this);
        speedCard.setRadius(dp(8));
        speedCard.setCardElevation(dp(1));
        speedCard.setContentPadding(dp(16), dp(12), dp(16), dp(12));
        speedCard.setCardBackgroundColor(thc(0xFFFAFAFA, 0xFF121212));
        LinearLayout.LayoutParams speedCardLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
        speedCardLp.setMargins(0, dp(4), 0, dp(4));
        speedCard.setLayoutParams(speedCardLp);

        LinearLayout speedRow = new LinearLayout(this);
        speedRow.setOrientation(LinearLayout.HORIZONTAL);
        speedRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView speedLabel = new TextView(this);
        speedLabel.setText("Speed");
        speedLabel.setTextSize(14);
        speedLabel.setTextColor(thc(0xFF333333, thc(0xFFE0E0E0, 0xFF424242)));
        speedLabel.setTypeface(null, Typeface.BOLD);
        speedLabel.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        speedRow.addView(speedLabel);

        TextView speedValue = new TextView(this);
        speedValue.setText("50 ms");
        speedValue.setTextSize(12);
        speedValue.setTextColor(thc(0xFF006847, 0xFF2E7D32));
        speedValue.setTypeface(Typeface.MONOSPACE);
        speedRow.addView(speedValue);
        speedCard.addView(speedRow);

        Slider speedSlider = new Slider(this);
        speedSlider.setValueFrom(1);
        speedSlider.setValueTo(500);
        speedSlider.setValue(50);
        speedSlider.setStepSize(1);
        speedSlider.setTrackHeight(dp(3));
        speedSlider.setThumbRadius(dp(8));
        speedSlider.setContentDescription("Simulation speed");
        speedSlider.addOnChangeListener((slider, value, fromUser) -> {
            int ms = (int) value;
            speedValue.setText(ms + " ms");
            if (currentController != null) {
                try {
                    setSimulationSpeedMs(currentController, ms);
                } catch (Exception e) {
                    Log.w(TAG, "Set speed error", e);
                }
            }
        });
        speedCard.addView(speedSlider);
        paramList.addView(speedCard);

        paramsPanel.addView(paramScroll, new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        bottomPanel.addView(paramsPanel, new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    private void showPanel(int position) {
        boolean displayTab = position == 0;
        if (isLandscape) {
            // Keep the tabs reachable in the side column; only the panel itself
            // collapses so the display can use the extra space.
            if (rightCol != null) rightCol.setVisibility(View.VISIBLE);
            bottomPanel.setVisibility(displayTab ? View.GONE : View.VISIBLE);
            if (displayColumnLp != null) displayColumnLp.weight = displayTab ? 3f : 2f;
        } else {
            dragHandle.setVisibility(displayTab ? View.GONE : View.VISIBLE);
            bottomPanel.setVisibility(displayTab ? View.GONE : View.VISIBLE);
            if (displayColumnLp != null) displayColumnLp.weight = displayTab ? 1f : 3f;
        }
        if (bottomPanelLp != null) bottomPanelLp.weight = 1f;
        consolePanel.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        layersPanel.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
        paramsPanel.setVisibility(position == 3 ? View.VISIBLE : View.GONE);
        displayColumn.setVisibility(View.VISIBLE);

        if (position == 2) refreshLayerList();
        displayColumn.requestLayout();
    }

    private void refreshLayerList() {
        LinearLayout layerList = findViewById(R.id.layer_list);
        if (layerList == null) return;
        layerList.removeAllViews();

        if (layerInfos.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No layers available");
            empty.setTextSize(14);
            empty.setTextColor(thc(0xFF888888, thc(0xFFAAAAAA, 0xFF999999)));
            empty.setPadding(0, dp(16), 0, dp(16));
            empty.setGravity(Gravity.CENTER);
            layerList.addView(empty);
            return;
        }

        for (int i = 0; i < layerInfos.size(); i++) {
            LayerInfo info = layerInfos.get(i);
            MaterialCardView card = new MaterialCardView(this);
            card.setRadius(dp(8));
            card.setCardElevation(dp(1));
            card.setContentPadding(dp(12), dp(8), dp(12), dp(8));
            card.setCardBackgroundColor(thc(0xFFFAFAFA, 0xFF121212));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT);
            cardLp.setMargins(0, dp(4), 0, dp(4));
            card.setLayoutParams(cardLp);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);

            TextView nameText = new TextView(this);
            nameText.setText(info.name);
            nameText.setTextSize(13);
            nameText.setTextColor(thc(0xFF333333, thc(0xFFE0E0E0, 0xFF424242)));
            int icon = info.visible ? R.drawable.ic_layers : R.drawable.ic_stop;
            nameText.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    ContextCompat.getDrawable(this, icon), null, null, null);
            nameText.setPadding(dp(8), dp(4), dp(8), dp(4));
            nameText.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
            row.addView(nameText);

            TextView visBtn = new TextView(this);
            visBtn.setText(info.visible ? "VISIBLE" : "HIDDEN");
            visBtn.setTextSize(10);
            visBtn.setTypeface(null, Typeface.BOLD);
            visBtn.setTextColor(info.visible ? thc(0xFF4CAF50, 0xFF81C784) : thc(0xFF888888, thc(0xFFAAAAAA, 0xFF999999)));
            visBtn.setPadding(dp(8), dp(4), dp(8), dp(4));
            final int idx = i;
            visBtn.setOnClickListener(v -> {
                info.visible = !info.visible;
                refreshLayerList();
            });
            row.addView(visBtn);

            card.addView(row);

            Slider opacitySlider = new Slider(this);
            opacitySlider.setValueFrom(0);
            opacitySlider.setValueTo(1);
            opacitySlider.setValue(info.opacity);
            opacitySlider.setStepSize(0.05f);
            opacitySlider.setTrackHeight(dp(3));
            opacitySlider.setThumbRadius(dp(8));
            opacitySlider.setContentDescription(info.name + " opacity");
            opacitySlider.addOnChangeListener((slider, value, fromUser) -> {
                info.opacity = value;
            });
            card.addView(opacitySlider);

            layerList.addView(card);
        }
    }

    private void startEngine() {
        if (!GamaNativeBootstrap.isInitialized()) {
            log("Initializing GAMA engine...");
            new Thread(() -> {
                try {
                    GamaNativeBootstrap.initialize(this, new GamaNativeBootstrap.ProgressCallback() {
                        @Override public void onProgress(String msg) { log("  " + msg); }
                        @Override public void onSuccess(String msg) { log("  " + msg); startCompilation(); }
                        @Override public void onFailure(String msg, Throwable t) { log("  FAIL: " + msg); }
                    });
                } catch (Exception e) {
                    Log.e(TAG, "Bootstrap failed", e);
                    log("Bootstrap error: " + e.getMessage());
                }
            }).start();
        } else {
            startCompilation();
        }
    }

    private void startCompilation() {
        runOnUiThread(() -> {
            String assetPath = getIntent().getStringExtra("asset_path");
            String jarPath = getIntent().getStringExtra("jar_path");
            String filePath = getIntent().getStringExtra("file_path");
            boolean fromLibrary = getIntent().getBooleanExtra("from_library", false);

            String effectivePath = jarPath != null ? jarPath : modelName;

            if (filePath != null) {
                compileModelFromFilePath(filePath);
            } else if (assetPath != null) {
                compileModelFromAsset(assetPath);
            } else if (fromLibrary && effectivePath != null) {
                compileModelFromLibrary(effectivePath);
            } else if (effectivePath != null) {
                compileModelFromLibrary(effectivePath);
            }
        });
    }

    // ---- Tab/UI Switching ----

    public void onDisplayRegistered(String displayName, AndroidDisplaySurface surface) {
        Log.i(TAG, "onDisplayRegistered: " + displayName + " (container=" + (getDisplayContainer() != null) + ")");
        if (activeDisplayName == null) {
            activeDisplayName = displayName;
            surface.setVisibility(View.VISIBLE);
        } else {
            surface.setVisibility(View.GONE);
        }

        MaterialButton tab = new MaterialButton(this);
        tab.setText(displayName);
        tab.setTextSize(11);
        tab.setTypeface(null, Typeface.BOLD);
        tab.setCornerRadius(dp(16));
        tab.setPadding(dp(12), dp(4), dp(12), dp(4));
        tab.setMinimumHeight(0);
        tab.setMinimumWidth(0);
        boolean isActive = activeDisplayName.equals(displayName);
        tab.setBackgroundTintList(ColorStateList.valueOf(isActive ? thc(0xFF006847, 0xFF2E7D32) : thc(0xFFE0E0E0, 0xFF424242)));
        tab.setTextColor(isActive ? thc(0xFFFFFFFF, 0xFF1E1E2E) : thc(0xFF666666, 0xFF999999));
        LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(WRAP_CONTENT, dp(32));
        tabLp.setMargins(dp(4), dp(2), dp(4), dp(2));
        tab.setLayoutParams(tabLp);
        tab.setOnClickListener(v -> selectDisplay(displayName));
        displayTabBar.addView(tab);

        if (displayTabBar.getChildCount() > 1) {
            displayTabScroll.setVisibility(View.VISIBLE);
        }
        View dt = displayColumn.findViewWithTag("displayToolbar");
        if (dt != null) dt.setVisibility(View.VISIBLE);
    }

    private void selectDisplay(String displayName) {
        if (displayName.equals(activeDisplayName)) return;
        activeDisplayName = displayName;

        for (int i = 0; i < displayContainer.getChildCount(); i++) {
            displayContainer.getChildAt(i).setVisibility(View.GONE);
        }
        for (int i = 0; i < displayTabBar.getChildCount(); i++) {
            View tabView = displayTabBar.getChildAt(i);
            if (tabView instanceof MaterialButton) {
                MaterialButton tab = (MaterialButton) tabView;
                boolean isActive = tab.getText().toString().trim().equals(displayName);
                tab.setBackgroundTintList(ColorStateList.valueOf(isActive ? thc(0xFF006847, 0xFF2E7D32) : thc(0xFFE0E0E0, 0xFF424242)));
                tab.setTextColor(isActive ? thc(0xFFFFFFFF, 0xFF1E1E2E) : thc(0xFF666666, 0xFF999999));
            }
        }

        java.util.Map<String, AndroidDisplaySurface> surfaces =
                com.gama.nativeapp.gui.AndroidGuiHandler.getInstance().getDisplaySurfaces();
        AndroidDisplaySurface activeSurface = surfaces.get(displayName);
        if (activeSurface != null) {
            activeSurface.setVisibility(View.VISIBLE);
            activeSurface.invalidate();
        }
    }

    private void handleDisplayAction(String action) {
        if ("\u26F6".equals(action)) {
            toggleFullscreen();
            return;
        }
        View target = getActiveDisplayView();
        if (target == null) return;
        try {
            switch (action) {
                case "+":
                    target.getClass().getMethod("zoomIn").invoke(target);
                    break;
                case "\u2212":
                    target.getClass().getMethod("zoomOut").invoke(target);
                    break;
                case "\u2195":
                    target.getClass().getMethod("zoomFit").invoke(target);
                    break;
            }
        } catch (Exception e) {
            Log.w(TAG, "Display action failed", e);
        }
    }

    /** Expands the display so it fills the whole phone screen, hiding the app
     *  toolbar, tabs, bottom panel, FABs and system bars. Tapping again restores. */
    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;

        toolbar.setVisibility(isFullscreen ? View.GONE : View.VISIBLE);
        tabLayout.setVisibility(isFullscreen ? View.GONE : View.VISIBLE);
        dragHandle.setVisibility(isFullscreen ? View.GONE : View.VISIBLE);
        bottomPanel.setVisibility(isFullscreen ? View.GONE : View.VISIBLE);
        transportBar.setVisibility(isFullscreen ? View.GONE : View.VISIBLE);
        if (isFullscreen) {
            displayTabScrollVisibility = displayTabScroll.getVisibility();
            displayTabScroll.setVisibility(View.GONE);
        } else {
            displayTabScroll.setVisibility(displayTabScrollVisibility);
        }
        if (isFullscreen) {
            savedDisplayWeight = displayColumnLp.weight;
            displayColumnLp.height = 0;
            displayColumnLp.weight = 1f;
        } else {
            displayColumnLp.weight = savedDisplayWeight;
            showPanel(tabLayout.getSelectedTabPosition());
        }
        displayColumn.requestLayout();

        View decor = getWindow().getDecorView();
        if (isFullscreen) {
            decor.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        } else {
            decor.setSystemUiVisibility(0);
        }

        if (fullscreenBtn != null) {
            fullscreenBtn.setTextColor(isFullscreen
                    ? thc(0xFF006847, 0xFF81C784)
                    : thc(0xFFAAAAAA, 0xFF999999));
        }

        View target = getActiveDisplayView();
        if (target != null) {
            target.post(() -> {
                try {
                    if (target instanceof com.gama.nativeapp.display.AndroidDisplaySurface) {
                        ((com.gama.nativeapp.display.AndroidDisplaySurface) target).setFillScreen(isFullscreen);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Fullscreen refit failed", e);
                }
            });
        }
    }

    private View getActiveDisplayView() {
        if (activeDisplayName == null) return null;
        try {
            java.util.Map<String, AndroidDisplaySurface> surfaces =
                    com.gama.nativeapp.gui.AndroidGuiHandler.getInstance().getDisplaySurfaces();
            return surfaces.get(activeDisplayName);
        } catch (Exception e) {
            return null;
        }
    }

    private int thc(int light, int dark) { return isDarkTheme ? dark : light; }

    private void toggleTheme() {
        isDarkTheme = !isDarkTheme;
        getSharedPreferences("gama_prefs", 0).edit().putBoolean("dark_theme", isDarkTheme).apply();
        recreate();
    }

    // ---- Simulation Controls ----

    private void togglePlayPause() {
        if (!isRunning || currentController == null) return;
        try {
            Class<?> ctrlInterface = Class.forName("gama.api.kernel.simulation.IExperimentController");
            if (!isPaused) {
                ctrlInterface.getMethod("processPause", boolean.class).invoke(currentController, true);
                isPaused = true;
                setTransportIcon(playPauseBtn, R.drawable.ic_play);
                stepBtn.setAlpha(1f);
                log("Paused");
            } else {
                ctrlInterface.getMethod("processStart", boolean.class).invoke(currentController, true);
                isPaused = false;
                setTransportIcon(playPauseBtn, R.drawable.ic_pause);
                stepBtn.setAlpha(0.45f);
                log("Resumed");
            }
        } catch (Exception e) {
            Log.w(TAG, "Toggle pause error", e);
        }
    }

    /** Runs exactly one simulation cycle while staying paused, using the
     *  controller's synchronous step command (the old code just un-paused and
     *  let the simulation run on, so stepping never advanced a single cycle). */
    private void stepSimulation() {
        if (!isRunning || !isPaused || currentController == null) return;
        try {
            Class<?> ctrlInterface = Class.forName("gama.api.kernel.simulation.IExperimentController");
            ctrlInterface.getMethod("processStep", int.class, boolean.class)
                    .invoke(currentController, 1, true);
            log("Step executed");
        } catch (Exception e) { Log.w(TAG, "Step error", e); }
    }

    private void setTransportIcon(ImageView btn, int res) {
        btn.setImageResource(res);
        DrawableCompat.setTint(btn.getDrawable(), Color.WHITE);
    }

    private void stopSimulation() {
        if (!isRunning) return;
        isRunning = false;
        isPaused = false;
        if (statePollRunnable != null) handler.removeCallbacks(statePollRunnable);
        if (clockUpdateRunnable != null) handler.removeCallbacks(clockUpdateRunnable);
        try {
            if (currentController != null) {
                Class<?> ctrlInterface = Class.forName("gama.api.kernel.simulation.IExperimentController");
                // Resume a paused experiment first so close() is not issued while
                // the execution thread is blocked on the pause lock.
                ctrlInterface.getMethod("processStart", boolean.class).invoke(currentController, false);
                ctrlInterface.getMethod("close").invoke(currentController);
                // The desktop path removes the controller from the static GAMA.controllers
                // list (see GAMA.closeController). Calling close() directly leaks the whole
                // experiment graph (plan -> model -> types -> sim -> displays -> activity)
                // because the controller stays reachable from that static list forever.
                Class<?> gamaClass = Class.forName("gama.api.GAMA");
                java.lang.reflect.Field controllersField = gamaClass.getDeclaredField("controllers");
                controllersField.setAccessible(true);
                java.util.List controllers = (java.util.List) controllersField.get(null);
                boolean removed = controllers.remove(currentController);
                Log.i(TAG, "Stopped: removed=" + removed + " controllers.size=" + controllers.size());
            }
        } catch (Exception e) { Log.w(TAG, "Stop error", e); }
        handler.post(() -> {
            toolbarTitle.setText(modelName + " (stopped)");
            cycleText.setText("Stopped");
            setTransportIcon(playPauseBtn, R.drawable.ic_play);
            stepBtn.setAlpha(0.45f);
        });
    }

    // ---- Compilation ----

    private void showCompilationError(String summary, List<Object> errors) {
        StringBuilder sb = new StringBuilder(summary).append("\n\n");
        for (Object err : errors) sb.append("\u2022 ").append(err).append("\n");
        String fullMsg = sb.toString();
        log(fullMsg);
        postUi(() -> {
            contentArea.removeAllViews();
            ScrollView scroll = new ScrollView(this);
            TextView errText = new TextView(this);
            errText.setText(fullMsg);
            errText.setTextSize(12);
            errText.setTextColor(thc(0xFFE53935, 0xFFCF6679));
            errText.setTypeface(Typeface.MONOSPACE);
            errText.setPadding(dp(16), dp(16), dp(16), dp(16));
            scroll.addView(errText);
            contentArea.addView(scroll);
        });
    }

    private void compileModelFromAsset(String assetPath) {
        log("Compiling: " + assetPath);
        COMPILE_EXECUTOR.execute(() -> {
            try {
                File cacheDir = getCacheDir();
                File modelFile = new File(cacheDir, assetPath);
                modelFile.getParentFile().mkdirs();
                try (InputStream is = getAssets().open(assetPath);
                     FileOutputStream fos = new FileOutputStream(modelFile)) {
                    byte[] buf = new byte[4096]; int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }
                extractIncludesFromJarForAsset(modelFile, assetPath);
                Object model = compileFile(modelFile);
                if (model != null) postUi(() -> showExperiments(model));
            } catch (Exception e) {
                Log.e(TAG, "Compilation error", e);
                String msg = "ERROR: " + rootMessage(e);
                log(msg);
                postUi(() -> showError(msg));
            }
        });
    }

    private void compileModelFromFilePath(String filePath) {
        log("Compiling: " + filePath);
        COMPILE_EXECUTOR.execute(() -> {
            try {
                File modelFile = new File(filePath);
                if (!modelFile.exists() || !modelFile.canRead()) {
                    String reason = !modelFile.exists() ? "file does not exist"
                            : "file exists but is not readable (permission denied)";
                    if (tryCompileLibraryFallback(filePath)) return;
                    log("ERROR: File not found: " + filePath + " (" + reason + ")");
                    postUi(() -> showError("File not found: " + filePath + " (" + reason + ")"));
                    return;
                }
                Object model = compileFile(modelFile);
                if (model != null) postUi(() -> showExperiments(model));
            } catch (Exception e) {
                Log.e(TAG, "File compilation error", e);
                String msg = "ERROR: " + rootMessage(e);
                if (isFileAccessError(e) && tryCompileLibraryFallback(filePath)) return;
                log(msg);
                postUi(() -> showError(msg));
            }
        });
    }

    /**
     * When a workspace file is missing or unreadable (e.g. copied with the wrong
     * ownership/permissions), find the matching model in the bundled library and
     * compile that instead, so the same model still runs from the workspace entry.
     * Returns true if a library match was found and compilation was started.
     */
    private boolean tryCompileLibraryFallback(String filePath) {
        try {
            String fileName = new File(filePath).getName();
            File cacheJar = LibraryJarUtil.ensureCached(this);
            if (cacheJar == null) return false;
            String match = null;
            try (JarFile jarFile = new JarFile(cacheJar)) {
                match = reconstructFromWorkspacePath(jarFile, filePath);
                if (match == null) {
                    java.util.Enumeration<? extends JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry e = entries.nextElement();
                        if (e.isDirectory()) continue;
                        String name = e.getName();
                        if (!name.endsWith("/" + fileName)) continue;
                        if (name.contains("/models/")) { match = name; break; }
                        if (match == null) match = name;
                    }
                }
            }
            if (match == null) return false;
            log("Workspace file unreadable/missing, compiling library copy instead: " + match);
            compileModelFromLibrary(match);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Library fallback failed", e);
            return false;
        }
    }

    /**
     * Workspace copies of library projects are stored under a folder named by
     * WorkspaceManager.sanitizePath(projectKey), e.g. the library entry
     * "models/Toy Models/Evacuation/models/Evacuation Phuc Xa.gaml" becomes
     * "<workspace>/models_Toy Models_Evacuation/models/Evacuation Phuc Xa.gaml".
     * Invert that mapping (replace '_' with '/') and check the jar for the exact
     * entry so we resolve the correct library model deterministically.
     */
    private String reconstructFromWorkspacePath(JarFile jarFile, String filePath) {
        try {
            String root = WorkspaceManager.workspaceRoot(this).getAbsolutePath();
            String rel = filePath;
            if (rel.startsWith(root + "/")) rel = rel.substring(root.length() + 1);
            int slash = rel.indexOf('/');
            if (slash <= 0) return null;
            String folder = rel.substring(0, slash);
            if (!folder.contains("_")) return null;
            String rest = rel.substring(slash + 1);
            String candidate = folder.replace('_', '/') + "/" + rest;
            if (jarFile.getJarEntry(candidate) != null) return candidate;
            if (rest.startsWith("models/")) {
                String alt = folder.replace('_', '/') + "/" + rest;
                if (jarFile.getJarEntry(alt) != null) return alt;
            }
            return null;
        } catch (Exception e) {
            Log.e(TAG, "Reconstruct failed", e);
            return null;
        }
    }

    private static boolean isFileAccessError(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur instanceof java.io.FileNotFoundException) return true;
            if (cur instanceof java.io.IOException) {
                String m = cur.getMessage();
                if (m != null && (m.contains("EACCES") || m.contains("Permission denied")
                        || m.contains("open failed") || m.contains("No such file"))) return true;
            }
            cur = cur.getCause();
        }
        return false;
    }

    private void compileModelFromLibrary(String jarEntryPath) {
        log("Compiling from library: " + jarEntryPath);
        COMPILE_EXECUTOR.execute(() -> {
            try {
                File cacheDir = getCacheDir();
                File cacheJar = LibraryJarUtil.ensureCached(this);
                if (cacheJar == null) {
                    log("ERROR: Library jar not available");
                    return;
                }

                JarFile jarFile = new JarFile(cacheJar);
                JarEntry entry = jarFile.getJarEntry(jarEntryPath);
                if (entry == null) {
                    jarFile.close();
                    log("ERROR: Entry not found");
                    postUi(() -> showError("Entry not found in library: " + jarEntryPath));
                    return;
                }

                String parentPath = jarEntryPath.substring(0, jarEntryPath.lastIndexOf('/') + 1);
                String projectRoot = parentPath.endsWith("models/") ?
                        parentPath.substring(0, parentPath.length() - "models/".length()) : "";

                boolean force = LibraryJarUtil.isExtractionStale(this);
                if (!force) {
                    jarFile.close();
                } else {
                    java.util.Enumeration<? extends JarEntry> entries = jarFile.entries();
                    while (entries.hasMoreElements()) {
                        JarEntry e = entries.nextElement();
                        String eName = e.getName();
                        if (e.isDirectory() || !eName.startsWith(projectRoot)) continue;
                        String relativePath = eName.substring(projectRoot.length());
                        if (relativePath.isEmpty()) continue;
                        File outFile = new File(cacheDir, projectRoot + relativePath);
                        if (outFile.exists()) {
                            if (outFile.lastModified() > cacheJar.lastModified()) continue;
                        }
                        outFile.getParentFile().mkdirs();
                        try (InputStream is = jarFile.getInputStream(e);
                             FileOutputStream fos = new FileOutputStream(outFile)) {
                            byte[] buf = new byte[4096]; int n;
                            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                        }
                    }
                    // Do NOT markExtracted here: only the full library extraction
                    // (ModelNavigatorActivity) marks completion, otherwise a partial
                    // extraction could cause the full one to be skipped.
                    jarFile.close();
                }

                File modelFile = new File(cacheDir, jarEntryPath);
                Object model = compileFile(modelFile);
                if (model != null) postUi(() -> showExperiments(model));
            } catch (Exception e) {
                Log.e(TAG, "Library compilation error", e);
                String msg = "ERROR: " + rootMessage(e);
                log(msg);
                postUi(() -> showError(msg));
            }
        });
    }

    private void postUi(Runnable r) {
        handler.post(() -> { if (!destroyed) r.run(); });
    }

    private Object compileFile(File modelFile) throws Exception {
        Class<?> builderClass = Class.forName("gaml.compiler.validation.GamlModelBuilder");
        Object builder = builderClass.getMethod("getInstance").invoke(null);
        Class<?> uriClass = Class.forName("org.eclipse.emf.common.util.URI");
        Object uri = uriClass.getMethod("createFileURI", String.class).invoke(null, modelFile.getAbsolutePath());
        List<Object> errors = new ArrayList<>();
        Class<?> modelClass = Class.forName("gama.api.kernel.species.IModelSpecies");
        Object model;
        try {
            model = builderClass.getMethod("compile", uriClass, List.class).invoke(builder, uri, errors);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            Throwable cause = ite.getCause() != null ? ite.getCause() : ite;
            if (cause instanceof Exception) throw (Exception) cause;
            throw new RuntimeException(cause);
        }

        if (model == null) {
            if (errors.isEmpty()) {
                showCompilationError("Compilation FAILED (no diagnostics reported)", errors);
            } else {
                showCompilationError("Compilation FAILED (" + errors.size() + " errors)", errors);
            }
            return null;
        }

        compiledModel = model;
        String name = (String) modelClass.getMethod("getName").invoke(model);
        log("Compiled: " + name);
        return model;
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur != null) {
            if (cur.getMessage() != null && !cur.getMessage().isEmpty()) {
                return cur.getClass().getSimpleName() + ": " + cur.getMessage();
            }
            cur = cur.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t;
    }

    private void extractIncludesFromJarForAsset(File modelFile, String assetPath) {
        try {
            File cacheJar = LibraryJarUtil.ensureCached(this);
            if (cacheJar == null) return;
            if (!LibraryJarUtil.isExtractionStale(this)) return;
            JarFile jarFile = new JarFile(cacheJar);
            String modelFileName = assetPath.substring(assetPath.lastIndexOf('/') + 1);
            boolean force = true;
            java.util.Enumeration<? extends JarEntry> entries = jarFile.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                String eName = e.getName();
                if (e.isDirectory() || !eName.contains("/includes/")) continue;
                String includePath = eName.substring(eName.indexOf("/includes/") + "/includes/".length());
                File outFile = new File(modelFile.getParentFile(), "../includes/" + includePath);
                if (outFile.exists()) {
                    if (!force) continue;
                    if (outFile.lastModified() > cacheJar.lastModified()) continue;
                }
                outFile.getParentFile().mkdirs();
                try (InputStream is = jarFile.getInputStream(e);
                     FileOutputStream fos = new FileOutputStream(outFile)) {
                    byte[] buf = new byte[4096]; int n;
                    while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
                }
            }
            // Do NOT markExtracted here: only the full library extraction
            // (ModelNavigatorActivity) marks completion.
            jarFile.close();
        } catch (Exception e) { log("Includes: " + e.getMessage()); }
    }

    private void showExperiments(Object model) {
        try {
            Class<?> modelClass = Class.forName("gama.api.kernel.species.IModelSpecies");
            java.lang.reflect.Method getExps = modelClass.getMethod("getExperiments");
            Iterable<?> experiments = (Iterable<?>) getExps.invoke(model);
            List<Object> expList = new ArrayList<>();
            for (Object exp : experiments) expList.add(exp);

            log("Found " + expList.size() + " experiment(s)");

            if (expList.isEmpty()) {
                log("No experiments found");
                showError("No experiments found in model");
                return;
            }

            Toast.makeText(this, "Found " + expList.size() + " experiment(s)!", Toast.LENGTH_LONG).show();

            // Create a simple dialog to pick experiment
            String[] names = new String[expList.size()];
            for (int i = 0; i < expList.size(); i++) {
                names[i] = (String) expList.get(i).getClass().getMethod("getName").invoke(expList.get(i));
                log("  Experiment: " + names[i]);
            }

            String requested = getIntent().getStringExtra("experiment_name");
            if (requested != null && !requested.isEmpty()) {
                for (int i = 0; i < expList.size(); i++) {
                    if (requested.equals(names[i])) {
                        log("Auto-starting experiment: " + names[i]);
                        runExperiment(expList.get(i), names[i]);
                        return;
                    }
                }
                log("Experiment '" + requested + "' not found, showing picker");
            }

            new androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Select Experiment")
                    .setItems(names, (dialog, which) -> {
                        dialog.dismiss();
                        try {
                            runExperiment(expList.get(which), names[which]);
                        } catch (Exception ex) {
                            log("Error: " + ex.getMessage());
                        }
                    })
                    .setCancelable(false)
                    .show();
        } catch (Exception e) {
            log("Error listing experiments: " + e.getMessage());
            showError("Error: " + e.getMessage());
        }
    }

    private void showError(String message) {
        postUi(() -> {
            contentArea.removeAllViews();
            TextView errText = new TextView(this);
            errText.setText(message);
            errText.setTextSize(14);
            errText.setTextColor(thc(0xFFE53935, 0xFFCF6679));
            errText.setGravity(Gravity.CENTER);
            errText.setPadding(dp(32), dp(32), dp(32), dp(32));
            contentArea.addView(errText);
        });
    }

    private void runExperiment(Object expPlan, String expName) {
        log("Starting: " + expName);
        currentExpPlan = expPlan;
        isRunning = true;
        isPaused = false;
        activeDisplayName = null;
        displayTabBar.removeAllViews();
        displayTabScroll.setVisibility(View.GONE);
        displayContainer.removeAllViews();
        layerInfos.clear();

        try {
            Class<?> guiHandlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
            Object guiHandler = guiHandlerClass.getMethod("getInstance").invoke(null);
            guiHandlerClass.getMethod("clearDisplayState", Activity.class).invoke(guiHandler, this);
        } catch (Exception e) { Log.w(TAG, "Clear state error", e); }

        handler.post(() -> toolbarTitle.setText(expName));

        new Thread(() -> {
            try {
                Class<?> guiHandlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
                Object guiHandler = guiHandlerClass.getMethod("getInstance").invoke(null);

                Class<?> gamaClass = Class.forName("gama.api.GAMA");
                gamaClass.getMethod("setHeadlessGui", Class.forName("gama.api.ui.IGui"))
                        .invoke(null, guiHandler);
                gamaClass.getMethod("setRegularGui", Class.forName("gama.api.ui.IGui"))
                        .invoke(null, guiHandler);

                Class<?> expClass = Class.forName("gama.api.kernel.species.IExperimentSpecies");
                expClass.getMethod("setHeadless", boolean.class).invoke(expPlan, false);
                expClass.getMethod("open").invoke(expPlan);

                Object controller = expClass.getMethod("getController").invoke(expPlan);
                currentController = controller;

                logDiagnostics(expPlan);

                Class<?> ctrlInterface = Class.forName("gama.api.kernel.simulation.IExperimentController");
                setSimulationSpeedMs(controller, 0);

                java.lang.reflect.Field controllersField = gamaClass.getDeclaredField("controllers");
                controllersField.setAccessible(true);
                @SuppressWarnings("unchecked")
                java.util.List controllers = (java.util.List) controllersField.get(null);
                controllers.add(controller);
                Log.i(TAG, "Started: controllers.size=" + controllers.size());

                setupStdoutRedirect();
                ctrlInterface.getMethod("processStart", boolean.class).invoke(controller, true);

                Class<?> absControllerClass = Class.forName("gama.api.kernel.simulation.DefaultExperimentController").getSuperclass();
                java.lang.reflect.Field pField = absControllerClass.getDeclaredField("paused");
                pField.setAccessible(true); pField.setBoolean(controller, false);
                java.lang.reflect.Field lField = absControllerClass.getDeclaredField("lock");
                lField.setAccessible(true);
                Object lock = lField.get(controller);
                lock.getClass().getMethod("release").invoke(lock);

                log("Experiment started");
                startStatePolling(controller);
            } catch (Exception e) {
                Log.e(TAG, "Run error", e);
                log("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                Throwable cause = e.getCause();
                while (cause != null) {
                    log("  CAUSE: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                    cause = cause.getCause();
                }
                handler.post(() -> { isRunning = false; toolbarTitle.setText(modelName + " (error)"); });
            }
        }).start();
    }

    private void setupStdoutRedirect() {
        try {
            if (originalOut == null) originalOut = System.out;
            if (originalErr == null) originalErr = System.err;
            final PrintStream origErr = originalErr;
            final PrintStream origOut = originalOut;
            System.setErr(new PrintStream(new java.io.OutputStream() {
                @Override public void write(int b) { origErr.write(b); }
                @Override public void write(byte[] b, int off, int len) {
                    origErr.write(b, off, len);
                    String s = new String(b, off, len).trim();
                    if (!s.isEmpty()) Log.e(TAG, s);
                }
            }, true));
            System.setOut(new PrintStream(new java.io.OutputStream() {
                @Override public void write(int b) { origOut.write(b); }
                @Override public void write(byte[] b, int off, int len) {
                    origOut.write(b, off, len);
                    String s = new String(b, off, len).trim();
                    if (!s.isEmpty()) Log.i(TAG, s);
                }
            }, true));
        } catch (Exception e) { Log.w(TAG, "Redirect error", e); }
    }

    private void startStatePolling(Object controller) {
        cacheReflectionFields(controller);
        final long startTime = System.currentTimeMillis();
        final int[] lastCycle = {-1};
        final long[] lastInvalidate = {0};

        statePollRunnable = () -> {
            if (!isRunning) return;
            try {
                if (aliveField != null) {
                    boolean alive = aliveField.getBoolean(controller);
                    if (!alive) {
                        Log.i(TAG, "Experiment finished (alive=false)");
                        handler.post(() -> {
                            toolbarTitle.setText(modelName + " (finished)");
                            cycleText.setText("Completed");
                            setTransportIcon(playPauseBtn, R.drawable.ic_play);
                            stepBtn.setAlpha(0.45f);
                        });
                        isRunning = false;
                        return;
                    }
                }

                int cycleCount = -1;
                try {
                    if (scopeField != null && getClockMethod != null && getCycleMethod != null) {
                        Object scope = scopeField.get(controller);
                        if (scope != null) {
                            Object clock = getClockMethod.invoke(scope);
                            if (clock != null) cycleCount = (int) getCycleMethod.invoke(clock);
                        }
                    }
                } catch (Exception e) {}

                boolean changed = cycleCount >= 0 && cycleCount != lastCycle[0];
                if (cycleCount >= 0) lastCycle[0] = cycleCount;
                if (changed && scopeField != null) {
                    try {
                        Object scope = scopeField.get(controller);
                        if (scope != null && (cycleCount % 3 == 0)) dumpAntState(scope, cycleCount);
                    } catch (Exception ignored) {}
                }

                long elapsed = System.currentTimeMillis() - startTime;
                long min = (elapsed / 1000) / 60;
                long sec = (elapsed / 1000) % 60;

                long now = System.currentTimeMillis();
                boolean stale = now - lastInvalidate[0] > 1000;
                final int finalCycle = cycleCount;
                handler.post(() -> {
                    String cycleStr = finalCycle >= 0 ? String.valueOf(finalCycle) : "?";
                    cycleText.setText(cycleStr + " cycles  " +
                            String.format("%02d:%02d", min, sec));
                    if (changed || stale) {
                        lastInvalidate[0] = System.currentTimeMillis();
                        updateDisplays();
                    }
                });
            } catch (Exception e) {
                Log.w(TAG, "Poll error: " + e.getMessage());
            }
            if (isRunning) handler.postDelayed(statePollRunnable, 100);
        };
        handler.postDelayed(statePollRunnable, 100);
    }

    private void dumpAntState(Object scope, int cycle) {
        try {
            Object sim = scope.getClass().getMethod("getSimulation").invoke(scope);
            if (sim == null) { Log.i(TAG, "DIAG: no simulation"); return; }
            Object pop = null;
            try { pop = sim.getClass().getMethod("getPopulationFor", String.class).invoke(sim, "ant"); } catch (Exception e) {}
            if (pop == null) {
                try { pop = findMicroPopulationReflect(sim, "ant", 0); } catch (Exception e) {}
            }
            if (pop == null) { Log.i(TAG, "DIAG: ant population not found (cycle=" + cycle + ")"); return; }
            java.lang.reflect.Method sizeM = pop.getClass().getMethod("size");
            int size = (int) sizeM.invoke(pop);
            if (size == 0) { Log.i(TAG, "DIAG: ant population empty"); return; }
            java.lang.reflect.Method getM = pop.getClass().getMethod("get", int.class);
            double sumDist = 0, sumHeading = 0;
            int carrying = 0, nearNest = 0, carryingNearNest = 0;
            double totalFood = 0;
            StringBuilder sample = new StringBuilder();
            java.lang.reflect.Method directVar = gama.api.kernel.agent.IAgent.class
                    .getMethod("getDirectVarValue", gama.api.runtime.scope.IScope.class, String.class);
            Object gridPop = null;
            try { gridPop = sim.getClass().getMethod("getPopulationFor", String.class).invoke(sim, "ant_grid"); } catch (Exception e) {}
            for (int i = 0; i < size && i < 400; i++) {
                Object obj = getM.invoke(pop, i);
                if (!(obj instanceof gama.api.kernel.agent.IAgent a) || a.dead()) continue;
                gama.api.types.geometry.IPoint loc = a.getLocation();
                Object heading = null, hasFood = null, state = null;
                try { heading = directVar.invoke(a, scope, "heading"); } catch (Exception e) {}
                try { hasFood = directVar.invoke(a, scope, "has_food"); } catch (Exception e) {}
                try { state = directVar.invoke(a, scope, "state"); } catch (Exception e) {}
                if (loc != null) {
                    double d = Math.hypot(loc.getX() - 50, loc.getY() - 50);
                    sumDist += d;
                    if (Boolean.TRUE.equals(hasFood) && d < 4) carryingNearNest++;
                    if (d < 4) nearNest++;
                    if (heading instanceof Number n) sumHeading += n.doubleValue();
                }
                if (Boolean.TRUE.equals(hasFood)) carrying++;
                if (i < 6) sample.append(String.format("(%s,%s)h=%s/s=%s; ",
                        loc == null ? "?" : String.format("%.0f", loc.getX()),
                        loc == null ? "?" : String.format("%.0f", loc.getY()),
                        heading, state));
            }
            if (gridPop != null) {
                java.lang.reflect.Method gsizeM = gridPop.getClass().getMethod("size");
                java.lang.reflect.Method ggetM = gridPop.getClass().getMethod("get", int.class);
                int gs = (int) gsizeM.invoke(gridPop);
                for (int j = 0; j < gs; j++) {
                    Object cell = ggetM.invoke(gridPop, j);
                    if (cell instanceof gama.api.kernel.agent.IAgent c && !c.dead()) {
                        Object food = directVar.invoke(c, scope, "food");
                        if (food instanceof Number n) totalFood += n.doubleValue();
                    }
                }
            }
            Log.i(TAG, String.format("DIAG cycle=%d ants=%d carrying=%d nearNest=%d carryingNearNest=%d totalFood=%.0f avgDistTo50=%.2f avgHeading=%.0f %s",
                    cycle, size, carrying, nearNest, carryingNearNest, totalFood,
                    size > 0 ? sumDist / size : 0,
                    size > 0 ? sumHeading / size : 0, sample));
            StringBuilder tracked = new StringBuilder();
            java.lang.reflect.Method dvar = gama.api.kernel.agent.IAgent.class
                    .getMethod("getDirectVarValue", gama.api.runtime.scope.IScope.class, String.class);
            for (int idx : new int[]{0, 1, 2}) {
                if (idx >= size) continue;
                Object a = getM.invoke(pop, idx);
                if (a instanceof gama.api.kernel.agent.IAgent ag && !ag.dead()) {
                    gama.api.types.geometry.IPoint l = ag.getLocation();
                    Object h = null, st = null, hf = null;
                    try { h = dvar.invoke(ag, scope, "heading"); } catch (Exception e) {}
                    try { st = dvar.invoke(ag, scope, "state"); } catch (Exception e) {}
                    try { hf = dvar.invoke(ag, scope, "has_food"); } catch (Exception e) {}
                    tracked.append(String.format("A%d=(%s,%s)h=%s s=%s f=%s; ", idx,
                            l == null ? "?" : String.format("%.1f", l.getX()),
                            l == null ? "?" : String.format("%.1f", l.getY()), h, st, hf));
                }
            }
            Object fg = null, fp = null;
            try { fg = sim.getClass().getMethod("getDirectVarValue", gama.api.runtime.scope.IScope.class, String.class)
                    .invoke(sim, scope, "food_gathered"); } catch (Exception e) {}
            try { fp = sim.getClass().getMethod("getDirectVarValue", gama.api.runtime.scope.IScope.class, String.class)
                    .invoke(sim, scope, "food_placed"); } catch (Exception e) {}
            Log.i(TAG, "DIAG tracked cycle=" + cycle + " food_gathered=" + fg + " food_placed=" + fp + " " + tracked);
        } catch (Throwable t) {
            Log.i(TAG, "DIAG error: " + t);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object findMicroPopulationReflect(Object macro, String speciesName, int depth) throws Exception {
        if (depth > 6) return null;
        Object pop = macro.getClass().getMethod("getPopulation").invoke(macro);
        if (pop == null) return null;
        java.lang.reflect.Method sizeM = pop.getClass().getMethod("size");
        java.lang.reflect.Method getM = pop.getClass().getMethod("get", int.class);
        java.lang.reflect.Method getPopFor = gama.api.kernel.agent.IAgent.class.getMethod("getPopulationFor", String.class);
        int size = (int) sizeM.invoke(pop);
        for (int i = 0; i < size; i++) {
            Object ag = getM.invoke(pop, i);
            if (!(ag instanceof gama.api.kernel.agent.IMacroAgent macro2)) continue;
            try {
                Object mp = getPopFor.invoke(macro2, speciesName);
                if (mp != null && (int) sizeM.invoke(mp) > 0) return mp;
            } catch (Exception e) {}
            Object deeper = findMicroPopulationReflect(macro2, speciesName, depth + 1);
            if (deeper != null) return deeper;
        }
        return null;
    }

    private void cacheReflectionFields(Object controller) {
        try {
            Class<?> ctrlClass = controller.getClass();
            Class<?> absClass = ctrlClass.getSuperclass();
            try { aliveField = absClass.getDeclaredField("experimentAlive"); aliveField.setAccessible(true); } catch (Exception e) {}
            try { scopeField = absClass.getDeclaredField("scope"); scopeField.setAccessible(true); } catch (Exception e) {}
            try { getClockMethod = Class.forName("gama.api.runtime.scope.IScope").getMethod("getClock"); } catch (Exception e) {}
            try { getCycleMethod = Class.forName("gama.core.simulation.SimulationClock").getMethod("getCycle"); } catch (Exception e) {}
        } catch (Exception e) { Log.e(TAG, "Cache fields error", e); }
    }

    private void logDiagnostics(Object expPlan) {
        try {
            Object model = expPlan.getClass().getMethod("getModel").invoke(expPlan);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> speciesMap =
                    (java.util.Map<String, Object>) model.getClass().getMethod("getAllSpecies").invoke(model);
            java.lang.reflect.Method modelGetSpecies = model.getClass().getMethod("getSpecies", String.class);
            for (java.util.Map.Entry<String, Object> e : speciesMap.entrySet()) {
                Object spAll = e.getValue();
                Object spNamed = modelGetSpecies.invoke(model, e.getKey());
                java.lang.reflect.Method getVar = spAll.getClass().getMethod("getVar", String.class);
                java.lang.reflect.Method visitAll = spAll.getClass().getMethod("getDescription").invoke(spAll)
                        .getClass().getMethod("visitAllAttributes",
                                Class.forName("gama.api.compilation.descriptions.IDescription$DescriptionVisitor"));
                java.util.Set<String> names = new java.util.LinkedHashSet<>();
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[] { Class.forName(
                                "gama.api.compilation.descriptions.IDescription$DescriptionVisitor") },
                        (proxy, m, args) -> {
                            Object attr = args[0];
                            names.add((String) attr.getClass().getMethod("getName").invoke(attr));
                            try {
                                for (Object dep : (java.util.Collection<?>) attr.getClass()
                                        .getMethod("getDependencies", java.util.Set.class, boolean.class,
                                                boolean.class)
                                        .invoke(attr, Class.forName(
                                                "gama.api.compilation.descriptions.IVariableDescription")
                                                .getField("INIT_DEPENDENCIES_FACETS").get(null), false, true)) {
                                    if (dep != null) { names.add((String) dep.getClass().getMethod("getName")
                                            .invoke(dep)); }
                                }
                            } catch (Exception ignored) {}
                            return true;
                        });
                java.util.List<String> missingAll = new java.util.ArrayList<>();
                java.util.List<String> missingNamed = new java.util.ArrayList<>();
                for (String n : names) {
                    if (getVar.invoke(spAll, n) == null) { missingAll.add(n); }
                    if (getVar.invoke(spNamed, n) == null) { missingNamed.add(n); }
                }
                Log.i(TAG, "DIAG species=" + e.getKey() + " sameInstance=" + (spAll == spNamed)
                        + " missingAll=" + missingAll + " missingNamed=" + missingNamed
                        + " spNamed=" + spNamed);
            }
        } catch (Exception ex) {
            Log.w(TAG, "DIAG error", ex);
        }
    }

    private void setSimulationSpeedMs(Object controller, long ms) throws Exception {
        Class<?> ctrlInterface = Class.forName("gama.api.kernel.simulation.IExperimentController");
        Object experiment = ctrlInterface.getMethod("getExperiment").invoke(controller);
        Object agent = Class.forName("gama.api.kernel.species.IExperimentSpecies").getMethod("getAgent").invoke(experiment);
        Class<?> agentClass = Class.forName("gama.api.kernel.simulation.IExperimentAgent");
        agentClass.getMethod("setMinimumDuration", Double.class).invoke(agent, ms / 1000.0);
    }

    private volatile boolean displayOutputsCached = false;

    private void updateDisplays() {
        try {
            Class<?> guiHandlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
            Object guiHandler = guiHandlerClass.getMethod("getInstance").invoke(null);
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> outputsMap =
                    (java.util.Map<String, Object>) guiHandlerClass.getMethod("getDisplayOutputs").invoke(guiHandler);

            if (outputsMap == null || outputsMap.isEmpty()) {
                Log.w(TAG, "updateDisplays: no outputs, probing...");
                guiHandlerClass.getMethod("probeAndCreateSurface").invoke(null);
                return;
            }

            Log.i(TAG, "updateDisplays: " + outputsMap.size() + " output(s)");
            boolean hasSurface = false;
            for (Object ldoObj : outputsMap.values()) {
                try {
                    Object surfObj = ldoObj.getClass().getMethod("getSurface").invoke(ldoObj);
                    if (surfObj instanceof View surfView) {
                        Log.i(TAG, "updateDisplays: invalidating " + surfView.getClass().getSimpleName());
                        surfView.post(surfView::invalidate);
                        hasSurface = true;
                    } else {
                        Log.w(TAG, "updateDisplays: surface is not a View: " + (surfObj != null ? surfObj.getClass().getSimpleName() : "null"));
                    }
                } catch (Exception de) {
                    Log.w(TAG, "updateDisplays: getSurface error: " + de.getMessage());
                }
            }
            if (!hasSurface) {
                Log.w(TAG, "updateDisplays: no valid surface found, probing...");
                guiHandlerClass.getMethod("probeAndCreateSurface").invoke(null);
            }
        } catch (Exception e) {
            Log.w(TAG, "updateDisplays error: " + e.getMessage());
            displayOutputsCached = false;
        }
    }

    private static void setGuiActivity(Activity activity) {
        try {
            Class<?> handlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
            handlerClass.getMethod("setActivity", Activity.class).invoke(null, activity);
        } catch (Throwable e) { Log.w(TAG, "Set activity error", e); }
    }

    public void log(String message) {
        Log.i(TAG, message);
        handler.post(() -> {
            logView.append(message + "\n");
            logScroll.fullScroll(ScrollView.FOCUS_DOWN);
        });
    }

    /** Switch the visible panel to the console tab so engine writes/errors are seen. */
    public void showConsoleView() {
        runOnUiThread(() -> showPanel(1));
    }

    private int dp(int dp) { return (int) (dp * getResources().getDisplayMetrics().density); }

    public FrameLayout getDisplayContainer() { return displayContainer; }

    public void updateCycleInfo(long cycle, long elapsedMs) {
        long seconds = elapsedMs / 1000;
        long min = seconds / 60;
        long sec = seconds % 60;
        handler.post(() -> cycleText.setText(cycle + " cycles  " +
                String.format("%02d:%02d", min, sec)));
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        destroyed = true;
        if (sensorBridge != null) sensorBridge.stop();
        if (statePollRunnable != null) handler.removeCallbacks(statePollRunnable);
        if (clockUpdateRunnable != null) handler.removeCallbacks(clockUpdateRunnable);
        // stopSimulation() must run while isRunning is still true: its first
        // statement is `if (!isRunning) return;`. Setting isRunning=false here
        // (before the close() below) silently skipped the whole disposal and
        // leaked every experiment (controller -> plan -> model -> sim ->
        // displays) on each launch.
        if (currentController != null) stopSimulation();
        try {
            if (originalOut != null) System.setOut(originalOut);
            if (originalErr != null) System.setErr(originalErr);
        } catch (Exception e) { Log.w(TAG, "Restore stream error", e); }
        originalOut = null;
        originalErr = null;
        try {
            Class<?> guiHandlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
            Object guiHandler = guiHandlerClass.getMethod("getInstance").invoke(null);
            guiHandlerClass.getMethod("clearDisplayState", Activity.class).invoke(guiHandler, this);
        } catch (Exception e) {}
        setGuiActivity(null);
        isRunning = false;
    }

    private Drawable createBackIcon() {
        int size = dp(32);
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(dp(4));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        int cx = size / 2, cy = size / 2;
        int pad = dp(8);
        canvas.drawLine(cx + pad, cy - pad, cx - pad, cy, paint);
        canvas.drawLine(cx - pad, cy, cx + pad, cy + pad, paint);
        return new BitmapDrawable(getResources(), bmp);
    }
}
