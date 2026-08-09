package com.gama.nativeapp.gui;

import android.app.Activity;
import android.content.Intent;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.gama.nativeapp.ExperimentActivity;
import com.gama.nativeapp.display.AndroidDisplaySurface;

import gama.api.ui.displays.IDisplaySurface;
import gama.api.ui.IConsoleListener;
import gama.api.ui.IGamaView;
import gama.api.ui.IGui;
import gama.api.kernel.species.IExperimentSpecies;
import gama.api.gaml.symbols.IParameter;
import gama.api.kernel.species.IModelSpecies;
import gama.api.ui.IOutput;
import gama.core.outputs.LayeredDisplayOutput;
import gama.api.GAMA;
import gama.api.runtime.scope.IScope;
import gama.api.exceptions.GamaRuntimeException;
import gama.api.types.color.IColor;
import gama.api.types.font.IFont;
import gama.api.types.list.IList;
import gama.api.types.map.IMap;
import gama.api.compilation.descriptions.IActionDescription;
import gama.api.utils.tests.CompoundSummary;
import gama.api.types.geometry.IPoint;
import gama.api.utils.server.ISocketCommand;
import gama.api.kernel.simulation.ISimulationAgent;
import gama.api.kernel.simulation.ITopLevelAgent;
import gama.api.ui.displays.IDisplayCreator;

public class AndroidGuiHandler implements IGui {

    private static final String TAG = "AndroidGuiHandler";
    private static Activity currentActivity;
    private static AndroidGuiHandler instance;
    private ConsoleListener consoleListener;
    private static IExperimentSpecies cachedExperimentPlan;

    /** Push a console/error line to the visible console view (when available) and optionally toast it. */
    private void deliver(String msg, boolean showConsole, boolean toast) {
        Log.i(TAG, msg);
        Activity a = currentActivity;
        String low = msg.toLowerCase();
        boolean isMemoryLow = low.contains("memory is low") || low.contains("out of memory");
        if (a instanceof ExperimentActivity) {
            ExperimentActivity exp = (ExperimentActivity) a;
            exp.log(msg);
            if (showConsole && !isMemoryLow) exp.showConsoleView();
        }
        if (toast && a != null && !isMemoryLow) {
            a.runOnUiThread(() -> Toast.makeText(a, msg, Toast.LENGTH_LONG).show());
        }
    }

    /** All registered display outputs keyed by display name */
    private final LinkedHashMap<String, LayeredDisplayOutput> displayOutputs = new LinkedHashMap<>();
    /** All created surfaces keyed by display name */
    private final LinkedHashMap<String, AndroidDisplaySurface> displaySurfaces = new LinkedHashMap<>();

    public static void setActivity(Activity activity) {
        currentActivity = activity;
    }

    public static AndroidGuiHandler getInstance() {
        if (instance == null) instance = new AndroidGuiHandler();
        return instance;
    }

    public static Activity getCurrentActivity() { return currentActivity; }

    /** Get all registered display outputs (read-only view) */
    public Map<String, LayeredDisplayOutput> getDisplayOutputs() {
        return java.util.Collections.unmodifiableMap(displayOutputs);
    }

    /** Get all created surfaces (read-only view) */
    public Map<String, AndroidDisplaySurface> getDisplaySurfaces() {
        return java.util.Collections.unmodifiableMap(displaySurfaces);
    }

    /** Clear all display state for a new experiment run */
    public void clearDisplayState(Activity activity) {
        displayOutputs.clear();
        displaySurfaces.clear();
        cachedExperimentPlan = null;
        if (activity instanceof ExperimentActivity) {
            ExperimentActivity exp = (ExperimentActivity) activity;
            FrameLayout container = exp.getDisplayContainer();
            if (container != null) {
                activity.runOnUiThread(() -> container.removeAllViews());
            }
        }
    }

