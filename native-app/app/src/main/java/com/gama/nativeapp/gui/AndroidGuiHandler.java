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

import gama.core.common.interfaces.IDisplayCreator.DisplayDescription;
import gama.core.common.interfaces.IDisplaySurface;
import gama.core.common.interfaces.IConsoleListener;
import gama.core.common.interfaces.IGamaView;
import gama.core.common.interfaces.IGui;
import gama.core.kernel.experiment.IExperimentPlan;
import gama.core.kernel.experiment.IParameter;
import gama.core.kernel.model.IModel;
import gama.core.kernel.simulation.SimulationAgent;
import gama.core.outputs.IOutput;
import gama.core.outputs.LayeredDisplayOutput;
import gama.core.runtime.GAMA;
import gama.core.runtime.IScope;
import gama.core.runtime.exceptions.GamaRuntimeException;
import gama.core.util.GamaColor;
import gama.core.util.GamaFont;
import gama.core.util.IList;
import gama.core.util.IMap;
import gama.gaml.descriptions.ActionDescription;
import gama.gaml.statements.test.CompoundSummary;

public class AndroidGuiHandler implements IGui {

    private static final String TAG = "AndroidGuiHandler";
    private static Activity currentActivity;
    private static AndroidGuiHandler instance;
    private ConsoleListener consoleListener;
    private static IExperimentPlan cachedExperimentPlan;

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
    public IDisplaySurface createDisplaySurfaceFor(LayeredDisplayOutput output, Object... args) {
        String displayName = output.getName();
        if (displaySurfaces.containsKey(displayName)) {
            Log.i(TAG, "Surface already exists for display: " + displayName + ", skipping");
            return displaySurfaces.get(displayName);
        }

        // Don't create surface until output has a valid scope (sim must init it first)
        if (output.getScope() == null) {
            // Try setting scope from controller via buildScopeFrom and init layers
            try {
                Class<?> gamaClass = Class.forName("gama.core.runtime.GAMA");
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
                        IScope gfxScope = (IScope) buildScope.invoke(output, ctrlScope);
                        output.setScope(gfxScope);
                        Log.i(TAG, "Set scope on " + displayName + " from controller (via buildScopeFrom)");
                        // Init layers directly to resolve species (skip full init which fails on
                        // initWith when simulation envelope is null)
                        java.lang.reflect.Method getLayers = output.getClass().getMethod("getLayers");
                        java.util.List layers = (java.util.List) getLayers.invoke(output);
                        if (layers != null) {
                            for (Object layer : layers) {
                                try {
                                    java.lang.reflect.Method setDisp = layer.getClass().getMethod("setDisplayOutput", IOutput.class);
                                    setDisp.invoke(layer, output);
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
                            SimulationAgent sim = ctrlScope.getSimulation();
                            gama.core.common.geometry.Envelope3D env = null;
                            if (sim != null) env = sim.getEnvelope();
                            if (env == null) {
                                // Fallback to a default envelope when sim envelope unavailable
                                env = gama.core.common.geometry.Envelope3D.of(0, 100, 0, 100, 0, 0);
                                Log.w(TAG, "Using default env for " + displayName + " (sim=" + (sim != null ? "ok" : "null") + " env=" + (sim != null && sim.getEnvelope() != null ? "ok" : "null") + ")");
                            }
                            output.getData().setEnvWidth(env.getWidth());
                            output.getData().setEnvHeight(env.getHeight());
                            Log.i(TAG, "Set env dimensions for " + displayName + ": " + env.getWidth() + "x" + env.getHeight());
                        } catch (Throwable t) {
                            Log.w(TAG, "Env set failed: " + t.getMessage());
                        }
                    }
                }
            } catch (Throwable t) {
                Log.w(TAG, "Scope set+init failed for " + displayName + ": " + t.getMessage());
            }
            if (output.getScope() == null) {
                Log.i(TAG, "Deferring surface creation for " + displayName + " (scope not ready)");
                return null;
            }
        }

        Activity activity = currentActivity;
        if (activity == null) {
            Log.w(TAG, "No activity available for display surface creation");
            return null;
        }

        if (output.getData().is3D()) {
            Log.i(TAG, "3D display type preserved for: " + displayName);
        }

        final AndroidDisplaySurface[] surfaceHolder = new AndroidDisplaySurface[1];
        try {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            activity.runOnUiThread(() -> {
                try {
                    surfaceHolder[0] = new AndroidDisplaySurface(activity, output);
                    Log.i(TAG, "Created surface for display: " + displayName);

                    displayOutputs.put(displayName, output);
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
            setSurfaceField(output, surfaceHolder[0]);
        }

        return surfaceHolder[0];
    }

    @Override
    public DisplayDescription getDisplayDescriptionFor(String name) {
        return null;
    }

    @Override
    public boolean openSimulationPerspective(IModel model, String experimentId) {
        Activity activity = currentActivity;
        if (activity == null) return false;
        Intent intent = new Intent(activity, ExperimentActivity.class);
        intent.putExtra("model_name", model.getName());
        intent.putExtra("experiment_name", experimentId);
        activity.startActivity(intent);
        return true;
    }

    @Override
    public void openMessageDialog(IScope scope, String error) {
        deliver("Message: " + error, false, false);
    }

    @Override
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
            List<IParameter> parameters, GamaFont font, GamaColor color, Boolean showTitle) {
        return java.util.Collections.emptyMap();
    }

    @Override
    public IMap<String, IMap<String, Object>> openWizard(IScope scope, String title,
            ActionDescription finish, IList<IMap<String, Object>> pages) {
        return null;
    }

    @Override
    public void displayTestsResults(IScope scope, CompoundSummary<?, ?> summary) {}

    @Override
    public void arrangeExperimentViews(IScope myScope, IExperimentPlan experimentPlan,
            Boolean keepTabs, Boolean keepToolbars, Boolean showConsoles,
            Boolean showParameters, Boolean showNavigator, Boolean showControls,
            Boolean keepTray, Supplier<GamaColor> color, boolean showEditors) {
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
    private void recordPlanOutputs(IExperimentPlan experimentPlan) {
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
            Class<?> gamaClass = Class.forName("gama.core.runtime.GAMA");
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
                                                getInstance().createDisplaySurfaceFor(ldo);
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
        public void informConsole(String s, gama.core.kernel.experiment.ITopLevelAgent root, GamaColor color) {
            Log.i(TAG, s);
            Activity a = currentActivity;
            if (a instanceof ExperimentActivity) {
                ((ExperimentActivity) a).log(s);
            }
        }
    }
}
