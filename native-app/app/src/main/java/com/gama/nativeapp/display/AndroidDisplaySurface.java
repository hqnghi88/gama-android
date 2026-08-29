package com.gama.nativeapp.display;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;

import java.awt.Font;
import java.awt.Rectangle;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import gama.api.runtime.GeneralSynchronizer;
import gama.api.ui.displays.IDisplayData;
import gama.api.ui.displays.IDisplaySurface.OpenGL;
import gama.api.ui.displays.IGraphics;
import gama.annotations.constants.IKeyword;
import gama.api.ui.layers.ILayer;
import gama.api.ui.layers.ILayerManager;
import gama.api.kernel.agent.IAgent;
import gama.api.kernel.agent.IPopulation;
import gama.api.types.geometry.GamaPointFactory;
import gama.api.types.geometry.IPoint;
import gama.api.types.geometry.IShape;
import gama.api.types.color.IColor;
import gama.api.utils.geometry.GamaEnvelopeFactory;
import gama.api.utils.geometry.IEnvelope;
import gama.core.outputs.LayeredDisplayData;
import gama.core.outputs.LayeredDisplayOutput;
import gama.core.outputs.display.LayerManager;
import gama.api.ui.layers.IEventLayerListener;
import gama.api.ui.layers.IDrawingAttributes;
import gama.core.outputs.layers.OverlayLayer;
import gama.api.GAMA;
import gama.api.ui.displays.IGraphicsScope;

public class AndroidDisplaySurface extends View implements OpenGL {

    private static final String TAG = "AndroidDisplaySurface";

    private final LayeredDisplayOutput output;
    private final ILayerManager layerManager;
    private AndroidDisplayGraphics androidGraphics;
    private IGraphicsScope scope;

    private final Rect viewPort = new Rect();
    private int displayWidth, displayHeight;
    private boolean zoomFit = true;
    private double zoomLevel = 1.0;
    // Fullscreen mode: the display fills the whole screen edge to edge. The
    // world keeps its true shape but the viewport (and the 3D cover-fit) take
    // the full surface size so no empty bands remain on tall phone screens.
    private volatile boolean fillScreen = false;
    private volatile boolean disposed = false;
    private boolean isLocked = false;
    private int frames = 0;
    private boolean rendered = false;
    // Once the initial envelope frames the world, stop re-deriving the world->pixel ratio from the
    // LIVE agent envelope every frame (the core updates selectionIn()/envelope each tick as agents
    // move, which made the view zoom/pan continuously). Capture the first valid envelope and reuse it.
    private boolean firstFitDone = false;
    // The world envelope used to place/size full-world imagery (grid image, terrain) is captured
    // ONCE and reused, instead of re-reading the LIVE sim.getEnvelope() every frame. The live
    // agent envelope grows as agents forage outward, which panned & re-zoomed the ground
    // ("recal everytime an ant is out bound"). Freeze it to the initial world framing.
    private IEnvelope frozenEnv = null;