    @Override
    public IDisplaySurface createDisplaySurfaceFor(IOutput.Display output, Object arg) {
        LayeredDisplayOutput ldo = (LayeredDisplayOutput) output;
        String displayName = ldo.getName();
        if (displaySurfaces.containsKey(displayName)) {
            Log.i(TAG, "Surface already exists for display: " + displayName + ", skipping");
            return displaySurfaces.get(displayName);
        }

        // Don't create surface until output has a valid scope (sim must init it first)
        if (ldo.getScope() == null) {
            // Try setting scope from controller via buildScopeFrom and init layers
            try {
                Class<?> gamaClass = Class.forName("gama.api.GAMA");
                java.lang.reflect.Field controllersField = gamaClass.getDeclaredField("controllers");
                controllersField.setAccessible(true);
                java.util.List controllers = (java.util.List) controllersField.get(null);
                if (controllers != null && !controllers.isEmpty()) {
                    Object controller = controllers.get(controllers.size() - 1);
                    java.lang.reflect.Field scopeField = controller.getClass().getSuperclass()
                        .getDeclaredField("scope");
                    scopeField.setAccessible(true);
                    IScope ctrlScope = (IScope) scopeField.get(controller);
                    if (ctrlScope != null) {
                        // Build proper scope and initialize layers to resolve species
                        Class<?> absOut = Class.forName("gama.core.outputs.AbstractOutput");
                        java.lang.reflect.Method buildScope = absOut.getDeclaredMethod("buildScopeFrom", IScope.class);
                        buildScope.setAccessible(true);
                        IScope gfxScope = (IScope) buildScope.invoke(ldo, ctrlScope);
                        ldo.setScope(gfxScope);
                        Log.i(TAG, "Set scope on " + displayName + " from controller (via buildScopeFrom)");
                        // Init layers directly to resolve species (skip full init which fails on
                        // initWith when simulation envelope is null)
                        java.lang.reflect.Method getLayers = ldo.getClass().getMethod("getLayers");
                        java.util.List layers = (java.util.List) getLayers.invoke(output);
                        if (layers != null) {
                            for (Object layer : layers) {
                                try {
                                    java.lang.reflect.Method setDisp = layer.getClass().getMethod("setDisplayOutput", IOutput.class);
                                    setDisp.invoke(layer, ldo);
                                } catch (Throwable t) {
                                    Log.w(TAG, "setDisplayOutput failed: " + t.getMessage());
                                }
                                try {
                                    java.lang.reflect.Method initLayer = layer.getClass().getMethod("init", IScope.class);
                                    initLayer.invoke(layer, gfxScope);
                                } catch (Throwable t) {
                                    Log.w(TAG, "layer.init failed: " + t.getMessage());
                                }
                            }
                            Log.i(TAG, "Layers initialized for " + displayName);
                        }
                        // Set env dimensions from simulation envelope since initWith was skipped
                        try {
                            ISimulationAgent sim = ctrlScope.getSimulation();
                            gama.api.utils.geometry.IEnvelope env = null;
                            if (sim != null) env = sim.getEnvelope();
                            if (env == null) {
                                // Fallback to a default envelope when sim envelope unavailable
                                env = gama.api.utils.geometry.GamaEnvelopeFactory.of(0, 100, 0, 100, 0, 0);
                                Log.w(TAG, "Using default env for " + displayName + " (sim=" + (sim != null ? "ok" : "null") + " env=" + (sim != null && sim.getEnvelope() != null ? "ok" : "null") + ")");
                            }
                            ldo.getData().setEnvWidth(env.getWidth());
                            ldo.getData().setEnvHeight(env.getHeight());
                            Log.i(TAG, "Set env dimensions for " + displayName + ": " + env.getWidth() + "x" + env.getHeight());
                        } catch (Throwable t) {
                            Log.w(TAG, "Env set failed: " + t.getMessage());
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Scope set+init failed for " + displayName + ": " + t.getMessage());
            }
            if (ldo.getScope() == null) {
                Log.i(TAG, "Deferring surface creation for " + displayName + " (scope not ready)");
                return null;
            }
        }

        Activity activity = currentActivity;
        if (activity == null) {
            Log.w(TAG, "No activity available for display surface creation");
            return null;
        }

        if (ldo.getData().is3D()) {
            Log.i(TAG, "3D display type preserved for: " + displayName);
        }

        final AndroidDisplaySurface[] surfaceHolder = new AndroidDisplaySurface[1];
        try {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    surfaceHolder[0] = new AndroidDisplaySurface(activity, ldo);
                    Log.i(TAG, "Created surface for display: " + displayName);

                    displayOutputs.put(displayName, ldo);
                    displaySurfaces.put(displayName, surfaceHolder[0]);

                    if (activity instanceof ExperimentActivity) {
                        ExperimentActivity expActivity = (ExperimentActivity) activity;
                        FrameLayout container = expActivity.getDisplayContainer();
                        if (container != null) {
                            FrameLayout.LayoutParams flp = new FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT);
                            flp.gravity = Gravity.CENTER;
                            container.addView(surfaceHolder[0], flp);
                            surfaceHolder[0].invalidate();
                            Log.i(TAG, "Surface added to container and invalidated: " + displayName);
                        } else {
                            Log.w(TAG, "Container is null for: " + displayName);
                        }
                        expActivity.onDisplayRegistered(displayName, surfaceHolder[0]);
                    } else {
                        Log.w(TAG, "Activity is not ExperimentActivity: " + (activity != null ? activity.getClass().getSimpleName() : "null"));
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Error creating display surface on UI thread", e);
                } finally {
                    latch.countDown();
                }
            });
            latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Log.e(TAG, "Interrupted waiting for UI thread", e);
        }

        if (surfaceHolder[0] != null) {
            setSurfaceField(ldo, surfaceHolder[0]);
        }

        return surfaceHolder[0];
    }

