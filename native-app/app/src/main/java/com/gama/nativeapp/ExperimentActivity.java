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
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.PopupMenu;

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

    // Background executor for heavy per-cycle work (dumpAntState, updateDisplays).
    // Keeps the main thread responsive with multi-thread / large-population models.
    private static final java.util.concurrent.ExecutorService POLL_EXECUTOR =
            java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "state-poll-bg");
                t.setDaemon(true);
                return t;
            });

    // UI components
    private MaterialToolbar toolbar;
    private TextView toolbarTitle;
    private LinearLayout toolbarContent;
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
    private FrameLayout contentFrame;

    // Transport control bar (play/pause, step, stop) replacing the floating FABs
    private LinearLayout transportBar;
    private ImageView playPauseBtn;
    private ImageView stepBtn;
    private ImageView stopBtn;
    private ImageView reloadBtn;
    private int playBtnColor, stepBtnColor, stopBtnColor, reloadBtnColor;

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
    // Combined row holding tabLayout and transportBar on the same line
    private LinearLayout tabTransportRow;
    // In landscape the transport bar is moved to the left edge of the display
    // column and the remaining display chrome sits in this inner column.
    private LinearLayout displayContent;
    private LinearLayout displayToolbar;
    private View transportSpacer;
    private TextView transportHint;

    // Drag handle for resizing (portrait only)
    private View dragHandle;
    private LinearLayout bottomPanel;
    private LinearLayout.LayoutParams bottomPanelLp;
    private boolean isLandscape = false;

    // Collapsible side/bottom panel (tabs + panel). Collapsed by default so the
    // display fills the screen; the toolbar hamburger menu toggles it in both
    // orientations.
    private boolean panelsOpen = false;

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
    // True while a reload is in progress. During processReload() the controller's
    // experimentAlive flag is transiently false; the state poll must treat that as
    // "still restarting" rather than "experiment finished" or it permanently kills
    // rendering (blank display) after a reload.
    private volatile boolean reloading = false;
    // Whether the current experiment declared 'autorun:true'. Reload must preserve
    // this (a non-autorun model must stay paused after reload, not auto-run).
    private volatile boolean experimentAutoRun = false;
    private Object currentExpPlan;
    private volatile Object currentController;
    private Runnable statePollRunnable;
    private Runnable clockUpdateRunnable;
    private Runnable pendingHideLoading;
    // Dynamic content of the Params tab, rebuilt when an experiment opens
    private LinearLayout paramList;
    private final List<Runnable> paramRefreshers = new ArrayList<>();

    // Floating simulation-speed + display-tools control overlaid on top of the
    // display. Holds themed text views so the bar can re-skin on theme toggle,
    // and includes a collapse toggle so the user can hide the bar to view the sim.
    private View speedOverlay;
    private LinearLayout speedOverlayContent;
    private TextView speedBarHandle;
    private Slider speedOverlaySlider;
    private TextView speedLabel;
    private boolean speedBarCollapsed = false;
    private java.util.List<TextView> speedToolButtons = new java.util.ArrayList<>();

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
        tabLayout.addTab(tabLayout.newTab().setText("Display"));
        tabLayout.addTab(tabLayout.newTab().setText("Console"));
        tabLayout.addTab(tabLayout.newTab().setText("Params"));
        tabLayout.setTabGravity(TabLayout.GRAVITY_FILL);
        tabLayout.setTabMode(TabLayout.MODE_SCROLLABLE);
        tabLayout.setElevation(dp(4));

        contentArea = new FrameLayout(this);

        buildTransportBar();

        // Combined row: tabLayout on the left, transport buttons on the right
        tabTransportRow = new LinearLayout(this);
        tabTransportRow.setOrientation(LinearLayout.HORIZONTAL);
        tabTransportRow.setGravity(Gravity.CENTER_VERTICAL);
        tabTransportRow.setBackgroundColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
        tabTransportRow.addView(tabLayout, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        tabTransportRow.addView(transportBar, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

        buildDisplayArea();
        buildConsolePanel();
        buildParamsPanel();

        contentFrame = new FrameLayout(this);
        contentFrame.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
        contentFrame.addView(contentArea, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        FrameLayout.LayoutParams speedLp = new FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT,
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        speedLp.setMargins(dp(12), dp(8), dp(12), 0);
        contentFrame.addView(buildSpeedOverlay(), speedLp);
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
                fullscreenBtn.setTextColor(thc(0xFF333333, 0xFFE6E6E6));
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
        this.toolbarContent = toolbarContent;

        TextView menuBtn = new TextView(this);
        menuBtn.setText("\u2630");
        menuBtn.setTextSize(20);
        menuBtn.setTextColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
        menuBtn.setGravity(Gravity.CENTER);
        menuBtn.setPadding(dp(10), dp(4), dp(10), dp(4));
        menuBtn.setMinWidth(0);
        menuBtn.setMinHeight(0);
        menuBtn.setOnClickListener(this::showMenu);

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
        toolbarTitle.setSingleLine(true);
        toolbarTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
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

        toolbarContent.addView(menuBtn);

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

        playBtnColor = thc(0xFF006847, 0xFF2E7D32);
        playPauseBtn = makeTransportButton(R.drawable.ic_play, "Play/Pause",
                playBtnColor, v -> togglePlayPause());
        stepBtnColor = thc(0xFFFF8F00, 0xFFE65100);
        stepBtn = makeTransportButton(R.drawable.ic_step, "Step",
                stepBtnColor, v -> stepSimulation());
        stopBtnColor = thc(0xFFE53935, 0xFFCF6679);
        stopBtn = makeTransportButton(R.drawable.ic_stop, "Stop",
                stopBtnColor, v -> stopSimulation());
        reloadBtnColor = thc(0xFF1976D2, 0xFF64B5F6);
        reloadBtn = makeTransportButton(R.drawable.ic_reload, "Reload (re-run init with current parameters)",
                reloadBtnColor, v -> reloadSimulation());
        transportBar.addView(playPauseBtn);
        transportBar.addView(stepBtn);
        transportBar.addView(reloadBtn);
        transportBar.addView(stopBtn);

        View spacer = new View(this);
        spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 0, 1f));
        spacer.setVisibility(View.GONE);
        transportBar.addView(spacer);
        transportSpacer = spacer;

        TextView hint = new TextView(this);
        hint.setText("1 finger: pan  |  2 fingers: rotate  |  pinch: zoom");
        hint.setTextSize(10);
        hint.setTextColor(thc(0xFF888888, 0xFF777777));
        hint.setPadding(dp(4), 0, dp(4), 0);
        hint.setVisibility(View.GONE);
        transportBar.addView(hint);
        transportHint = hint;
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

        displayTabScroll = new HorizontalScrollView(this);
        displayTabScroll.setHorizontalScrollBarEnabled(false);
        displayTabScroll.setBackgroundColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
        displayTabScroll.setVisibility(View.GONE);
        displayTabBar = new LinearLayout(this);
        displayTabBar.setOrientation(LinearLayout.HORIZONTAL);
        displayTabBar.setPadding(dp(8), dp(4), dp(8), dp(4));
        displayTabScroll.addView(displayTabBar);
        displayColumn.addView(displayTabScroll, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        displayContainer = new FrameLayout(this);
        displayContainer.setBackgroundColor(thc(0xFFE8E8E8, 0xFF2D2D2D));
        displayContainer.setLayoutParams(new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        displayColumn.addView(displayContainer, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

        LinearLayout displayToolbar = new LinearLayout(this);
        displayToolbar.setOrientation(LinearLayout.HORIZONTAL);
        displayToolbar.setGravity(Gravity.CENTER_VERTICAL);
        displayToolbar.setPadding(dp(8), 0, dp(8), 0);
        displayToolbar.setBackgroundColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
        displayToolbar.setVisibility(View.GONE);
        this.displayToolbar = displayToolbar;

        // Display tools (Zoom+/Zoom-/Fit/Fullscreen) now live in the floating
        // consolidated control bar (buildSpeedOverlay); this legacy toolbar stays
        // empty to avoid duplicate controls.
        displayToolbar.setTag("displayToolbar");
        displayColumn.addView(displayToolbar, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

        dragHandle = new View(this);
        dragHandle.setBackgroundColor(thc(0xFFDDDDDD, 0xFF424242));
        dragHandle.setLayoutParams(new LinearLayout.LayoutParams(MATCH_PARENT, dp(4)));
        setupDragHandle();

        bottomPanel = new LinearLayout(this);
        bottomPanel.setOrientation(LinearLayout.VERTICAL);
        bottomPanelLp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f);
    }

    /** Arranges the shared views for the current orientation. Portrait stacks the
     *  display above the bottom panel (with a drag handle); landscape uses an
     *  IDE-style layout: the green toolbar stays full-width at the top with the
     *  transport controls (play/step/stop) on its right, and the display surface
     *  sits next to a side column holding the tabs and the bottom panel so it can
     *  use the whole height. */
    private void applyOrientation() {
        contentArea.removeAllViews();

        if (isLandscape) {
            // Left rail: toolbar (back/title/cycles/theme) above vertical transport
            // bar, giving the display maximum width.
            displayColumn.removeAllViews();
            displayColumn.setOrientation(LinearLayout.HORIZONTAL);

            LinearLayout displayHeader = new LinearLayout(this);
            displayHeader.setOrientation(LinearLayout.HORIZONTAL);
            displayHeader.setGravity(Gravity.CENTER_VERTICAL);
            displayHeader.setBackgroundColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
            displayHeader.addView(displayTabScroll, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
            displayHeader.addView(displayToolbar, new LinearLayout.LayoutParams(WRAP_CONTENT, dp(44)));

            displayContent = new LinearLayout(this);
            displayContent.setOrientation(LinearLayout.VERTICAL);
            displayContent.addView(displayHeader, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            View displayDivider = new View(this);
            displayDivider.setBackgroundColor(thc(0xFFE0E0E0, 0xFF333333));
            displayContent.addView(displayDivider, new LinearLayout.LayoutParams(MATCH_PARENT, dp(1)));
            displayContent.addView(displayContainer, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
            displayColumn.addView(displayContent, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f));

            // Root becomes horizontal: left rail + content
            while (rootLayout.getChildCount() > 0) rootLayout.removeViewAt(0);
            rootLayout.setOrientation(LinearLayout.HORIZONTAL);

            LinearLayout leftRail = new LinearLayout(this);
            leftRail.setOrientation(LinearLayout.VERTICAL);
            leftRail.setBackgroundColor(ContextCompat.getColor(this, R.color.toolbar_background));
            detachFromParent(toolbar);
            leftRail.addView(toolbar, new LinearLayout.LayoutParams(dp(180), WRAP_CONTENT));
            // Move transport bar out of tabTransportRow into the left rail vertically
            detachFromParent(transportBar);
            reorientTransportBar(true, false);
            leftRail.addView(transportBar, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            rootLayout.addView(leftRail, new LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT));
            rootLayout.addView(contentFrame, new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f));

            mainRow = new LinearLayout(this);
            mainRow.setOrientation(LinearLayout.HORIZONTAL);
            mainRow.setBackgroundColor(thc(0xFFF5F5F5, thc(0xFF2D2D2D, thc(0xFFE0E0E0, 0xFF424242))));

            displayColumnLp = new LinearLayout.LayoutParams(0, MATCH_PARENT, 2f);
            detachFromParent(displayColumn);
            mainRow.addView(displayColumn, displayColumnLp);

            rightCol = new LinearLayout(this);
            rightCol.setOrientation(LinearLayout.VERTICAL);
            rightCol.setBackgroundColor(thc(0xFFFAFAFA, 0xFF121212));
            LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, MATCH_PARENT, 1f);
            mainRow.addView(rightCol, rightLp);

            // Tabs go in rightCol (transport is in the left rail now)
            detachFromParent(tabLayout);
            rightCol.addView(tabLayout, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            bottomPanelLp = new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f);
            detachFromParent(bottomPanel);
            rightCol.addView(bottomPanel, bottomPanelLp);

            contentArea.addView(mainRow, new FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        } else {
            // Restore the portrait structure: toolbar + tab+transport row on top,
            // then the display chrome below.
            while (rootLayout.getChildCount() > 0) rootLayout.removeViewAt(0);
            rootLayout.setOrientation(LinearLayout.VERTICAL);
            detachFromParent(toolbar);
            detachFromParent(tabTransportRow);
            // Reattach transport bar into tabTransportRow if it was moved to the left rail
            if (transportBar.getParent() != tabTransportRow) {
                detachFromParent(transportBar);
                tabTransportRow.addView(transportBar, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
            }
            // Detach tabLayout from rightCol if needed and put it back in tabTransportRow
            if (tabLayout.getParent() != tabTransportRow) {
                detachFromParent(tabLayout);
                tabTransportRow.addView(tabLayout, 0, new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
            }
            rootLayout.addView(toolbar, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            rootLayout.addView(tabTransportRow, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            rootLayout.addView(contentFrame, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));

            displayColumn.removeAllViews();
            displayColumn.setOrientation(LinearLayout.VERTICAL);
            if (displayContent != null) displayContent.removeAllViews();
            detachFromParent(displayTabScroll);
            detachFromParent(displayToolbar);
            detachFromParent(displayContainer);
            reorientTransportBar(false, false);
            displayColumn.addView(displayTabScroll, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            displayColumn.addView(displayContainer, new LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f));
            displayColumn.addView(displayToolbar, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));

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
        }

        // Restore the panel visibility state for the currently selected tab
        int pos = tabLayout.getSelectedTabPosition();
        if (pos < 0) pos = 0;
        showPanel(pos);
        displayColumn.requestLayout();
    }

    /** Styles the transport bar for its current host. In portrait it is embedded
     *  in the tabTransportRow (horizontal); in landscape it is a vertical rail
     *  in the left sidebar. */
    private void reorientTransportBar(boolean vertical, boolean inToolbar) {
        transportBar.setOrientation(vertical ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        transportBar.setGravity(vertical ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        if (vertical) {
            transportBar.setBackgroundColor(Color.TRANSPARENT);
            transportBar.setPadding(dp(4), dp(8), dp(4), dp(8));
        } else {
            transportBar.setBackgroundColor(Color.TRANSPARENT);
            transportBar.setPadding(dp(4), dp(2), dp(4), dp(2));
        }
        int marginV = vertical ? 12 : 0;
        styleTransportButton(playPauseBtn, playBtnColor, true, marginV);
        styleTransportButton(stepBtn, stepBtnColor, true, marginV);
        styleTransportButton(reloadBtn, reloadBtnColor, true, marginV);
        styleTransportButton(stopBtn, stopBtnColor, true, marginV);
        if (transportSpacer != null) transportSpacer.setVisibility(View.GONE);
        if (transportHint != null) transportHint.setVisibility(View.GONE);
    }

    /** Applies the light/dark circle style to one transport button. Light circles
     *  (used on the green toolbar) carry a tinted icon; colored circles (the
     *  portrait strip) carry a white icon. */
    private void styleTransportButton(ImageView b, int color, boolean light, int marginV) {
        if (b == null) return;
        android.graphics.drawable.GradientDrawable bg =
                new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        if (light) {
            bg.setColor(thc(0xFFFFFFFF, 0xFF37474F));
            DrawableCompat.setTint(b.getDrawable(), color);
        } else {
            bg.setColor(color);
            DrawableCompat.setTint(b.getDrawable(), Color.WHITE);
        }
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) b.getLayoutParams();
        if (lp != null) {
            lp.setMargins(dp(4), dp(marginV), dp(4), dp(marginV));
            b.setLayoutParams(lp);
        }
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

        LinearLayout paramListLayout = new LinearLayout(this);
        paramListLayout.setOrientation(LinearLayout.VERTICAL);
        paramListLayout.setPadding(dp(16), dp(8), dp(16), dp(8));
        paramScroll.addView(paramListLayout);
        paramList = paramListLayout;

        paramsPanel.addView(paramScroll, new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
        bottomPanel.addView(paramsPanel, new LinearLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT));
    }

    /** Builds the floating consolidated control bar overlaid on top of the display.
     *  One bar holds the speed slider and the display tools (zoom in/out, fit,
     *  fullscreen) so they are always one tap away in portrait, landscape and
     *  fullscreen. Returns a themed, elevated card. */
    private View buildSpeedOverlay() {
        MaterialCardView card = new MaterialCardView(this);
        card.setRadius(dp(14));
        card.setCardElevation(dp(8));
        card.setContentPadding(dp(12), dp(6), dp(10), dp(6));
        card.setCardBackgroundColor(thc(0xF7FFFFFF, 0xF2262735));
        card.setStrokeColor(thc(0x22000000, 0x55FFFFFF));
        card.setStrokeWidth(dp(1));

        LinearLayout contentRow = new LinearLayout(this);
        contentRow.setOrientation(LinearLayout.HORIZONTAL);
        contentRow.setGravity(Gravity.CENTER_VERTICAL);

         // --- Speed section ---
        TextView label = new TextView(this);
        label.setText("Speed");
        label.setTextSize(13);
        label.setTextColor(thc(0xFF333333, 0xFFE6E6E6));
        label.setTypeface(null, Typeface.BOLD);
        label.setLayoutParams(new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));
        speedLabel = label;
        contentRow.addView(label);

        Slider slider = new Slider(this);
        slider.setValueFrom(1);
        slider.setValueTo(500);
        slider.setValue(50);
        slider.setStepSize(1);
        slider.setTrackHeight(dp(6));
        slider.setThumbRadius(dp(10));
        slider.setContentDescription("Simulation speed");
        slider.setLabelFormatter(value -> ((int) value) + " ms");
        slider.setThumbTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary)));
        slider.setTrackActiveTintList(android.content.res.ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary)));
        slider.setMinimumWidth(dp(120));
        LinearLayout.LayoutParams sliderLp = new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f);
        sliderLp.setMargins(dp(8), 0, dp(8), 0);
        slider.setLayoutParams(sliderLp);

        slider.addOnChangeListener((s, val, fromUser) -> {
            int ms = (int) val;
            if (currentController != null) {
                try {
                    setSimulationSpeedMs(currentController, ms);
                } catch (Exception e) {
                    Log.w(TAG, "Set speed error", e);
                }
            }
        });

        contentRow.addView(slider, sliderLp);
        View divider = new View(this);
        divider.setBackgroundColor(thc(0x22000000, 0x55FFFFFF));
        LinearLayout.LayoutParams dividerLp = new LinearLayout.LayoutParams(dp(1), dp(24));
        dividerLp.setMargins(dp(6), 0, dp(6), 0);
        divider.setLayoutParams(dividerLp);
        contentRow.addView(divider);

        // --- Display tools: Zoom+, Zoom-, Fit, Fullscreen ---
        String[][] tools = {
            {"Zoom+", "+"}, {"Zoom-", "\u2212"}, {"Fit", "\u2195"}, {"Fullscreen", "\u26F6"}
        };
        for (String[] tool : tools) {
            TextView btn = new TextView(this);
            btn.setText(tool[0]);
            btn.setTextColor(thc(0xFF333333, 0xFFE6E6E6));
            btn.setTextSize(11);
            btn.setGravity(Gravity.CENTER);
            btn.setPadding(dp(8), dp(6), dp(8), dp(6));
            btn.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_pill));
            btn.setOnClickListener(v -> handleDisplayAction(tool[1]));
            if ("\u26F6".equals(tool[1])) fullscreenBtn = btn;
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
            btnLp.setMargins(dp(3), 0, dp(3), 0);
            speedToolButtons.add(btn);
            contentRow.addView(btn, btnLp);
        }

        // --- Hide toolbar button: collapses the whole bar away. ---
        TextView hideBtn = new TextView(this);
        hideBtn.setText("\u2715"); // ✕
        hideBtn.setTextColor(thc(0xFF333333, 0xFFE6E6E6));
        hideBtn.setTextSize(13);
        hideBtn.setGravity(Gravity.CENTER);
        hideBtn.setPadding(dp(6), dp(4), dp(6), dp(4));
        hideBtn.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_pill));
        hideBtn.setContentDescription("Hide control bar");
        hideBtn.setOnClickListener(v -> setSpeedBarCollapsed(true));
        LinearLayout.LayoutParams hideLp = new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT);
        hideLp.setMargins(dp(4), 0, 0, 0);
        speedToolButtons.add(hideBtn);
        contentRow.addView(hideBtn, hideLp);

        speedOverlayContent = contentRow;

        // --- Collapse handle: the slim pill left visible when the bar is
        //     collapsed, so the user can always tap it to bring the bar back. ---
        TextView handle = new TextView(this);
        handle.setText("\u2630"); // ☰
        handle.setTextSize(16);
        handle.setTypeface(null, Typeface.BOLD);
        handle.setGravity(Gravity.CENTER);
        handle.setPadding(dp(18), dp(6), dp(18), dp(6));
        handle.setBackground(ContextCompat.getDrawable(this, R.drawable.bg_pill));
        handle.setContentDescription("Show control bar");
        handle.setOnClickListener(v -> setSpeedBarCollapsed(false));
        handle.setVisibility(View.GONE);
        speedBarHandle = handle;

        LinearLayout stack = new LinearLayout(this);
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.addView(contentRow);
        stack.addView(handle);
        card.addView(stack);

        speedOverlaySlider = slider;
        speedOverlay = card;
        card.setVisibility(View.VISIBLE);
        return card;
    }

    /** Collapses/expands the floating control bar. When collapsed, only a slim
     *  handle pill remains visible (tap it to restore); the full bar — speed
     *  slider and display tools — is hidden so the sim is unobstructed. */
    private void setSpeedBarCollapsed(boolean collapsed) {
        speedBarCollapsed = collapsed;
        if (speedOverlayContent != null) {
            speedOverlayContent.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        }
        if (speedBarHandle != null) {
            speedBarHandle.setVisibility(collapsed ? View.VISIBLE : View.GONE);
        }
    }

    /** Convenience toggle for the menu. */
    private void toggleSpeedOverlay() {
        setSpeedBarCollapsed(!speedBarCollapsed);
    }

    private void showPanel(int position) {
        boolean displayTab = position == 0;
        if (isLandscape) {
            // Collapsible side column: the display fills the whole width on the
            // Display tab unless the user opened the panels, and always shares
            // the row with a panel tab (Console/Layers/Params) so it stays usable.
            boolean openPanel = !displayTab || panelsOpen;
            if (rightCol != null) rightCol.setVisibility(openPanel ? View.VISIBLE : View.GONE);
            bottomPanel.setVisibility(displayTab ? View.GONE : View.VISIBLE);
            if (displayColumnLp != null) displayColumnLp.weight = displayTab ? (openPanel ? 3f : 1f) : 2f;
        } else {
            // Portrait: the panels follow the selected tab unless the user hid them
            // via the hamburger menu (panelsOpen), which lets the display fill up.
            // The tab strip (Display/Console/Layers/Params) is part of the panel
            // chrome, so it collapses with the panels too.
            if (tabLayout != null) tabLayout.setVisibility(panelsOpen ? View.GONE : View.VISIBLE);
            boolean showBottom = !panelsOpen && !displayTab;
            dragHandle.setVisibility(showBottom ? View.VISIBLE : View.GONE);
            bottomPanel.setVisibility(showBottom ? View.VISIBLE : View.GONE);
            if (displayColumnLp != null) displayColumnLp.weight = displayTab ? 1f : 3f;
        }
        if (bottomPanelLp != null) bottomPanelLp.weight = 1f;
        consolePanel.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
        paramsPanel.setVisibility(position == 2 ? View.VISIBLE : View.GONE);
        displayColumn.setVisibility(View.VISIBLE);

        displayColumn.requestLayout();
    }

    /** Toggles the panels (side column in landscape, bottom panel in portrait).
     *  Works from the toolbar hamburger menu in both orientations. */
    private void togglePanels() {
        panelsOpen = !panelsOpen;
        showPanel(tabLayout.getSelectedTabPosition());
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

    // Shown in the display container while a model initializes (heavy init like
    // shapefile triangulation can take minutes on a phone) so the blank period
    // reads as "loading" instead of "broken".
    private FrameLayout loadingOverlay;

    private void showLoading(String message) {
        handler.post(() -> {
            if (destroyed || displayContainer == null) return;
            if (loadingOverlay != null && loadingOverlay.getParent() == displayContainer) {
                updateLoadingText(message);
                return;
            }
            displayContainer.removeAllViews();
            loadingOverlay = new FrameLayout(this);
            loadingOverlay.setBackgroundColor(thc(0xFFF5F5F5, 0xFF1E1E2E));

            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER);
            FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                    WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER);
            box.setLayoutParams(blp);

            ProgressBar pb = new ProgressBar(this);
            box.addView(pb, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

            TextView tv = new TextView(this);
            tv.setId(R.id.loading_text);
            tv.setText(message);
            tv.setTextSize(14);
            tv.setPadding(dp(24), dp(12), dp(24), 0);
            tv.setGravity(Gravity.CENTER);
            tv.setTextColor(thc(0xFF333333, 0xFFE0E0E0));
            box.addView(tv, new LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT));

            loadingOverlay.addView(box);
            displayContainer.addView(loadingOverlay, new FrameLayout.LayoutParams(
                    MATCH_PARENT, MATCH_PARENT));
        });
    }

    private void updateLoadingText(String message) {
        if (loadingOverlay == null) return;
        View v = loadingOverlay.findViewById(R.id.loading_text);
        if (v instanceof TextView tv) tv.setText(message);
    }

    private void hideLoading() {
        handler.post(() -> {
            if (loadingOverlay != null && loadingOverlay.getParent() instanceof ViewGroup vp) {
                vp.removeView(loadingOverlay);
            }
            loadingOverlay = null;
        });
    }

    public void onDisplayRegistered(String displayName, AndroidDisplaySurface surface) {
        if (pendingHideLoading != null) {
            handler.removeCallbacks(pendingHideLoading);
            pendingHideLoading = null;
        }
        hideLoading();
        if (activeDisplayName == null) {
            activeDisplayName = displayName;
            surface.setVisibility(View.VISIBLE);
        } else {
            // A reload replaces the surface for an already-active display; that
            // replacement must stay visible (it's the display being shown). Only
            // secondary displays -- registered while another display is active --
            // start hidden. Otherwise a reloaded display is set GONE and never
            // lays out or draws -> blank screen.
            boolean isActive = activeDisplayName.equals(displayName);
            surface.setVisibility(isActive ? View.VISIBLE : View.GONE);
        }

        // A reload re-registers the same display name (old surface torn down, fresh
        // one built). Don't accumulate a duplicate tab: reuse the existing one.
        MaterialButton tab = null;
        for (int i = 0; i < displayTabBar.getChildCount(); i++) {
            View child = displayTabBar.getChildAt(i);
            if (child instanceof MaterialButton && child.getTag() != null
                    && child.getTag().equals(displayName)) {
                tab = (MaterialButton) child;
                break;
            }
        }
        if (tab == null) {
            tab = new MaterialButton(this);
            tab.setText(displayName);
            tab.setTag(displayName);
            tab.setTextSize(11);
            tab.setTypeface(null, Typeface.BOLD);
            tab.setCornerRadius(dp(16));
            tab.setPadding(dp(12), dp(4), dp(12), dp(4));
            tab.setMinimumHeight(0);
            tab.setMinimumWidth(0);
            LinearLayout.LayoutParams tabLp = new LinearLayout.LayoutParams(WRAP_CONTENT, dp(32));
            tabLp.setMargins(dp(4), dp(2), dp(4), dp(2));
            tab.setLayoutParams(tabLp);
            tab.setOnClickListener(v -> selectDisplay(displayName));
            displayTabBar.addView(tab);
        }
        boolean isActive = activeDisplayName.equals(displayName);
        tab.setBackgroundTintList(ColorStateList.valueOf(isActive ? thc(0xFF006847, 0xFF2E7D32) : thc(0xFFE0E0E0, 0xFF424242)));
        tab.setTextColor(isActive ? thc(0xFFFFFFFF, 0xFF1E1E2E) : thc(0xFF666666, 0xFF999999));

        if (displayTabBar.getChildCount() > 1) {
            displayTabScroll.setVisibility(View.VISIBLE);
        }
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

    /** Shows the toolbar hamburger menu. The same menu is available in portrait
     *  and landscape since the button lives in the shared toolbar content. */
    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, panelsOpen ? "Show panels" : "Hide panels");
        menu.getMenu().add(0, 2, 1, isDarkTheme ? "Light theme" : "Dark theme");
        menu.getMenu().add(0, 3, 2, isFullscreen ? "Exit fullscreen" : "Fullscreen");
        menu.getMenu().add(0, 5, 3, speedBarCollapsed ? "Show control bar" : "Hide control bar");
        menu.getMenu().add(0, 4, 4, "Back to models");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: togglePanels(); break;
                case 2: toggleTheme(); break;
                case 3: toggleFullscreen(); break;
                case 5: toggleSpeedOverlay(); break;
                case 4: finish(); break;
            }
            return true;
        });
        menu.show();
    }

    /** Expands the display so it fills the whole phone screen, hiding the app
     *  toolbar, tabs, bottom panel, FABs and system bars. Tapping again restores. */
    private void toggleFullscreen() {
        isFullscreen = !isFullscreen;

        toolbar.setVisibility(isFullscreen ? View.GONE : View.VISIBLE);
        tabTransportRow.setVisibility(isFullscreen ? View.GONE : View.VISIBLE);
        dragHandle.setVisibility(isFullscreen ? View.GONE : View.VISIBLE);
        bottomPanel.setVisibility(isFullscreen ? View.GONE : View.VISIBLE);
        if (isFullscreen) {
            displayTabScrollVisibility = displayTabScroll.getVisibility();
            displayTabScroll.setVisibility(View.GONE);
        } else {
            displayTabScroll.setVisibility(displayTabScrollVisibility);
        }
        if (isFullscreen) {
            savedDisplayWeight = displayColumnLp.weight;
            if (isLandscape) {
                // Landscape: the display column fills the whole row and the side
                // column is hidden; height must stay MATCH_PARENT (weight 0 in a
                // horizontal LinearLayout governs width only).
                displayColumnLp.weight = 1f;
                if (rightCol != null) rightCol.setVisibility(View.GONE);
            } else {
                displayColumnLp.height = 0;
                displayColumnLp.weight = 1f;
            }
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
        applyThemeColors();
    }

    /** Re-skin all programmatic views for the current theme without recreating
     *  the Activity (which would kill the running simulation). */
    private void applyThemeColors() {
        if (toolbar != null) toolbar.setBackgroundColor(thc(0xFF388E3C, 0xFF2E7D32));
        if (toolbarTitle != null) toolbarTitle.setTextColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
        if (cycleText != null) cycleText.setTextColor(thc(0xB3FFFFFF, 0xB3E0E0E0));
        if (transportBar != null) transportBar.setBackgroundColor(thc(0xFFEEEEEE, thc(0xFF1E1E2E, 0xFF2D2D2D)));
        if (displayColumn != null) displayColumn.setBackgroundColor(thc(0xFFF5F5F5, thc(0xFF2D2D2D, 0xFF37474F)));
        if (displayTabScroll != null) displayTabScroll.setBackgroundColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
        if (displayContainer != null) displayContainer.setBackgroundColor(thc(0xFFE8E8E8, 0xFF2D2D2D));
        if (displayToolbar != null) displayToolbar.setBackgroundColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
        if (dragHandle != null) dragHandle.setBackgroundColor(thc(0xFFDDDDDD, 0xFF424242));
        if (rootLayout != null) rootLayout.setBackgroundColor(thc(0xFF388E3C, 0xFF2E7D32));
        if (contentArea != null) contentArea.setBackgroundColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
        if (tabLayout != null) {
            tabLayout.setBackgroundColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
            tabLayout.setTabTextColors(thc(0xFF888888, 0xFF999999), ContextCompat.getColor(this, R.color.primary));
        }
        if (tabTransportRow != null) tabTransportRow.setBackgroundColor(thc(0xFF388E3C, 0xFF2E7D32));
        if (rightCol != null) rightCol.setBackgroundColor(thc(0xFF388E3C, 0xFF2E7D32));
        if (bottomPanel != null) bottomPanel.setBackgroundColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
        if (consolePanel != null) consolePanel.setBackgroundColor(thc(0xFF1E1E1E, thc(0xFF0D0D0D, 0xFF000000)));
        if (layersPanel != null) layersPanel.setBackgroundColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
        if (paramsPanel != null) paramsPanel.setBackgroundColor(thc(0xFFFFFFFF, 0xFF1E1E2E));
        if (logView != null) logView.setBackgroundColor(thc(0xFF0D0D0D, 0xFF000000));
        // Floating control bar (theme-aware so text stays readable in both themes).
        if (speedOverlay instanceof MaterialCardView) {
            ((MaterialCardView) speedOverlay).setCardBackgroundColor(thc(0xF7FFFFFF, 0xF2262735));
            ((MaterialCardView) speedOverlay).setStrokeColor(thc(0x22000000, 0x55FFFFFF));
        }
        if (speedLabel != null) speedLabel.setTextColor(thc(0xFF333333, 0xFFE6E6E6));
        if (fullscreenBtn != null) {
            fullscreenBtn.setTextColor(isFullscreen
                    ? thc(0xFF006847, 0xFF81C784)
                    : thc(0xFF333333, 0xFFE6E6E6));
        }
        for (TextView b : speedToolButtons) {
            if (b != fullscreenBtn) b.setTextColor(thc(0xFF333333, 0xFFE6E6E6));
        }
    }

    // ---- Simulation Controls ----

    private void togglePlayPause() {
        if (!isRunning || currentController == null) return;
        final Object ctrl = currentController;
        final boolean wasPaused = isPaused;
        // Update UI optimistically (instant feedback).
        isPaused = !wasPaused;
        setTransportIcon(playPauseBtn, wasPaused ? R.drawable.ic_pause : R.drawable.ic_play);
        stepBtn.setAlpha(wasPaused ? 0.45f : 1f);
        log(wasPaused ? "Resumed" : "Paused");
        // processPause/processStart block on the engine's internal lock;
        // run off the main thread so the UI stays responsive.
        POLL_EXECUTOR.execute(() -> {
            try {
                Class<?> ctrlInterface = Class.forName("gama.api.kernel.simulation.IExperimentController");
                if (wasPaused) {
                    ctrlInterface.getMethod("processStart", boolean.class).invoke(ctrl, true);
                } else {
                    ctrlInterface.getMethod("processPause", boolean.class).invoke(ctrl, true);
                }
            } catch (Exception e) {
                Log.w(TAG, "Toggle pause error", e);
                // Revert UI on failure.
                isPaused = wasPaused;
                handler.post(() -> setTransportIcon(playPauseBtn, wasPaused ? R.drawable.ic_play : R.drawable.ic_pause));
            }
        });
    }

    /** Runs exactly one simulation cycle while staying paused, using the
     *  controller's synchronous step command (the old code just un-paused and
     *  let the simulation run on, so stepping never advanced a single cycle). */
    private void stepSimulation() {
        if (!isRunning || !isPaused || currentController == null) return;
        final Object ctrl = currentController;
        POLL_EXECUTOR.execute(() -> {
            try {
                Class<?> ctrlInterface = Class.forName("gama.api.kernel.simulation.IExperimentController");
                ctrlInterface.getMethod("processStep", int.class, boolean.class)
                        .invoke(ctrl, 1, true);
            } catch (Exception e) { Log.w(TAG, "Step error", e); }
        });
    }

    /**
     * Reloads the experiment, re-running init with the current parameter values,
     * mirroring desktop GAMA's Reload button (controller.processReload). The app
     * already wrote slider/editor values into the experiment agent via
     * ParamsPanelBuilder.applyValue, so reload re-seeds globals from those values.
     * Runs asynchronously on the controller's command thread. Because a reload
     * re-creates the simulation/outputs in place, the activity, its params panel
     * and the display surface are left intact -- no need to go back to the model.
     */
    private void reloadSimulation() {
        if (!isRunning || currentController == null) return;
        final Object ctrl = currentController;
        log("Reloading experiment with current parameter values…");
        // processReload() disposes the old agent then re-opens one. During that
        // window the controller's experimentAlive flag is transiently false, which
        // the state poll would otherwise read as "finished" and permanently stop the
        // polling/rendering (blank display). Flag the reload so the poll treats the
        // brief alive==false as "restarting" and keeps running until the new sim is
        // back up.
        reloading = true;
        POLL_EXECUTOR.execute(() -> {
            try {
                Log.i(TAG, "reload: invoking processReload");
                Class<?> ctrlInterface = Class.forName("gama.api.kernel.simulation.IExperimentController");
                Object ok = ctrlInterface.getMethod("processReload", boolean.class).invoke(ctrl, false);
                Log.i(TAG, "reload: processReload returned " + ok);
            } catch (Exception e) { Log.w(TAG, "Reload error", e); }
            // processReload() re-creates the simulation but leaves the controller's
            // scheduler pause lock held, so the new sim never advances and never
            // drives display updates -> blank display. Release the lock exactly like
            // the initial startup does -- but ONLY for models that originally
            // declared 'autorun:true'. A paused-start model must stay paused after
            // reload (matching desktop GAMA), so don't release the lock for those.
            try {
                Class<?> absControllerClass = Class.forName("gama.api.kernel.simulation.DefaultExperimentController").getSuperclass();
                java.lang.reflect.Field pField = absControllerClass.getDeclaredField("paused");
                pField.setAccessible(true);
                java.lang.reflect.Field lField = absControllerClass.getDeclaredField("lock");
                lField.setAccessible(true);
                Object lock = lField.get(ctrl);
                if (experimentAutoRun) {
                    pField.setBoolean(ctrl, false);
                    lock.getClass().getMethod("release").invoke(lock);
                    isPaused = false;
                    Log.i(TAG, "reload: released scheduler pause lock (autorun)");
                } else {
                    // Keep the freshly reloaded (paused) model paused: just reflect
                    // that state in the UI. The pause signal is already in place
                    // because reload preserves the paused flag.
                    isPaused = true;
                    Log.i(TAG, "reload: keeping reloaded model paused (non-autorun)");
                }
            } catch (Exception pe) { Log.w(TAG, "reload: lock release failed: " + pe.getMessage()); }
            // A reload re-creates the simulation but REUSES the display surfaces
            // (and their LayeredDisplayOutputs). Force each registered surface to
            // re-bind to the new simulation/scope so it doesn't keep drawing the
            // disposed one (which shows a blank display). This is a safety net in
            // case the engine doesn't invoke outputReloaded() on our surfaces.
            handler.post(() -> {
                if (destroyed) return;
                try {
                    java.util.Map<String, com.gama.nativeapp.display.AndroidDisplaySurface> surfaces =
                            com.gama.nativeapp.gui.AndroidGuiHandler.getInstance().getDisplaySurfaces();
                    Log.i(TAG, "reload: poking " + surfaces.size() + " surfaces");
                    for (com.gama.nativeapp.display.AndroidDisplaySurface s : surfaces.values()) {
                        s.outputReloaded();
                    }
                } catch (Exception ex) { Log.w(TAG, "Reload surface refresh error", ex); }
            });
        });
    }

    private void setTransportIcon(ImageView btn, int res) {
        btn.setImageResource(res);
        android.graphics.drawable.Drawable d = btn.getDrawable();
        if (d == null) return;
        // The play/pause button sits on a light circle in light mode and a dark
        // circle in dark mode (see styleTransportButton) — tint accordingly so
        // the icon never disappears against its own background.
        DrawableCompat.setTint(d, thc(0xFF006847, 0xFFFFFFFF));
    }

    private void stopSimulation() {
        if (!isRunning) return;
        isRunning = false;
        isPaused = false;
        if (statePollRunnable != null) handler.removeCallbacks(statePollRunnable);
        if (clockUpdateRunnable != null) handler.removeCallbacks(clockUpdateRunnable);
        handler.post(() -> {
            paramRefreshers.clear();
            if (paramList != null) paramList.removeAllViews();
        });
        // Capture controller reference while still valid; close() can block for
        // a long time so run the heavy disposal on a background thread to avoid
        // freezing the UI (especially noticeable with heavy multi-thread models).
        final Object ctrl = currentController;
        currentController = null;
        if (ctrl != null) {
            new Thread(() -> {
                try {
                    Class<?> ctrlInterface = Class.forName("gama.api.kernel.simulation.IExperimentController");
                    ctrlInterface.getMethod("processStart", boolean.class).invoke(ctrl, false);
                    ctrlInterface.getMethod("close").invoke(ctrl);
                    Class<?> gamaClass = Class.forName("gama.api.GAMA");
                    java.lang.reflect.Field controllersField = gamaClass.getDeclaredField("controllers");
                    controllersField.setAccessible(true);
                    java.util.List controllers = (java.util.List) controllersField.get(null);
                    boolean removed = controllers.remove(ctrl);
                } catch (Exception e) { Log.w(TAG, "Stop error", e); }
            }, "experiment-close").start();
        }
        // Clean up all display and simulation resources held by this activity
        try {
            Class<?> guiHandlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
            Object guiHandler = guiHandlerClass.getMethod("getInstance").invoke(null);
            guiHandlerClass.getMethod("clearDisplayState", Activity.class).invoke(guiHandler, this);
        } catch (Exception e) { Log.w(TAG, "ClearDisplayState error", e); }
        setGuiActivity(null);
        // Return to editor
        finish();
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

                // 1) Extract the model's own project tree
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
                // 2) Extract data files referenced by the model but living OUTSIDE its
                //    project tree (e.g. "../../Data Data Importation/includes/x.shp").
                extractReferencedJarFiles(jarFile, cacheJar, cacheDir,
                        parentPath, new String(java.nio.file.Files.readAllBytes(
                                java.nio.file.Paths.get(new File(cacheDir, jarEntryPath).getAbsolutePath())),
                                java.nio.charset.StandardCharsets.UTF_8));
                // Do NOT markExtracted here: only the full library extraction
                // (ModelNavigatorActivity) marks completion, otherwise a partial
                // extraction could cause the full one to be skipped.
                jarFile.close();

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

    /**
     * Scans the GAML source for quoted string literals that look like relative
     * file paths and extracts the matching JAR entries next to the model, even
     * when they resolve OUTSIDE the model's project tree (e.g.
     * "../../Data/Data Importation/includes/test.shp"). Cross-project imports
     * ("../../Predator Prey/models/Model 13.gaml") are resolved by remapping
     * their first path segment onto the JAR's layout, and the imported model's
     * whole source project is extracted alongside so its own includes resolve.
     */
    private void extractReferencedJarFiles(JarFile jarFile, File cacheJar, File cacheDir,
                                           String parentPath, String gamlSource) {
        try {
            // Index entries by their path minus the first segment: the library JAR
            // flattens desktop top-level projects differently than GAML expects.
            java.util.Map<String, JarEntry> byRest = new java.util.HashMap<>();
            java.util.Enumeration<? extends JarEntry> all = jarFile.entries();
            while (all.hasMoreElements()) {
                JarEntry e = all.nextElement();
                String n = e.getName();
                int slash = n.indexOf('/');
                if (slash > 0 && !e.isDirectory()) byRest.putIfAbsent(n.substring(slash + 1), e);
            }

            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"([^\n\"]+)\"|'([^'\n]+)'")
                    .matcher(gamlSource);
            java.util.Set<String> done = new java.util.HashSet<>();
            while (m.find()) {
                String lit = m.group(1) != null ? m.group(1) : m.group(2);
                if (lit == null || lit.isEmpty()) continue;
                if (lit.startsWith("#") || lit.startsWith("http")) continue;
                if (!lit.contains("/") && !lit.contains(".")) continue;
                String norm = normalizeJarPath(parentPath, lit);
                if (norm.isEmpty() || !done.add(norm)) continue;

                // Imported models: prefer LINKING to the already-extracted library
                // copy (no duplication); fall back to extracting from the JAR only
                // if the file is not on disk anywhere.
                if (norm.endsWith(".gaml") && linkExistingImport(cacheDir, norm)) {
                    continue;
                }

                JarEntry entry = resolveJarEntry(jarFile, byRest, norm);
                if (entry == null) continue;
                extractJarEntryTo(jarFile, cacheJar, cacheDir, entry, norm);

                // Imported model not found on disk: pull its whole source project
                // tree from the JAR so ITS includes/images resolve relative to it.
                if (norm.endsWith(".gaml")) {
                    String jarPath = entry.getName();
                    int s1 = jarPath.indexOf('/');
                    int s2 = s1 >= 0 ? jarPath.indexOf('/', s1 + 1) : -1;
                    if (s2 > 0) {
                        String projectPrefix = jarPath.substring(0, s2 + 1);
                        String key = norm.substring(norm.indexOf('/') + 1);
                        String destPrefix = norm.substring(0, norm.length() - key.length());
                        log("Import support: extracting project tree for " + key);
                        java.util.Enumeration<? extends JarEntry> proj = jarFile.entries();
                        while (proj.hasMoreElements()) {
                            JarEntry pe = proj.nextElement();
                            String pn = pe.getName();
                            if (pe.isDirectory() || !pn.startsWith(projectPrefix)) continue;
                            String relInProject = pn.substring(pn.indexOf('/') + 1);
                            if (relInProject.isEmpty()) continue;
                            extractJarEntryTo(jarFile, cacheJar, cacheDir, pe,
                                    destPrefix + relInProject);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log("Includes: " + e.getMessage());
        }
    }

    /**
     * If an imported model already exists in the extracted library under a
     * different top-level folder (e.g. "tutorials/Predator Prey" vs the
     * "recipes/Predator Prey" the GAML import resolves to), symlinks that
     * existing project directory into place instead of duplicating files.
     */
    private boolean linkExistingImport(File cacheDir, String norm) {
        try {
            File dest = new File(cacheDir, norm);
            if (dest.exists()) return true;
            int s1 = norm.indexOf('/');
            int s2 = norm.indexOf('/', s1 + 1);
            if (s2 < 0) return false;
            String destPrefix = norm.substring(0, s1);        // e.g. "recipes"
            String projectName = norm.substring(s1 + 1, s2);  // e.g. "Predator Prey"
            String remainder = norm.substring(s1 + 1);        // "Predator Prey/models/X.gaml"

            File srcProject = findProjectDirWith(cacheDir, destPrefix, projectName, remainder);
            if (srcProject == null) return false;

            File linkParent = new File(cacheDir, destPrefix);
            linkParent.mkdirs();
            File link = new File(linkParent, projectName);
            if (link.exists()) return true;
            android.system.Os.symlink(srcProject.getAbsolutePath(), link.getAbsolutePath());
            log("Import support: linked " + destPrefix + "/" + projectName
                    + " -> " + srcProject.getPath().replace(cacheDir.getPath() + "/", ""));
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "linkExistingImport failed", t);
            return false;
        }
    }

    /** Finds a directory named projectName (outside excludePrefix) containing the expected file. */
    private static File findProjectDirWith(File cacheDir, String excludePrefix,
                                           String projectName, String remainder) {
        final File[] result = {null};
        try {
            java.nio.file.Files.walkFileTree(cacheDir.toPath(),
                    new java.nio.file.SimpleFileVisitor<java.nio.file.Path>() {
                        @Override public java.nio.file.FileVisitResult visitFile(
                                java.nio.file.Path file,
                                java.nio.file.attribute.BasicFileAttributes attrs) {
                            String rel = cacheDir.toPath().relativize(file).toString();
                            if (rel.equals(remainder) || rel.endsWith("/" + remainder)) {
                                if (!rel.startsWith(excludePrefix + "/")) {
                                    // Project dir = path up to and including projectName
                                    int idx = rel.lastIndexOf(projectName + "/");
                                    if (idx >= 0) {
                                        result[0] = cacheDir.toPath()
                                                .resolve(rel.substring(0, idx + projectName.length()))
                                                .toFile();
                                        return java.nio.file.FileVisitResult.TERMINATE;
                                    }
                                }
                            }
                            return java.nio.file.FileVisitResult.CONTINUE;
                        }
                    });
        } catch (Throwable t) {
            Log.w(TAG, "findProjectDirWith failed", t);
        }
        return result[0];
    }

    /** Exact match, then models/-prefixed, then first-segment-remapped match.
     *  byRest keys are JAR paths minus their first segment (e.g.
     *  "Predator Prey/models/X.gaml" from "tutorials/Predator Prey/..."), so the
     *  fallback strips the FIRST segment of the normalized reference. */
    private static JarEntry resolveJarEntry(JarFile jarFile,
                                            java.util.Map<String, JarEntry> byRest, String norm) {
        for (String candidate : new String[]{norm, "models/" + norm, "Models/" + norm}) {
            JarEntry e2 = jarFile.getJarEntry(candidate);
            if (e2 != null && !e2.isDirectory()) return e2;
        }
        int slash = norm.indexOf('/');
        if (slash > 0) {
            JarEntry e3 = byRest.get(norm.substring(slash + 1));
            if (e3 != null) return e3;
        }
        return null;
    }

    /** Extracts one entry unless a fresh copy already exists at destDir/destRel. */
    private void extractJarEntryTo(JarFile jarFile, File cacheJar, File cacheDir,
                                   JarEntry entry, String destRel) throws java.io.IOException {
        File outFile = new File(cacheDir, destRel);
        if (outFile.exists() && outFile.lastModified() > cacheJar.lastModified()) return;
        outFile.getParentFile().mkdirs();
        try (InputStream is = jarFile.getInputStream(entry);
             FileOutputStream fos = new FileOutputStream(outFile)) {
            byte[] buf = new byte[4096]; int n;
            while ((n = is.read(buf)) > 0) fos.write(buf, 0, n);
        }
    }

    /** Resolves "dir/" + "../x/y.ext" into a clean JAR-relative path. */
    private static String normalizeJarPath(String baseDir, String rel) {
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        for (String part : (baseDir + rel).split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) { if (!stack.isEmpty()) stack.pollLast(); }
            else stack.addLast(part);
        }
        return String.join("/", stack);
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

    /**
     * Called by AndroidGuiHandler.openSimulationPerspective() when the GAMA engine
     * requests an experiment after compilation.  Stores the name so showExperiments
     * can apply the autorun check.  If compilation is already done, triggers the
     * picker immediately.
     */
    public void autoStartExperiment(String experimentId) {
        log("Engine requested experiment: " + experimentId + " (ignored, picker will show)");
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
        hideLoading();
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
        showLoading("Initializing \"" + expName + "\"…\n(heavy models can take a while)");

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

                setupStdoutRedirect();

                // Respect the GAML 'autorun' facet: experiments declared without
                // autorun:true must open PAUSED and wait for the user to press Play.
                boolean autoRun = false;
                try {
                    Object ar = expClass.getMethod("isAutorun").invoke(expPlan);
                    autoRun = Boolean.TRUE.equals(ar);
                } catch (Exception e) {
                    Log.w(TAG, "isAutorun check failed", e);
                }
                log("Experiment '" + expName + "' autorun=" + autoRun);
                experimentAutoRun = autoRun;

                // Batch experiments drive many simulations internally and have no
                // display; their progress is reported through status messages.
                boolean isBatch = false;
                try {
                    isBatch = (Boolean) expClass.getMethod("isBatch").invoke(expPlan);
                    if (isBatch) {
                        handler.post(() -> toolbarTitle.setText("⚙ " + expName + " (batch)"));
                        log("Batch experiment — open the Console tab to follow runs; "
                                + "Stop ends the exploration.");
                    }
                } catch (Throwable ignored) {}

                ctrlInterface.getMethod("processStart", boolean.class).invoke(controller, true);

                // For batch or no-display experiments the loading overlay would
                // stay forever because onDisplayRegistered never fires.  Hide it
                // immediately for batch; for others schedule a delayed fallback
                // that is cancelled if/when a display does register.
                if (isBatch) {
                    hideLoading();
                } else {
                    pendingHideLoading = () -> {
                        pendingHideLoading = null;
                        hideLoading();
                    };
                    handler.postDelayed(pendingHideLoading, 5_000);
                }

                Class<?> absControllerClass = Class.forName("gama.api.kernel.simulation.DefaultExperimentController").getSuperclass();
                java.lang.reflect.Field pField = absControllerClass.getDeclaredField("paused");
                pField.setAccessible(true);
                java.lang.reflect.Field lField = absControllerClass.getDeclaredField("lock");
                lField.setAccessible(true);
                Object lock = lField.get(controller);

                if (autoRun) {
                    pField.setBoolean(controller, false);
                    lock.getClass().getMethod("release").invoke(lock);
                    handler.post(() -> {
                        setTransportIcon(playPauseBtn, R.drawable.ic_pause);
                        stepBtn.setAlpha(0.45f);
                    });
                    log("Experiment started (autorun:true)");
                } else {
                    // Pause immediately after init so the simulation waits for Play.
                    ctrlInterface.getMethod("processPause", boolean.class).invoke(controller, true);
                    isPaused = true;
                    handler.post(() -> {
                        setTransportIcon(playPauseBtn, R.drawable.ic_play);
                        stepBtn.setAlpha(1f);
                    });
                    log("Experiment loaded paused (no autorun:true) — press Play to run");
                }

                startStatePolling(controller);

                // Build the Params tab content from the engine (parameters,
                // texts, user_command buttons) once the scope is available.
                if (expPlan instanceof gama.api.kernel.species.IExperimentSpecies esp) {
                    handler.post(() -> {
                        if (destroyed || paramList == null) return;
                        paramRefreshers.clear();
                        com.gama.nativeapp.gui.ParamsPanelBuilder.populate(
                                this, paramList, esp, paramRefreshers);
                    });
                }
            } catch (Exception e) {
                Log.e(TAG, "Run error", e);
                log("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                Throwable cause = e.getCause();
                while (cause != null) {
                    log("  CAUSE: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
                    cause = cause.getCause();
                }
                if (pendingHideLoading != null) {
                    handler.removeCallbacks(pendingHideLoading);
                    pendingHideLoading = null;
                }
                hideLoading();
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
                @Override public void write(byte[] b, int off, int len) { origErr.write(b, off, len); }
            }, true));
            System.setOut(new PrintStream(new java.io.OutputStream() {
                @Override public void write(int b) { origOut.write(b); }
                @Override public void write(byte[] b, int off, int len) { origOut.write(b, off, len); }
            }, true));
        } catch (Exception ignored) { }
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
                        // A reload transiently shows alive==false while it swaps the
                        // old simulation out for the new one. Don't treat that as
                        // "experiment finished": keep polling until the new sim is up
                        // (or, if this isn't a reload, do the normal finish handling).
                        if (reloading) return; // reschedule below; keep waiting
                        handler.post(() -> {
                            toolbarTitle.setText(modelName + " (finished)");
                            cycleText.setText("Completed");
                            setTransportIcon(playPauseBtn, R.drawable.ic_play);
                            stepBtn.setAlpha(0.45f);
                        });
                        isRunning = false;
                        return;
                    }
                    // Once the reloaded simulation is alive again, clear the reload flag.
                    if (reloading) reloading = false;
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

                long elapsed = System.currentTimeMillis() - startTime;
                long min = (elapsed / 1000) / 60;
                long sec = (elapsed / 1000) % 60;

                long now = System.currentTimeMillis();
                boolean stale = now - lastInvalidate[0] > 1000;
                final int finalCycle = cycleCount;

                // Update cycle counter on the UI thread (cheap).
                handler.post(() -> {
                    String cycleStr = finalCycle >= 0 ? String.valueOf(finalCycle) : "?";
                    cycleText.setText(cycleStr + " cycles  " +
                            String.format("%02d:%02d", min, sec));
                });

                // Offload heavy per-cycle work to a background thread so the UI
                // stays responsive with large populations / multi-thread models.
                if (changed || stale) {
                    lastInvalidate[0] = System.currentTimeMillis();
                    POLL_EXECUTOR.execute(() -> {
                        if (!isRunning) return;
                        try {
                            if (changed && scopeField != null) {
                                try {
                                    Object scope = scopeField.get(controller);
                                    if (scope != null && (finalCycle % 5 == 0))
                                        dumpAntState(scope, finalCycle);
                                } catch (Exception ignored) {}
                            }
                            updateDisplays();
                        } catch (Exception e) {
                            Log.w(TAG, "Poll bg error: " + e.getMessage());
                        }
                        // Refresh read-only parameter values / labels on UI thread.
                        if (paramRefreshers != null && !paramRefreshers.isEmpty()) {
                            handler.post(() -> {
                                if (!isRunning) return;
                                for (Runnable r : new ArrayList<>(paramRefreshers)) {
                                    try { r.run(); } catch (Throwable ignored) {}
                                }
                            });
                        }
                    });
                }
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
            if (sim == null) return;
            Object pop = null;
            try { pop = sim.getClass().getMethod("getPopulationFor", String.class).invoke(sim, "ant"); } catch (Exception e) {}
            if (pop == null) {
                try { pop = findMicroPopulationReflect(sim, "ant", 0); } catch (Exception e) {}
            }
            if (pop == null) return;
            java.lang.reflect.Method sizeM = pop.getClass().getMethod("size");
            int size = (int) sizeM.invoke(pop);
            if (size == 0) return;
            java.lang.reflect.Method getM = pop.getClass().getMethod("get", int.class);
            double sumDist = 0;
            int carrying = 0, nearNest = 0;
            java.lang.reflect.Method directVar = gama.api.kernel.agent.IAgent.class
                    .getMethod("getDirectVarValue", gama.api.runtime.scope.IScope.class, String.class);
            for (int i = 0; i < size && i < 200; i++) {
                Object obj = getM.invoke(pop, i);
                if (!(obj instanceof gama.api.kernel.agent.IAgent a) || a.dead()) continue;
                gama.api.types.geometry.IPoint loc = a.getLocation();
                if (loc != null) {
                    double d = Math.hypot(loc.getX() - 50, loc.getY() - 50);
                    sumDist += d;
                    if (d < 4) nearNest++;
                }
                try {
                    Object hasFood = directVar.invoke(a, scope, "has_food");
                    if (Boolean.TRUE.equals(hasFood)) carrying++;
                } catch (Exception e) {}
            }
        } catch (Throwable t) {
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
                guiHandlerClass.getMethod("probeAndCreateSurface").invoke(null);
                return;
            }

            boolean hasSurface = false;
            for (Object ldoObj : outputsMap.values()) {
                try {
                    Object surfObj = ldoObj.getClass().getMethod("getSurface").invoke(ldoObj);
                    if (surfObj instanceof View surfView) {
                        surfView.post(surfView::invalidate);
                        hasSurface = true;
                    } else {
                        Log.w(TAG, "updateDisplays: output surface is not a View: "
                                + (surfObj == null ? "null" : surfObj.getClass().getSimpleName()));
                    }
                } catch (Exception de) { /* skip broken output */ }
            }
            Log.i(TAG, "updateDisplays: outputs=" + outputsMap.size() + " hasSurface=" + hasSurface);
            if (!hasSurface) {
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