    private PointF mousePosition = new PointF(-1, -1);
    private final Set<IEventLayerListener> listeners = new HashSet<>();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());

    /** Invalidates this view, hopping to the UI thread when called from a worker thread. */
    private void invalidateSafe() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            invalidate();
        } else {
            uiHandler.post(this::invalidate);
        }
    }

    /**
     * Toggles fullscreen fill mode. In this mode the viewport takes the full
     * surface size and the 3D scene cover-fits it, so the display fills the
     * screen edge to edge. The 3D auto-fit is reset so the new mode applies.
     */
    public void setFillScreen(boolean fill) {
        if (this.fillScreen == fill) return;
        this.fillScreen = fill;
        if (androidGraphics != null) {
            androidGraphics.setSceneCoverFit(fill);
            androidGraphics.resetSceneFit();
        }
        int w = getWidth(), h = getHeight();
        if (fill) {
            if (w > 0 && h > 0) {
                displayWidth = w;
                displayHeight = h;
                viewPort.set(0, 0, w, h);
                zoomFit = true;
                updateZoomLevel();
            }
        } else {
            // Refit on the next layout pass once the view shrinks back.
            zoomFit = true;
            requestLayout();
        }
        invalidateSafe();
    }

    private float lastTouchX, lastTouchY;
    private float lastFocalX, lastFocalY;
    private float focalX, focalY;
    private float lastTwistAngle;
    private boolean twistTracking;
    private ScaleGestureDetector scaleDetector;
    private GestureDetector tapDetector;
    private final Paint bgPaint = new Paint();
    private final Paint agentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint();
    private int framesSinceLastDraw = 0;

    private final RectF workRect = new RectF();
    private Bitmap cachedGridBitmap;
    private int cachedGridW, cachedGridH;

    private java.util.List<String> cachedSpeciesNames;
    private long lastSpeciesCacheTime = 0;
    private gama.api.kernel.agent.IMacroAgent capturedSim = null;
    private int cachedAgentCount = 0;
    private boolean scopeUpdated = false;

    public AndroidDisplaySurface(Context context, LayeredDisplayOutput output) {
        super(context);
        this.output = output;
        output.setSurface(this);
        try {
            gama.api.runtime.scope.IScope outScope = output.getScope();
            if (outScope != null) {
                setDisplayScope(outScope.copyForGraphics("in android2d display"));
            }
        } catch (Throwable t) {
            android.util.Log.w("ANDROID_DISPLAY", "Scope not available at construction, will retry in onDraw");
        }
        // Capture simulation reference at construction time before scope becomes stale
        try {
            gama.api.runtime.scope.IScope outScope = output.getScope();
            if (outScope != null) {
                this.capturedSim = outScope.getSimulation();
            }
        } catch (Throwable t) {
        }
        output.getData().addListener(this);
        this.layerManager = new LayerManager(this, output);
        this.androidGraphics = new AndroidDisplayGraphics();
        this.androidGraphics.setDisplaySurface(this);

        bgPaint.setColor(0xFFFFFFFF);
        try {
            int bg = IColor.toAWTColor(output.getData().getBackgroundColor()).getRGB();
            bgPaint.setColor(bg | 0xFF000000);
        } catch (Throwable e) {
            Log.w(TAG, "bgPaint init failed", e);
        }
        bgPaint.setStyle(Paint.Style.FILL);

        agentPaint.setColor(0xFF00FF00);
        agentPaint.setStyle(Paint.Style.FILL);
        gridPaint.setFilterBitmap(true);

        scaleDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                float factor = detector.getScaleFactor();
                if (factor > 0 && !Float.isNaN(factor) && !Float.isInfinite(factor)) {
                    zoomBy(factor, detector.getFocusX(), detector.getFocusY());
                }
                return true;
            }
        });

        tapDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDoubleTap(MotionEvent e) {
                if (output != null && output.getData().is3D()) {
                    androidGraphics.resetCamera3D();
                    zoomFit();
                }
                return true;
            }
        });

        setClickable(true);
        setFocusable(true);
        setWillNotDraw(false);
        setLayerType(LAYER_TYPE_SOFTWARE, null);
    }

    public AndroidDisplaySurface(Context context, AttributeSet attrs) {
        super(context, attrs);
        this.output = null;
        this.layerManager = null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0 && h > 0) {
            if (zoomFit) {
                zoomFit();
            }
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int parentW = MeasureSpec.getSize(widthMeasureSpec);
        int parentH = MeasureSpec.getSize(heightMeasureSpec);
        if (parentW <= 0 || parentH <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        boolean is3D = output != null && output.getData().is3D();
        if (fillScreen || is3D) {
            setMeasuredDimension(parentW, parentH);
            return;
        }
        double envW = getEnvWidth();
        double envH = getEnvHeight();
        if (envW <= 0 || envH <= 0) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        double envRatio = envW / envH;
        int measuredW, measuredH;
        if (envRatio >= (double) parentW / parentH) {
            measuredW = parentW;
            measuredH = (int) Math.round(parentW / envRatio);
        } else {
            measuredH = parentH;
            measuredW = (int) Math.round(parentH * envRatio);
        }
        setMeasuredDimension(Math.max(1, measuredW), Math.max(1, measuredH));
    }

    // Triple-buffered snapshot rendering:
    //
    //  - The GAMA output scheduler calls updateDisplay() from the SIMULATION
    //    thread at the END of every cycle. We render the layers into a work
    //    bitmap right there, so each snapshot is a post-cycle frame — never
    //    mid-reflex (this is what made the FoV cone appear unmasked while the
    //    slow masked_by computation was running).
    //  - Completed snapshots are handed to the UI via an atomic index swap; the
    //    UI thread only ever READS the presented bitmap, and the renderer never
    //    writes into it again until two swaps later — no concurrent draw/read,
    //    hence no flicker.
    private static final int FRAME_BUFFERS = 3;
    private final android.graphics.Bitmap[] frameBuffers =
            new android.graphics.Bitmap[FRAME_BUFFERS];
    private final android.graphics.Canvas[] frameCanvases =
            new android.graphics.Canvas[FRAME_BUFFERS];
    private final Object renderLock = new Object();
    private int workIndex = 0;
    private volatile int presentIndex = -1;
    private volatile long lastCycleRenderNs;

    // Cap snapshot renders (~30 fps): fast models run hundreds of cycles per
    // second and painting every cycle-end on the SIM thread would throttle the
    // simulation itself. Skipped cycles are simply not painted; each painted
    // frame stays a consistent post-cycle snapshot. 30 fps keeps motion smooth
    // enough to feel fluid while still leaving CPU headroom for the sim.
    private static final long MIN_SNAPSHOT_INTERVAL_NS = 33_333_333L;

    /** Renders layers into the next work buffer, then publishes it (any thread). */
    private void renderSnapshot() {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        synchronized (renderLock) {
            ensureBuffers(w, h);
            try {
                renderFrame(frameCanvases[workIndex]);
                presentIndex = workIndex;
                lastCycleRenderNs = System.nanoTime();
                // Next work buffer: any slot that is NOT the one being presented.
                for (int i = 0; i < FRAME_BUFFERS; i++) {
                    if (i != presentIndex && i != workIndex) { workIndex = i; break; }
                }
                if (workIndex == presentIndex) workIndex = (presentIndex + 1) % FRAME_BUFFERS;
            } catch (Throwable t) {
                android.util.Log.e("ANDROID_DISPLAY", "snapshot render failed", t);
            }
        }
    }

    private void ensureBuffers(int w, int h) {
        boolean ok = true;
        for (int i = 0; i < FRAME_BUFFERS; i++) {
            if (frameBuffers[i] == null || frameBuffers[i].getWidth() != w
                    || frameBuffers[i].getHeight() != h) { ok = false; break; }
        }
        if (ok) return;
        for (int i = 0; i < FRAME_BUFFERS; i++) {
            if (frameBuffers[i] != null) frameBuffers[i].recycle();
            frameBuffers[i] = null;
            frameCanvases[i] = null;
        }
        presentIndex = -1;
        try {
            for (int i = 0; i < FRAME_BUFFERS; i++) {
                frameBuffers[i] = android.graphics.Bitmap.createBitmap(w, h,
                        android.graphics.Bitmap.Config.ARGB_8888);
                frameCanvases[i] = new Canvas(frameBuffers[i]);
            }
        } catch (Throwable t) {
            android.util.Log.w("ANDROID_DISPLAY", "frame buffers alloc failed: " + t);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (disposed || output == null) return;

        long now = System.nanoTime();
        int p = presentIndex;
        boolean fresh = p >= 0 && frameBuffers[p] != null
                && now - lastCycleRenderNs < 300_000_000L;

        if (!fresh) {
            // Paused / interactive / first-frame: take a fresh snapshot.
            renderSnapshot();
            p = presentIndex;
        }

        if (p >= 0 && frameBuffers[p] != null) {
            canvas.drawBitmap(frameBuffers[p], 0, 0, null);
        }
    }

    /** Called by GAMA's scheduler from the SIM thread at end of every cycle:
     *  render a consistent snapshot immediately, then ask UI to present it. */
    private void onSimCycleUpdate() {
        renderSnapshot();
        uiHandler.post(this::invalidateSafe);
    }

    private void renderFrame(Canvas canvas) {
        canvas.drawColor(bgPaint.getColor());

        if (androidGraphics == null) {
            androidGraphics = new AndroidDisplayGraphics();
            androidGraphics.setDisplaySurface(this);
        }

        androidGraphics.setCanvas(canvas);
        androidGraphics.resetDrawnShapesCount();

        // Keep the display scope bound to the experiment agent, matching desktop behavior
        // (Java2DDisplaySurface uses output.getScope().copyForGraphics(...)). Overlay aspects
        // reference experiment variables, which only resolve when the scope agent is the experiment.
        // GridLayerData.compute() calls scope.getAgent().getPopulationFor(name), which on desktop
        // walks up the host chain to the simulation, so the experiment agent works there too.
        if (!scopeUpdated) {
            if (scope == null) {
                try {
                    gama.api.runtime.scope.IScope outScope = output.getScope();
                    if (outScope != null) {
                        setDisplayScope(outScope.copyForGraphics("in android2d display"));
                        scopeUpdated = true;
                    }
                } catch (Throwable t) {
                    android.util.Log.w("ANDROID_DISPLAY", "Could not init scope from output: " + t.getMessage());
                }
            }
            if (capturedSim == null) {
                try {
                    gama.api.runtime.scope.IScope outScope = output.getScope();
                    if (outScope != null) {
                        capturedSim = outScope.getSimulation();
                    }
                } catch (Throwable t) {}
            }
        }

        boolean drewShapes = false;
        canvas.save();
        canvas.translate(-viewPort.left, -viewPort.top);
        try {
            IGraphicsScope drawScope = scope;
            if (drawScope == null) {
                gama.api.runtime.scope.IScope outScope = output.getScope();
                if (outScope != null) drawScope = outScope.copyForGraphics("draw");
            }
            if (drawScope != null && !drawScope.interrupted()) {
                androidGraphics.beginFrame();
                layerManager.drawLayersOn(androidGraphics);
                drewShapes = androidGraphics.getDrawnShapesCount() > 0;
            }
        } catch (Throwable t) {
            android.util.Log.e("ANDROID_DISPLAY", "layerManager draw error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
        }
        if (!drewShapes) {
            gama.api.ui.layers.ILayer.Chart chartOnly = layerManager.getOnlyChart();
            if (chartOnly == null) {
                if (!androidGraphics.is3dMode()) {
                    drawAgentsManually(canvas);
                }
            }
        }
        canvas.restore();

        frames++;
        rendered = true;
    }

    private void drawAgentsManually(Canvas canvas) {
        try {
            IGraphicsScope drawScope = scope;
            if (drawScope == null || drawScope.interrupted()) {
                return;
            }

            gama.api.kernel.agent.IMacroAgent sim = null;
            // Priority 1: use cached simulation
            sim = capturedSim;
            // Priority 2-5: scope chain (usually fails for display copies)
            if (sim == null) {
                try { sim = drawScope.getSimulation(); } catch (Throwable t) {}
            }
            if (sim == null) {
                try {
                    gama.api.runtime.scope.IScope outScope = output.getScope();
                    if (outScope != null) sim = outScope.getSimulation();
                } catch (Throwable t) {}
            }
            if (sim == null) {
                try {
                    gama.api.runtime.scope.IScope outScope = output.getScope();
                    if (outScope != null && outScope.getRoot() != null) sim = outScope.getRoot().getSimulation();
                } catch (Throwable t) {}
            }
            if (sim == null) {
                try {
                    gama.api.runtime.scope.IScope outScope = output.getScope();
                    if (outScope != null) {
                        Object exp = outScope.getExperiment();
                        if (exp instanceof gama.api.kernel.agent.IMacroAgent macro) sim = macro.getSimulation();
                    }
                } catch (Throwable t) {}
            }
            // Priority 6: try GAMA.getSimulation() or iterate controllers
            if (sim == null) {
                try {
                    Class<?> gamaClass = Class.forName("gama.api.GAMA");
                    Object simObj = gamaClass.getMethod("getSimulation").invoke(null);
                    if (simObj instanceof gama.api.kernel.agent.IMacroAgent m) sim = m;
                } catch (Throwable t) {}
            }
            if (sim == null) {
                try {
                    Class<?> gamaClass = Class.forName("gama.api.GAMA");
                    java.lang.reflect.Field ctrlField = gamaClass.getDeclaredField("controllers");
                    ctrlField.setAccessible(true);
                    java.util.List controllers = (java.util.List) ctrlField.get(null);
                    if (controllers != null && !controllers.isEmpty()) {
                        Object ctrl = controllers.get(controllers.size() - 1);
                        java.lang.reflect.Field agentField = ctrl.getClass().getSuperclass().getDeclaredField("agent");
                        agentField.setAccessible(true);
                        Object agent = agentField.get(ctrl);
                        if (agent instanceof gama.api.kernel.agent.IMacroAgent macro) sim = macro.getSimulation();
                    }
                } catch (Throwable t) {}
            }
            // Cache for next time
            if (sim != null && capturedSim == null) {
                capturedSim = sim;
            }
            if (sim == null) {
                return;
            }

            double envW = getEnvWidth();
            double envH = getEnvHeight();
            if (envW <= 0 || envH <= 0) {
                return;
            }

            // IMPORTANT: do NOT override envW/envH with the live sim.getEnvelope() here. As agents
            // spread (e.g. ants foraging outward) the agent envelope grows every step, which would
            // recompute scale/offset below and make the whole view zoom out / re-center continuously
            // ("rebound the ant"). The envelope must stay locked to the (frozen) world bounds so the
            // camera is stable.
            double dispW = getDisplayWidth();
            double dispH = getDisplayHeight();
            double scale = Math.min(dispW / envW, dispH / envH);
            double offsetX = (dispW - envW * scale) / 2.0;
            double offsetY = (dispH - envH * scale) / 2.0;
            float radius = (float) Math.max(4, 3 * scale);

            gama.api.kernel.agent.IAgent agent = (sim instanceof gama.api.kernel.agent.IAgent) ? (sim) : null;
            if (agent == null) return;

            long now = System.currentTimeMillis();
            if (cachedSpeciesNames == null || (now - lastSpeciesCacheTime) > 2000) {
                try {
                    Object specObj = sim.getSpecies();
                    if (specObj instanceof gama.api.kernel.species.IModelSpecies model) {
                        java.util.Map<String, gama.api.kernel.species.ISpecies> allSpecies = model.getAllSpecies();
                        cachedSpeciesNames = allSpecies != null ? new java.util.ArrayList<>(allSpecies.keySet()) : null;
                    }
                } catch (Throwable t) { /* skip */ }
                lastSpeciesCacheTime = now;
            }
            if (cachedSpeciesNames == null || cachedSpeciesNames.isEmpty()) {
                return;
            }

            int totalDrawn = 0;
            Paint cellPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            Paint agentCirclePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            agentCirclePaint.setColor(0xFF000000);
            agentCirclePaint.setStyle(Paint.Style.FILL);

            java.util.List<Object[]> gridAgents = new java.util.ArrayList<>();

            for (String speciesName : cachedSpeciesNames) {
                try {
                    IPopulation<? extends gama.api.kernel.agent.IAgent> pop = agent.getPopulationFor(speciesName);
                    // For micro-species (e.g. 'ant' inside 'ants_model'), getPopulationFor returns empty.
                    // Fall back to iterating macro-agent sub-populations.
                    if (pop == null || pop.size() == 0) {
                        IPopulation microPop = tryGetMicroPopulation(sim, speciesName);
                        if (microPop != null && microPop.size() > 0) pop = microPop;
                    }
                    if (pop == null || pop.size() == 0) {
                        continue;
                    }

                    boolean isGridPop = pop instanceof gama.core.topology.grid.GridPopulation;
                    int gridCols = 0, gridRows = 0;
                    if (isGridPop) {
                        try {
                            gama.core.topology.grid.GridPopulation gp = (gama.core.topology.grid.GridPopulation) pop;
                            gridCols = gp.getNbCols();
                            gridRows = gp.getNbRows();
                        } catch (Throwable t) { isGridPop = false; }
                    }

                    if (isGridPop && gridCols > 0 && gridRows > 0) {
                        int sz = pop.size();
                        double cellW = envW / gridCols;
                        double cellH = envH / gridRows;
                        try {
                            gama.api.kernel.topology.IGrid grid = ((gama.core.topology.grid.GridPopulation) pop).getGrid();
                            int[] displayData = grid != null ? grid.getDisplayData() : null;
                            for (int cy = 0; cy < gridRows; cy++) {
                                for (int cx = 0; cx < gridCols; cx++) {
                                    int color = displayData != null ? displayData[cy * gridCols + cx] : 0xFF000000;
                                    if (color == 0) color = 0xFF000000;
                                    cellPaint.setColor(0xFF000000 | (color & 0x00FFFFFF));
                                    float left = (float) (cx * cellW * scale + offsetX);
                                    float top = (float) ((envH - (cy + 1) * cellH) * scale + offsetY);
                                    float right = (float) ((cx + 1) * cellW * scale + offsetX);
                                    float bottom = (float) ((envH - cy * cellH) * scale + offsetY);
                                    canvas.drawRect(left, top, right, bottom, cellPaint);
                                    totalDrawn++;
                                }
                            }
                        } catch (Throwable t) { /* skip grid */ }
                    } else {
                        int sz = pop.size();
                        for (int i = 0; i < sz; i++) {
                            Object obj = pop.get(i);
                            if (!(obj instanceof gama.api.kernel.agent.IAgent a) || a.dead()) continue;
                            IPoint pt = a.getLocation();
                            if (pt == null) continue;
                            float sx = (float) (pt.getX() * scale + offsetX);
                            float sy = (float) ((envH - pt.getY()) * scale + offsetY);
                            gridAgents.add(new Object[]{sx, sy});
                        }
                    }
                } catch (Throwable t) { /* skip pop */ }
            }

            for (Object[] pos : gridAgents) {
                float sx = (float) pos[0];
                float sy = (float) pos[1];
                canvas.drawCircle(sx, sy, radius, agentCirclePaint);
                totalDrawn++;
            }
        } catch (Throwable t) { /* skip draw */ }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private IPopulation tryGetMicroPopulation(
            gama.api.kernel.agent.IMacroAgent sim, String speciesName) {
        // Approach: recursively find all micro-populations matching the species name
        try {
            IPopulation simPop = sim.getPopulation();
            if (simPop == null) return null;
            return findInPopulations(simPop, speciesName, 0);
        } catch (Throwable t) {}
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private IPopulation findInPopulations(
            IPopulation pop, String speciesName, int depth) {
        if (depth > 5 || pop == null) return null;
        try {
            for (int i = 0; i < pop.size(); i++) {
                Object agent = pop.get(i);
                if (agent instanceof gama.api.kernel.agent.IAgent ag) {
                    if (ag instanceof gama.api.kernel.agent.IMacroAgent macro) {
                        try {
                            IPopulation microPop = macro.getPopulationFor(speciesName);
                            if (microPop != null && microPop.size() > 0) {
                                return microPop;
                            }
                        } catch (Throwable t) {}
                        try {
                            IPopulation agentPop = macro.getPopulation();
                            if (agentPop != null && agentPop.size() > 0) {
                                IPopulation found = findInPopulations(agentPop, speciesName, depth + 1);
                                if (found != null) return found;
                            }
                        } catch (Throwable t) {}
                        try {
                            IPopulation<? extends gama.api.kernel.agent.IAgent>[] allMicro = macro.getMicroPopulations();
                            if (allMicro != null) {
                                for (Object mp : allMicro) {
                                    if (mp instanceof IPopulation subPop) {
                                        String popName = subPop.getSpecies() != null ? subPop.getSpecies().getName() : "";
                                        if (popName.equals(speciesName) && subPop.size() > 0) return subPop;
                                        IPopulation deeper = findInPopulations(subPop, speciesName, depth + 1);
                                        if (deeper != null) return deeper;
                                    }
                                }
                            }
                        } catch (Throwable t) {}
                    }
                }
            }
        } catch (Throwable t) {}
        return null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (disposed || output == null) return false;

        scaleDetector.onTouchEvent(event);
        tapDetector.onTouchEvent(event);

        float x = event.getX();
        float y = event.getY();

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                lastTouchX = x;
                lastTouchY = y;
                downTime = System.currentTimeMillis();
                longPressFired = false;
                mousePosition.set(x, y);
                publishMouseLocation();
                scheduleLongPress();
                dispatchMouseEvent(7, (int) x, (int) y);  // mouse_enter
                dispatchMouseEvent(16, (int) x, (int) y); // mouse_down
                return true;

            case MotionEvent.ACTION_POINTER_DOWN:
                cancelLongPressCheck();
                computeFocal(event);
                lastFocalX = focalX;
                lastFocalY = focalY;
                lastTouchX = x;
                lastTouchY = y;
                if (event.getPointerCount() >= 2) {
                    lastTwistAngle = twistAngle(event);
                    twistTracking = true;
                }
                return true;

            case MotionEvent.ACTION_MOVE:
                mousePosition.set(x, y);
                publishMouseLocation();
                dispatchMouseEvent(6, (int) x, (int) y);  // mouse_move
                if (System.currentTimeMillis() - downTime > LONG_PRESS_MS
                        && movedBeyondSlop(x, y)) {
                    cancelLongPressCheck();
                    longPressFired = true; // suppress menu once dragging
                }
                dispatchMouseEvent(5, (int) x, (int) y);  // mouse_drag
                if (!isLocked) {
                    if (event.getPointerCount() > 1) {
                        computeFocal(event);
                        if (output != null && output.getData().is3D()) {
                            float dx = focalX - lastFocalX;
                            float dy = focalY - lastFocalY;
                            // Two-finger drag orbits/tilts the 3D camera from the focal
                            // movement. When the two fingers also twist (their connecting
                            // line rotates), add that twist as extra yaw so pinching-twist
                            // keeps rotating, matching the intuitive drag-to-orbit gesture.
                            float twist = 0f;
                            if (twistTracking) {
                                float angle = twistAngle(event);
                                float dAngle = angle - lastTwistAngle;
                                if (dAngle > 180f) dAngle -= 360f;
                                else if (dAngle < -180f) dAngle += 360f;
                                twist = dAngle;
                                lastTwistAngle = angle;
                            }
                            androidGraphics.rotateCamera3D(-dx * 0.3f + twist, -dy * 0.3f);
                        } else {
                            float dx = focalX - lastFocalX;
                            float dy = focalY - lastFocalY;
                            viewPort.offset(-(int) dx, -(int) dy);
                        }
                        lastFocalX = focalX;
                        lastFocalY = focalY;
                        invalidateSafe();
                    } else {
                        float dx = x - lastTouchX;
                        float dy = y - lastTouchY;
                        if (output != null && output.getData().is3D()) {
                            androidGraphics.panCamera3D(dx, dy);
                        } else {
                            viewPort.offset(-(int) dx, -(int) dy);
                        }
                        invalidateSafe();
                    }
                }
                lastTouchX = x;
                lastTouchY = y;
                return true;

            case MotionEvent.ACTION_POINTER_UP:
                int upIndex = event.getActionIndex();
                float fx = 0, fy = 0;
                int n = 0;
                for (int i = 0; i < event.getPointerCount(); i++) {
                    if (i == upIndex) continue;
                    fx += event.getX(i);
                    fy += event.getY(i);
                    n++;
                }
                focalX = n > 0 ? fx / n : x;
                focalY = n > 0 ? fy / n : y;
                lastFocalX = focalX;
                lastFocalY = focalY;
                lastTouchX = x;
                lastTouchY = y;
                twistTracking = false;
                return true;

            case MotionEvent.ACTION_UP:
                mousePosition.set(x, y);
                publishMouseLocation();
                cancelLongPressCheck();
                dispatchMouseEvent(17, (int) x, (int) y); // mouse_up
                if (!longPressFired && System.currentTimeMillis() - downTime >= LONG_PRESS_MS) {
                    dispatchMouseEvent(9, (int) x, (int) y); // mouse_menu (long press)
                }
                dispatchMouseEvent(8, (int) x, (int) y);  // mouse_exit
                return true;
        }
        return super.onTouchEvent(event);
    }

    private static final long LONG_PRESS_MS = 550;
    private long downTime;
    private boolean longPressFired;
    private final Runnable longPressCheck = new Runnable() {
        @Override public void run() {
            if (!longPressFired) {
                longPressFired = true;
                dispatchMouseEvent(9,
                        (int) mousePosition.x, (int) mousePosition.y); // mouse_menu
            }
        }
    };

    private boolean movedBeyondSlop(float x, float y) {
        float dx = x - lastTouchX, dy = y - lastTouchY;
        return dx * dx + dy * dy > 64f;
    }

    private void scheduleLongPress() {
        postDelayed(longPressCheck, LONG_PRESS_MS);
    }

    private void cancelLongPressCheck() {
        removeCallbacks(longPressCheck);
    }

    /** Publishes the pointer position (view px + model coords) for #user_location. */
    private void publishMouseLocation() {
        try {
            gama.api.types.geometry.IPoint displayPt =
                    gama.api.types.geometry.GamaPointFactory.createImmutable(
                            mousePosition.x, mousePosition.y);
            gama.api.types.geometry.IPoint modelPt = null;
            ILayer layer = primaryLayerForCoords();
            if (layer != null) {
                modelPt = layer.getModelCoordinatesFrom(
                        (int) mousePosition.x, (int) mousePosition.y, this);
            }
            com.gama.nativeapp.gui.AndroidGuiHandler.setMouseLocations(modelPt, displayPt);
        } catch (Throwable ignored) {}
    }

    /** First layer able to convert view coordinates to model coordinates. */
    private ILayer primaryLayerForCoords() {
        try {
            Object ldo = output;
            if (ldo == null) return null;
            java.util.List layers = (java.util.List) ldo.getClass().getMethod("getLayers")
                    .invoke(ldo);
            if (layers != null) {
                for (Object l : layers) {
                    if (l instanceof ILayer il && !(l instanceof OverlayLayer)) return il;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private void computeFocal(MotionEvent event) {
        float fx = 0, fy = 0;
        int n = event.getPointerCount();
        for (int i = 0; i < n; i++) {
            fx += event.getX(i);
            fy += event.getY(i);
        }
        focalX = fx / n;
        focalY = fy / n;
    }

    /** Angle (degrees) of the line between the two pointers, relative to
     *  the horizontal.  Used to detect twist/rotation gestures. */
    private float twistAngle(MotionEvent event) {
        float dx = event.getX(1) - event.getX(0);
        float dy = event.getY(1) - event.getY(0);
        return (float) Math.toDegrees(Math.atan2(dy, dx));
    }

    // -- IDisplaySurface implementation --

    @Override
    public java.awt.image.BufferedImage getImage(int width, int height) {
        int w = width > 0 ? width : super.getWidth();
        int h = height > 0 ? height : super.getHeight();
        if (w <= 0 || h <= 0) return null;
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        androidGraphics.setCanvas(c);
        androidGraphics.beginFrame();
        layerManager.drawLayersOn(androidGraphics);
        androidGraphics.setCanvas(null);

        int[] pixels = new int[w * h];
        bmp.getPixels(pixels, 0, w, 0, 0, w, h);
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, w, h, pixels, 0, w);
        bmp.recycle();
        return image;
    }

    @Override
    public void updateDisplay(boolean force, GeneralSynchronizer synchronizer) {
        try {
            if (!disposed) {
                // Called by GAMA's output scheduler on the SIM thread at the end
                // of each cycle. Rate-limit to MIN_SNAPSHOT_INTERVAL_NS so fast
                // simulations are not throttled by per-cycle painting.
                long now = System.nanoTime();
                if (force || now - lastCycleRenderNs >= MIN_SNAPSHOT_INTERVAL_NS) {
                    onSimCycleUpdate();
                }
            }
        } catch (Throwable t) {
            android.util.Log.e("ANDROID_DISPLAY", "updateDisplay failed", t);
        } finally {
            // The scheduler waits on this when the display is synchronized:true.
            if (synchronizer != null) synchronizer.release();
        }
    }

    @Override
    public void setMenuManager(Object displaySurfaceMenu) {}

    @Override
    public void zoomIn() {
        zoomBy(1.2f, getWidth() / 2f, getHeight() / 2f);
    }

    @Override
    public void zoomOut() {
        zoomBy(0.8f, getWidth() / 2f, getHeight() / 2f);
    }

    private void zoomBy(float factor, float focusX, float focusY) {
        if (isLocked || factor <= 0) return;
        int viewW = getWidth();
        int viewH = getHeight();
        if (viewW <= 0 || viewH <= 0) return;

        if (androidGraphics != null && androidGraphics.is3dMode()) {
            // 3D zoom dollies the camera so the viewport keeps filling the
            // screen and zooming out reveals more of the world.
            androidGraphics.zoomCamera3D(factor);
            invalidateSafe();
            return;
        }

        float newW = (float) getDisplayWidth() * factor;
        float newH = (float) getDisplayHeight() * factor;
        // Keep zoom bounded but allow zooming OUT below the default 1:1 fit
        // (previously the display was clamped to at least the view size, so Zoom-
        // did nothing from the initial fit).
        float minW = Math.max(20f, viewW * 0.05f);
        float minH = Math.max(20f, viewH * 0.05f);
        float maxW = viewW * 100f;
        float maxH = viewH * 100f;
        float minFactor = Math.max(minW / (float) getDisplayWidth(), minH / (float) getDisplayHeight());
        float maxFactor = Math.min(maxW / (float) getDisplayWidth(), maxH / (float) getDisplayHeight());
        factor = Math.max(minFactor, Math.min(maxFactor, factor));
        newW = (float) getDisplayWidth() * factor;
        newH = (float) getDisplayHeight() * factor;

        int newLeft = (int) (focusX * (factor - 1) + viewPort.left * factor);
        int newTop = (int) (focusY * (factor - 1) + viewPort.top * factor);
        displayWidth = Math.max(1, (int) newW);
        displayHeight = Math.max(1, (int) newH);
        viewPort.set(newLeft, newTop, newLeft + displayWidth, newTop + displayHeight);

        zoomFit = false;
        updateZoomLevel();
        invalidateSafe();
    }

    @Override
    public void zoomFit() {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        mousePosition.set(w / 2f, h / 2f);
        if (resizeImage(w, h, false)) {
            this.zoomLevel = 1.0;
            this.zoomFit = true;
            viewPort.set(0, 0, w, h);
            if (androidGraphics != null && androidGraphics.is3dMode()) {
                androidGraphics.resetCameraFit3D();
            }
            invalidateSafe();
        }
    }

    @Override
    public void toggleLock() { isLocked = !isLocked; }

    @Override
    public ILayerManager getManager() { return layerManager; }

    @Override
    public void focusOn(IShape geometry) {
        if (geometry == null) return;
        Rectangle2D r = layerManager.focusOn(geometry, this);
        if (r == null) return;
        float xScale = (float) (getWidth() / r.getWidth());
        float yScale = (float) (getHeight() / r.getHeight());
        float zf = Math.min(xScale, yScale);
        viewPort.set(0, 0, (int) (getDisplayWidth() * zf), (int) (getDisplayHeight() * zf));
        invalidateSafe();
    }

    @Override
    public void runAndUpdate(Runnable r) {
        new Thread(() -> {
            r.run();
            uiHandler.post(this::invalidate);
        }).start();
    }

    @Override
    public void outputReloaded() {
        setDisplayScope(output.getScope().copyForGraphics("in android2d display"));
        layerManager.outputChanged();
        if (zoomFit) zoomFit();
        invalidateSafe();
    }

    private IEnvelope getOrFreezeEnvelope() {
        if (frozenEnv != null) return frozenEnv;
        try {
            gama.api.runtime.scope.IScope s = output.getScope();
            if (s != null) {
                gama.api.kernel.agent.IMacroAgent sim = s.getSimulation();
                if (sim != null) {
                    IEnvelope env = sim.getEnvelope();
                    if (env != null && env.getWidth() > 0 && env.getHeight() > 0) {
                        frozenEnv = env;
                        return frozenEnv;
                    }
                }
            }
        } catch (Throwable t) {}
        return null;
    }

    @Override
    public double getEnvWidth() {
        IEnvelope env = getOrFreezeEnvelope();
        return env != null ? env.getWidth() : output.getData().getEnvWidth();
    }

    @Override
    public double getEnvHeight() {
        IEnvelope env = getOrFreezeEnvelope();
        return env != null ? env.getHeight() : output.getData().getEnvHeight();
    }

    /** The stable, frozen world envelope (for full-world imagery). Never re-reads the live agent envelope. */
    public IEnvelope getFrozenEnvelope() {
        return getOrFreezeEnvelope();
    }

    @Override
    public double getDisplayWidth() { return viewPort.width(); }

    @Override
    public double getDisplayHeight() { return viewPort.height(); }

    int getViewPortLeft() { return viewPort.left; }

    int getViewPortTop() { return viewPort.top; }

    @Override
    public Collection<IAgent> selectAgent(int x, int y) {
        int xc = x - viewPort.left;
        int yc = y - viewPort.top;
        List<IAgent> result = new ArrayList<>();
        List<ILayer> layers = layerManager.getLayersIntersecting(xc, yc);
        for (ILayer layer : layers) {
            Collection<IAgent> agents = layer.collectAgentsAt(xc, yc, this);
            if (agents != null && !agents.isEmpty()) result.addAll(agents);
        }
        return result;
    }

    @Override
    public double getZoomLevel() { return zoomLevel; }

    @Override
    public void setSize(int x, int y) {
        viewPort.set(0, 0, x, y);
    }

    @Override
    public LayeredDisplayOutput getOutput() { return output; }

    @Override
    public IDisplayData getData() { return output != null ? output.getData() : null; }

    @Override
    public void layersChanged() { invalidateSafe(); }

    @Override
    public void addListener(IEventLayerListener e) {
        listeners.add(e);
        if (e.getClass().getSimpleName().contains("Keyboard")) {
            post(() -> installKeyboardBar());
        }
    }

    /** Overlays a compact soft-key row so GAML keyboard event layers (letters,
     *  arrows, escape...) are usable on touch-only devices. */
    private void installKeyboardBar() {
        try {
            ViewGroup parent = (ViewGroup) getParent();
            if (parent == null || parent.findViewWithTag("gama_key_bar") != null) return;

            HorizontalScrollView scroll = new HorizontalScrollView(getContext());
            scroll.setTag("gama_key_bar");
            scroll.setHorizontalScrollBarEnabled(false);
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);

            android.content.Context ctx = getContext();
            int pad = (int) (6 * ctx.getResources().getDisplayMetrics().density + 0.5f);
            View.OnClickListener keyTap = v -> {
                String key = (String) v.getTag();
                if ("space".equals(key)) {
                    dispatchKeyEvent(' ');
                } else if (key.length() == 1) {
                    dispatchKeyEvent(key.charAt(0));
                } else {
                    Integer code = SPECIAL_KEYS.get(key);
                    if (code != null) dispatchSpecialKeyEvent(code);
                }
            };

            String[] keys = {"esc", "←", "→", "↑", "↓",
                    "a","b","c","d","e","f","g","h","i","j","k","l","m",
                    "n","o","p","q","r","s","t","u","v","w","x","y","z",
                    "0","1","2","3","4","5","6","7","8","9", "space"};
            for (String k : keys) {
                android.widget.Button b = new android.widget.Button(ctx, null,
                        android.R.attr.buttonBarButtonStyle);
                b.setText(k.equals("space") ? "␣" : k);
                b.setTag(k.equals("esc") ? "esc" : k);
                b.setPadding(pad * 2, pad / 2, pad * 2, pad / 2);
                b.setMinimumWidth(0);
                b.setMinimumHeight(0);
                b.setTextSize(13);
                b.setOnClickListener(keyTap);
                row.addView(b);
            }
            scroll.addView(row);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM;
            parent.addView(scroll, lp);
        } catch (Throwable t) {
            Log.w("AndroidDisplaySurface", "keyboard bar install failed", t);
        }
    }

    private static final java.util.Map<String, Integer> SPECIAL_KEYS = java.util.Map.of(
            "esc", IEventLayerListener.KEY_ESC,
            "←", IEventLayerListener.ARROW_LEFT,
            "→", IEventLayerListener.ARROW_RIGHT,
            "↑", IEventLayerListener.ARROW_UP,
            "↓", IEventLayerListener.ARROW_DOWN);

    @Override
    public void removeListener(IEventLayerListener e) { listeners.remove(e); }

    @Override
    public Collection<IEventLayerListener> getLayerListeners() { return listeners; }

    @Override
    public IEnvelope getVisibleRegionForLayer(ILayer currentLayer) {
        if (currentLayer instanceof OverlayLayer && scope != null) {
            return scope.getSimulation().getEnvelope();
        }
        IEnvelope e = GamaEnvelopeFactory.create();
        e.expandToInclude(currentLayer.getModelCoordinatesFrom(0, 0, this));
        e.expandToInclude(currentLayer.getModelCoordinatesFrom((int) getWidth(), (int) getHeight(), this));
        return e;
    }

    @Override
    public int getFPS() { int r = frames; frames = 0; return r; }

    @Override
    public boolean isDisposed() { return disposed; }

    @Override
    public void getModelCoordinatesInfo(StringBuilder receiver) {
        receiver.append("Model coordinates: ").append(mousePosition.x).append(", ").append(mousePosition.y);
    }

    @Override
    public void dispatchKeyEvent(char character) {
        for (IEventLayerListener gl : listeners) gl.keyPressed(String.valueOf(character));
    }

    @Override
    public void dispatchSpecialKeyEvent(int keyCode) {
        for (IEventLayerListener gl : listeners) gl.specialKeyPressed(keyCode);
    }

    @Override
    public void dispatchMouseEvent(int swtEventType, int x, int y) {
        for (IEventLayerListener gl : listeners) {
            switch (swtEventType) {
                case 16: gl.mouseDown(x, y, 1); break;
                case 17: gl.mouseUp(x, y, 1); break;
                case 6: gl.mouseMove(x, y); break;
                case 5: gl.mouseDrag(x, y, 1); break;
                case 7: gl.mouseEnter(x, y); break;
                case 8: gl.mouseExit(x, y); break;
                case 9: gl.mouseMenu(x, y); break;
            }
        }
    }

    @Override
    public void setMousePosition(int x, int y) { mousePosition.set(x, y); }

    @Override
    public void draggedTo(int x, int y) {
        if (!isLocked) {
            float dx = x - mousePosition.x;
            float dy = y - mousePosition.y;
            if (output != null && output.getData().is3D()) {
                androidGraphics.panCamera3D(dx, dy);
            } else {
                viewPort.offset(-(int) dx, -(int) dy);
            }
            invalidateSafe();
        }
        mousePosition.set(x, y);
    }

    @Override
    public void selectAgentsAroundMouse() {}

    @Override
    public IGraphicsScope getScope() { return scope; }

    @Override
    public boolean isVisible() { return getVisibility() == VISIBLE; }

    @Override
    public IGraphics getIGraphics() { return androidGraphics; }

    @Override
    public Rectangle getBoundsForRobotSnapshot() { return new Rectangle(getWidth(), getHeight()); }

    @Override
    public IPoint getModelCoordinates() {
        List<ILayer> layers = layerManager.getLayersIntersecting((int) mousePosition.x, (int) mousePosition.y);
        for (ILayer layer : layers) {
            if (layer.isProvidingWorldCoordinates()) {
                return layer.getModelCoordinatesFrom((int) mousePosition.x, (int) mousePosition.y, this);
            }
        }
        return GamaPointFactory.create();
    }

    @Override
    public void changed(LayeredDisplayData.Changes property, Object value) {
        if (property == LayeredDisplayData.Changes.BACKGROUND) {
            int rgb = 0xFF000000;
            if (value instanceof gama.api.types.color.IColor ic) {
                rgb = ic.getRGB();
            } else if (value instanceof java.awt.Color c) {
                rgb = c.getRGB();
            }
            bgPaint.setColor(rgb | 0xFF000000);
        }
    }

    @Override
    public IEnvelope getROIDimensions() {
        try {
            gama.api.runtime.scope.IScope s = output.getScope();
            if (s != null) {
                gama.api.kernel.agent.IMacroAgent sim = s.getSimulation();
                if (sim != null) {
                    IEnvelope env = sim.getEnvelope();
                    if (env != null) return env;
                }
            }
        } catch (Throwable t) {}
        double envW = getEnvWidth();
        double envH = getEnvHeight();
        if (envW > 0 && envH > 0) {
            return GamaEnvelopeFactory.of(0, envW, 0, envH, 0, 0);
        }
        return null;
    }

    @Override
    public void setPaused(boolean paused) {}

    @Override
    public void selectAgent(IDrawingAttributes attributes) {}

    @Override
    public void selectionIn(IEnvelope env) {
        if (env == null || disposed) return;
        final int w = getWidth();
        final int h = getHeight();
        if (w <= 0 || h <= 0) return;
        // One-time initial framing; afterwards keep the current view and just redraw.
        if (!firstFitDone) {
            boolean is3d = output != null && output.getData().is3D();
            if (is3d) {
                displayWidth = w;
                displayHeight = h;
                viewPort.set(0, 0, w, h);
                zoomFit = true;
                updateZoomLevel();
            } else if (fillScreen) {
                displayWidth = w;
                displayHeight = h;
                viewPort.set(0, 0, w, h);
                zoomFit = true;
                updateZoomLevel();
            } else {
                final double eW = env.getWidth();
                final double eH = env.getHeight();
                if (eW > 0 && eH > 0) {
                    double scale = Math.min(w / eW, h / eH);
                    int newW = Math.max(1, (int) Math.round(eW * scale));
                    int newH = Math.max(1, (int) Math.round(eH * scale));
                    int left = (int) Math.round(env.getMinX() * scale);
                    int top = (int) Math.round(env.getMinY() * scale);
                    displayWidth = newW;
                    displayHeight = newH;
                    viewPort.set(left, top, left + newW, top + newH);
                    zoomFit = false;
                    updateZoomLevel();
                }
            }
            firstFitDone = true;
        }
        invalidateSafe();
    }

    @Override
    public Font computeFont(Font f) { return f; }

    private void setDisplayScope(IGraphicsScope scope) {
        if (this.scope != null) GAMA.releaseScope(this.scope);
        this.scope = scope;
    }

    private void updateZoomLevel() {
        if (getEnvWidth() > 0 && getEnvHeight() > 0) {
            zoomLevel = Math.min(getDisplayWidth() / getEnvWidth(), getDisplayHeight() / getEnvHeight());
        }
    }

    private boolean resizeImage(int x, int y, boolean force) {
        if (!force && x == getDisplayWidth() && y == getDisplayHeight()) return true;
        if (x < 10 || y < 10) return false;

        int[] dim = computeBoundsFrom(x, y);
        displayWidth = Math.max(1, dim[0]);
        displayHeight = Math.max(1, dim[1]);
        viewPort.set(0, 0, displayWidth, displayHeight);

        if (androidGraphics == null) {
            androidGraphics = new AndroidDisplayGraphics();
            androidGraphics.setDisplaySurface(this);
        }
        return true;
    }

    private int[] computeBoundsFrom(int vwidth, int vheight) {
        if (fillScreen) return new int[]{vwidth, vheight};
        if (!layerManager.stayProportional()) return new int[]{vwidth, vheight};
        double ratio = getEnvHeight() / getEnvWidth();
        int[] dim = new int[2];
        if (ratio < 1) {
            dim[1] = Math.min(vheight, (int) Math.round(vwidth * ratio));
            dim[0] = Math.min(vwidth, (int) Math.round(dim[1] / ratio));
        } else {
            dim[0] = Math.min(vwidth, (int) Math.round(vheight / ratio));
            dim[1] = Math.min(vheight, (int) Math.round(dim[0] * ratio));
        }
        return dim;
    }

    public void dispose() {
        if (disposed) return;
        disposed = true;
        getData().removeListener(this);
        if (layerManager != null) layerManager.dispose();
        GAMA.releaseScope(getScope());
        setDisplayScope(null);
    }
}