    @Override
    public IDisplayCreator getDisplayDescriptionFor(String name) {
        return null;
    }

    @Override
    public Map<String, ISocketCommand> getServerCommands() {
        return java.util.Collections.emptyMap();
    }

    @Override
    public IPoint getMouseLocationInModel() {
        return null;
    }

    @Override
    public IPoint getMouseLocationInDisplay() {
        return null;
    }

    @Override
    public boolean openSimulationPerspective(IModelSpecies model, String experimentId) {
        Activity activity = currentActivity;
        if (activity == null) return false;
        Intent intent = new Intent(activity, ExperimentActivity.class);
        intent.putExtra("model_name", model.getName());
        intent.putExtra("experiment_name", experimentId);
        activity.startActivity(intent);
        return true;
    }

    public void openMessageDialog(IScope scope, String error) {
        deliver("Message: " + error, false, false);
    }

    public void openErrorDialog(IScope scope, String error) {
        String msg = "Error: " + error;
        Log.e(TAG, msg);
        deliver(msg, true, true);
    }

    @Override
    public void runtimeError(IScope scope, GamaRuntimeException g) {
        String msg = "Runtime error: " + (g != null ? g.getMessage() : "unknown");
        Log.e(TAG, msg, g);
        deliver(msg, true, true);
    }

    @Override
    public void displayErrors(IScope scope, List<GamaRuntimeException> errors, boolean show) {
        if (errors == null) return;
        for (GamaRuntimeException g : errors) {
            String msg = "Runtime error: " + (g != null ? g.getMessage() : "unknown");
            deliver(msg, true, true);
        }
    }

    @Override
    public IConsoleListener getConsole() {
        if (consoleListener == null) consoleListener = new ConsoleListener();
        return consoleListener;
    }

    @Override
    public void run(String taskName, Runnable opener, boolean asynchronous) {
        if (asynchronous) {
            new Thread(opener, taskName).start();
        } else {
            opener.run();
        }
    }

    @Override
    public IGamaView showView(IScope scope, String viewId, String name, int code) {
        Log.i(TAG, "showView called: viewId=" + viewId + ", name=" + name);
        return new AndroidGamaView(name);
    }

    @Override
    public void exit() {
        Activity activity = currentActivity;
        if (activity != null) {
            activity.runOnUiThread(() -> activity.finish());
        }
    }

    @Override
    public Map<String, Object> openUserInputDialog(IScope scope, String title,
            List<IParameter> parameters, IFont font, IColor color, Boolean showTitle) {
        return java.util.Collections.emptyMap();
    }

    @Override
    public IMap<String, IMap<String, Object>> openWizard(IScope scope, String title,
            IActionDescription finish, IList<IMap<String, Object>> pages) {
        return null;
    }

    @Override
    public void displayTestsResults(IScope scope, CompoundSummary<?, ?> summary) {}

    @Override
    public void arrangeExperimentViews(IScope myScope, IExperimentSpecies experimentPlan,
            Boolean keepTabs, Boolean keepToolbars, Boolean showConsoles,
            Boolean showParameters, Boolean showNavigator, Boolean showControls,
            Boolean keepTray, Supplier<IColor> color, boolean showEditors) {
        Log.i(TAG, "[ARRANGE] called");
        cachedExperimentPlan = experimentPlan;
        Activity activity = currentActivity;

        // Only record plan outputs, don't create surfaces until sim initialized them
        recordPlanOutputs(experimentPlan);

        if (activity instanceof ExperimentActivity) {
            ExperimentActivity expActivity = (ExperimentActivity) activity;
            expActivity.runOnUiThread(() -> expActivity.updateCycleInfo(0, 0));
        }
    }

    /** Record plan outputs by name (no surface creation - sim outputs will have proper scopes) */
    private void recordPlanOutputs(IExperimentSpecies experimentPlan) {
        try {
            java.lang.reflect.Method getSimOutputs = experimentPlan.getClass()
                .getMethod("getOriginalSimulationOutputs");
            Object simOutputMgr = getSimOutputs.invoke(experimentPlan);
            if (simOutputMgr == null) return;

            java.lang.reflect.Field outputsField = simOutputMgr.getClass().getSuperclass()
                .getDeclaredField("outputs");
            outputsField.setAccessible(true);
            Object outputsMap = outputsField.get(simOutputMgr);
            if (!(outputsMap instanceof Map)) return;

            Map map = (Map) outputsMap;
            for (Object val : map.values()) {
                if (val instanceof LayeredDisplayOutput ldo) {
                    displayOutputs.put(ldo.getName(), ldo);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to record plan outputs: " + e.getMessage());
        }
    }

    public static void probeAndCreateSurface() {
        if (cachedExperimentPlan == null) return;

        // Try simulation agent's initialized outputs first (they have valid scopes)
        try {
            Class<?> gamaClass = Class.forName("gama.api.GAMA");
            java.lang.reflect.Field controllersField = gamaClass.getDeclaredField("controllers");
            controllersField.setAccessible(true);
            java.util.List controllers = (java.util.List) controllersField.get(null);
            if (controllers != null && !controllers.isEmpty()) {
                Object controller = controllers.get(controllers.size() - 1);
                java.lang.reflect.Field scopeField = controller.getClass().getSuperclass()
                    .getDeclaredField("scope");
                scopeField.setAccessible(true);
                IScope ctrlScope = (IScope) scopeField.get(controller);
                if (ctrlScope != null) {
                    Object rootAgent = ctrlScope.getRoot();
                    if (rootAgent != null) {
                        Object simAgent = rootAgent.getClass().getMethod("getSimulation").invoke(rootAgent);
                        if (simAgent != null) {
                            Object simOutMgr = simAgent.getClass().getMethod("getOutputManager").invoke(simAgent);
                            if (simOutMgr != null) {
                                java.lang.reflect.Field simOutputsField = simOutMgr.getClass().getSuperclass()
                                    .getDeclaredField("outputs");
                                simOutputsField.setAccessible(true);
                                Object simOutputsMap = simOutputsField.get(simOutMgr);
                                if (simOutputsMap instanceof Map) {
                                    Map map = (Map) simOutputsMap;
                                    for (Object val : map.values()) {
                                        if (val instanceof LayeredDisplayOutput ldo) {
                                            getInstance().displayOutputs.put(ldo.getName(), ldo);
                                            if (ldo.getSurface() == null) {
                                                getInstance().createDisplaySurfaceFor(ldo, null);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to probe display outputs: " + e.getMessage());
        }
    }

    private static void setSurfaceField(LayeredDisplayOutput output, IDisplaySurface surf) {
        try {
            Class<?> cls = output.getClass();
            java.lang.reflect.Field surfaceField = null;
            while (cls != null && surfaceField == null) {
                try {
                    surfaceField = cls.getDeclaredField("surface");
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
            if (surfaceField != null) {
                surfaceField.setAccessible(true);
                if (surfaceField.get(output) == null) {
                    surfaceField.set(output, surf);
                    Log.i(TAG, "Set surface field on output: " + output.getName());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not set surface field: " + e.getMessage());
        }
    }

    @Override
    public void updateParameters(boolean refreshValues) {}

    private static class ConsoleListener implements IConsoleListener {
        @Override
        public void informConsole(String s, ITopLevelAgent root, IColor color) {
            Log.i(TAG, s);
            Activity a = currentActivity;
            if (a instanceof ExperimentActivity) {
                ((ExperimentActivity) a).log(s);
            }
        }
    }
}
