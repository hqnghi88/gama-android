package com.gama.nativeapp;

import android.content.Context;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

public class GamaNativeBootstrap {

    private static final String TAG = "GamaNativeBootstrap";
    private static final Map<String, Bundle> registeredBundles = new LinkedHashMap<>();
    private static volatile boolean initialized = false;

    public static boolean isInitialized() { return initialized; }

    public interface ProgressCallback {
        void onProgress(String message);
        void onSuccess(String message);
        void onFailure(String message, Throwable t);
    }

    public static void initialize(Context context, ProgressCallback callback) throws Exception {
        // Note: NOT setting org.geotools.referencing.forceXY - it triggers
        // LongitudeFirstFactory wrapping in DefaultAuthorityFactory which causes
        // RecursiveSearchException on Android. Axis order from .prj files is used.
        Log.i(TAG, "=== Bootstrap started ===");
        callback.onProgress("Setting up GAMA plugin bundles...");

        ClassLoader appClassLoader = context.getClassLoader();

        List<String> pluginNames = Arrays.asList(
            "gama.core", "gama.library", "gama.headless", "gaml.compiler",
            "gama.processor", "gama.annotations", "gama.dependencies",
            "gama.extension.bdi", "gama.extension.database", "gama.extension.fipa",
            "gama.extension.image", "gama.extension.maths", "gama.extension.network",
            "gama.extension.pedestrian", "gama.extension.serialize",
            "gama.extension.stats", "gama.extension.traffic",
            "gama.extension.androidsensor",
            "gama.ui.application", "gama.ui.display.java2d", "gama.ui.display.opengl",
            "gama.ui.editor", "gama.ui.experiment", "gama.ui.navigator",
            "gama.ui.shared", "gama.ui.viewers"
        );

        for (String pluginName : pluginNames) {
            Bundle bundle = createBundle(pluginName, appClassLoader);
            registeredBundles.put(pluginName, bundle);
            Platform.registerBundle(pluginName, bundle);
        }

        Log.i(TAG, "Registered " + registeredBundles.size() + " plugin bundles");
        callback.onProgress("Registered " + registeredBundles.size() + " plugin bundles");

        callback.onProgress("Loading GAML language additions...");

        String additionsBase = "gaml.additions";
        String additionsClass = "GamlAdditions";

        List<String> loadOrder = new ArrayList<>();
        loadOrder.add("gama.core");
        for (String name : registeredBundles.keySet()) {
            if (!name.equals("gama.core")) {
                loadOrder.add(name);
            }
        }

        int loaded = 0;
        int skipped = 0;
        for (String pluginName : loadOrder) {
            Bundle bundle = registeredBundles.get(pluginName);
            if (bundle == null) continue;

            String shortName = pluginName.substring(pluginName.lastIndexOf('.') + 1);
            String classPath = additionsBase + "." + shortName + "." + additionsClass;

            try {
                Class<?> clazz = bundle.loadClass(classPath);
                Constructor<?> ctor = clazz.getConstructor();
                Object instance = ctor.newInstance();
                Method initMethod = clazz.getMethod("initialize");
                initMethod.invoke(instance);
                loaded++;
                callback.onProgress("Loaded additions: " + pluginName + " (" + loaded + "/" + loadOrder.size() + ")");
            } catch (ClassNotFoundException e) {
                skipped++;
                callback.onProgress("No additions for: " + pluginName);
            } catch (NoClassDefFoundError | VerifyError e) {
                skipped++;
                Log.w(TAG, "Missing class for: " + pluginName, e);
                callback.onProgress("Missing class for " + pluginName + ": " + e.getMessage());
            } catch (Exception e) {
                skipped++;
                Log.w(TAG, "Failed to load additions for: " + pluginName, e);
                callback.onProgress("Error loading " + pluginName + ": " + e.getMessage());
            } catch (Throwable t) {
                skipped++;
                Log.w(TAG, "Unexpected error for: " + pluginName, t);
                callback.onProgress("Unexpected error for " + pluginName + ": " + t.getClass().getSimpleName() + ": " + t.getMessage());
            }
        }

        Log.i(TAG, "Loaded " + loaded + " plugin additions, skipped " + skipped);
        callback.onProgress("Loaded " + loaded + " plugin additions, skipped " + skipped);

        callback.onProgress("Initializing GAMA meta-model...");
        try {
            Class<?> metaModelClass = Class.forName("gama.gaml.compilation.kernel.GamaMetaModel");
            Object metaModelInstance = metaModelClass.getField("INSTANCE").get(null);
            Method buildMethod = metaModelClass.getMethod("build");
            buildMethod.invoke(metaModelInstance);
            callback.onProgress("Meta-model initialized");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to init meta-model", e);
            callback.onProgress("Meta-model init error: " + e.getMessage());
        }

        callback.onProgress("Initializing type system...");
        try {
            Class<?> typesClass = Class.forName("gama.gaml.types.Types");
            Method initMethod = typesClass.getMethod("init");
            initMethod.invoke(null);
            callback.onProgress("Type system initialized");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to init types", e);
            callback.onProgress("Types init error: " + e.getMessage());
        }

        try {
            Class<?> bundleLoaderClass = Class.forName("gama.gaml.compilation.kernel.GamaBundleLoader");
            java.lang.reflect.Field loadedField = bundleLoaderClass.getField("LOADED");
            loadedField.set(null, true);
            callback.onProgress("Set GamaBundleLoader.LOADED = true");
        } catch (Throwable e) {
            Log.w(TAG, "Failed to set LOADED flag", e);
        }

        try {
            Class<?> datesClass = Class.forName("gama.gaml.operators.Dates");
            Method initMethod = datesClass.getMethod("initialize");
            initMethod.invoke(null);
            callback.onProgress("Dates initialized");
        } catch (Throwable e) {
            Log.w(TAG, "Failed to init Dates", e);
        }

        // Initialize the ForkJoinPool for parallel agent execution
        // On desktop this is triggered by preference change listeners, but on Android prefs are no-op
        try {
            Class<?> executorClass = Class.forName("gama.core.runtime.concurrent.GamaExecutorService");
            Method resetMethod = executorClass.getMethod("reset");
            resetMethod.invoke(null);

            Field poolField = executorClass.getField("AGENT_PARALLEL_EXECUTOR");
            Object pool = poolField.get(null);
            if (pool != null) {
                Log.i(TAG, "ForkJoinPool initialized: " + pool);
                callback.onProgress("ForkJoinPool initialized for parallel execution");
            } else {
                Log.w(TAG, "ForkJoinPool still null after reset()");
            }

            // Verify ANDROID_PARALLEL_EXECUTOR (our ExecutorService replacement)
            try {
                Field androidPoolField = executorClass.getField("ANDROID_PARALLEL_EXECUTOR");
                Object androidPool = androidPoolField.get(null);
                if (androidPool != null) {
                    Log.i(TAG, "ANDROID_PARALLEL_EXECUTOR initialized: " + androidPool.getClass().getSimpleName());
                } else {
                    Log.w(TAG, "ANDROID_PARALLEL_EXECUTOR is null");
                }
            } catch (NoSuchFieldException e) {
                Log.w(TAG, "ANDROID_PARALLEL_EXECUTOR field not found (patcher may not have run)");
            }
        } catch (Throwable e) {
            Log.e(TAG, "Failed to init ForkJoinPool", e);
            callback.onProgress("ForkJoinPool init failed: " + e.getMessage());
        }

        // Register the Android GUI handler as the GAMA GUI
        try {
            Class<?> gamaClass = Class.forName("gama.api.GAMA");
            Class<?> guiHandlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
            Object guiHandler = guiHandlerClass.getMethod("getInstance").invoke(null);
            Method setHeadlessGui = gamaClass.getMethod("setHeadlessGui", Class.forName("gama.api.ui.IGui"));
            setHeadlessGui.invoke(null, guiHandler);
            Log.i(TAG, "Android GUI handler registered");
            callback.onProgress("Android GUI handler registered");
        } catch (Throwable e) {
            Log.w(TAG, "Failed to set Android GUI handler", e);
            callback.onProgress("GUI handler setup skipped: " + e.getMessage());
        }

        // Register android2d display type
        try {
            Class<?> setupClass = Class.forName("com.gama.nativeapp.display.GamaAndroidDisplaySetup");
            setupClass.getMethod("registerDisplays").invoke(null);
            callback.onProgress("Registered android2d display type");
        } catch (Throwable e) {
            Log.w(TAG, "Failed to register android2d display", e);
            callback.onProgress("Display registration skipped: " + e.getMessage());
        }

        // Register event layer delegates (normally loaded via Eclipse extension points)
        // MUST run before CoreConstantsSupplier below, which registers the mouse/keyboard
        // event constants (#mouse_down, #mouse_move, etc.) by browsing EventLayerStatement.delegates
        try {
            Class<?> eventLayerStatementClass = Class.forName("gama.core.outputs.layers.EventLayerStatement");
            Class<?> eventDelegateIface = Class.forName("gama.core.common.interfaces.IEventLayerDelegate");
            Method addEventDelegate = eventLayerStatementClass.getMethod("addDelegate", eventDelegateIface);
            addEventDelegate.invoke(null,
                    Class.forName("gama.core.outputs.layers.MouseEventLayerDelegate").getDeclaredConstructor().newInstance());
            addEventDelegate.invoke(null,
                    Class.forName("gama.core.outputs.layers.KeyboardEventLayerDelegate").getDeclaredConstructor().newInstance());
            Log.i(TAG, "Event layer delegates registered (mouse, keyboard)");
            callback.onProgress("Event layer delegates registered");
        } catch (Throwable e) {
            Log.w(TAG, "Failed to register event layer delegates", e);
            callback.onProgress("Event layer delegates registration skipped: " + e.getMessage());
        }

        // Register GAML constants (units, colors, etc.) BEFORE XText init
        // BuiltinGlobalScopeProvider reads GAML.UNITS in its constructor during XText init
        try {
            Class<?> gamlConstClass = Class.forName("gama.api.gaml.GAML");
            Class<?> acceptorClass = Class.forName("gama.gaml.constants.IConstantAcceptor");
            Method getAcceptor = gamlConstClass.getMethod("getConstantAcceptor");
            Object acceptor = getAcceptor.invoke(null);

            Class<?> supplierClass = Class.forName("gama.gaml.constants.CoreConstantsSupplier");
            Object supplier = supplierClass.getDeclaredConstructor().newInstance();
            Method supply = supplierClass.getMethod("supplyConstantsTo", acceptorClass);
            supply.invoke(supplier, acceptor);

            // Also register math constants (ODE solvers)
            try {
                Class<?> mathSupplierClass = Class.forName("gama.extension.maths.ode.MathConstantSupplier");
                Object mathSupplier = mathSupplierClass.getDeclaredConstructor().newInstance();
                supply.invoke(mathSupplier, acceptor);
                Log.i(TAG, "Math constants registered");
            } catch (Throwable ignored) {}

            // Also register image constants
            try {
                Class<?> imgSupplierClass = Class.forName("gama.extension.image.ImageConstantSupplier");
                Object imgSupplier = imgSupplierClass.getDeclaredConstructor().newInstance();
                supply.invoke(imgSupplier, acceptor);
                Log.i(TAG, "Image constants registered");
            } catch (Throwable ignored) {}

            Field unitsField = gamlConstClass.getField("UNITS");
            @SuppressWarnings("unchecked")
            java.util.Map<?, ?> units = (java.util.Map<?, ?>) unitsField.get(null);
            Log.i(TAG, "GAML constants registered: " + units.size() + " entries (includes colors, units)");
            callback.onProgress("GAML constants registered: " + units.size() + " entries");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to register GAML constants", e);
            callback.onProgress("Constants registration failed: " + e.getMessage());
        }

        // Initialize XText/GAML compiler infrastructure
        try {
            Class<?> standaloneClass = Class.forName("gaml.compiler.gaml.GamlStandaloneSetup");
            Method doSetup = standaloneClass.getMethod("doSetup");
            Object injector = doSetup.invoke(null);
            callback.onProgress("GAML compiler initialized (XText injector ready)");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to init GAML compiler", e);
            callback.onProgress("GAML compiler init failed: " + e.getMessage());
        }

        // Manually register draw delegates (normally loaded via Eclipse extension points)
        // Try multiple classloaders to ensure the DELEGATES map is populated in the right one
        try {
            Class<?> drawStatementClass = Class.forName("gama.gaml.statements.draw.DrawStatement");
            ClassLoader dsCl = drawStatementClass.getClassLoader();
            Log.i(TAG, "DrawStatement classloader: " + dsCl);
            Class<?> shapeDrawerClass = Class.forName("gama.gaml.statements.draw.ShapeDrawer");
            Class<?> textDrawerClass = Class.forName("gama.gaml.statements.draw.TextDrawer");
            Class<?> assetDrawerClass = Class.forName("gama.gaml.statements.draw.AssetDrawer");
            Class<?> aspectDrawerClass = Class.forName("gama.gaml.statements.draw.AspectDrawer");
            Method addDelegate = drawStatementClass.getMethod("addDelegate", Class.forName("gama.core.common.interfaces.IDrawDelegate"));
            addDelegate.invoke(null, shapeDrawerClass.getDeclaredConstructor().newInstance());
            addDelegate.invoke(null, textDrawerClass.getDeclaredConstructor().newInstance());
            addDelegate.invoke(null, assetDrawerClass.getDeclaredConstructor().newInstance());
            addDelegate.invoke(null, aspectDrawerClass.getDeclaredConstructor().newInstance());
            Log.i(TAG, "Draw delegates registered on classloader " + dsCl);
            callback.onProgress("Draw delegates registered");

            // Verify DELEGATES map
            Field delegatesField = drawStatementClass.getDeclaredField("DELEGATES");
            delegatesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<?, ?> delegates = (java.util.Map<?, ?>) delegatesField.get(null);
            Log.i(TAG, "DELEGATES map size: " + delegates.size() + " keys: " + delegates.keySet());
        } catch (Throwable e) {
            Log.w(TAG, "Failed to register draw delegates", e);
            callback.onProgress("Draw delegates registration skipped: " + e.getMessage());
        }

        // Register create delegates (normally loaded via Eclipse extension points)
        try {
            Class<?> createStatementClass = Class.forName("gama.gaml.statements.CreateStatement");
            Class<?> nullDelegateClass = Class.forName("gama.gaml.statements.create.CreateFromNullDelegate");
            Class<?> csvDelegateClass = Class.forName("gama.gaml.statements.create.CreateFromCSVDelegate");
            Class<?> geomDelegateClass = Class.forName("gama.gaml.statements.create.CreateFromGeometriesDelegate");
            Class<?> gridDelegateClass = Class.forName("gama.gaml.statements.create.CreateFromGridFileDelegate");
            Class<?> icdInterface = Class.forName("gama.core.common.interfaces.ICreateDelegate");
            Method addCreateDelegate = createStatementClass.getMethod("addDelegate", icdInterface);
            addCreateDelegate.invoke(null, nullDelegateClass.getDeclaredConstructor().newInstance());
            addCreateDelegate.invoke(null, csvDelegateClass.getDeclaredConstructor().newInstance());
            addCreateDelegate.invoke(null, geomDelegateClass.getDeclaredConstructor().newInstance());
            addCreateDelegate.invoke(null, gridDelegateClass.getDeclaredConstructor().newInstance());
            Log.i(TAG, "Create delegates registered (4)");
            callback.onProgress("Create delegates registered");
        } catch (Throwable e) {
            Log.w(TAG, "Failed to register create delegates", e);
            callback.onProgress("Create delegates registration skipped: " + e.getMessage());
        }

        // Register GAML parser, info provider, ecore utils, model builder, validator
        // (equivalent to GamlActivator.start() in OSGi)
        try {
            // 1. Register parser provider
            Class<?> exprFactory = Class.forName("gama.gaml.expressions.GamlExpressionFactory");
            Class<?> compilerClass = Class.forName("gaml.compiler.gaml.expression.GamlExpressionCompiler");
            Class<?> providerClass = Class.forName("gama.gaml.expressions.GamlExpressionFactory$ParserProvider");
            Method registerParser = exprFactory.getMethod("registerParserProvider",
                Class.forName("gama.gaml.expressions.GamlExpressionFactory$ParserProvider"));
            Object parserProvider = java.lang.reflect.Proxy.newProxyInstance(
                appClassLoader,
                new Class[]{ providerClass },
                (proxy, method, args) -> {
                    if ("get".equals(method.getName())) {
                        return compilerClass.getDeclaredConstructor().newInstance();
                    }
                    return null;
                });
            registerParser.invoke(null, parserProvider);
            Log.i(TAG, "GAML parser provider registered");

            // 2. Register info provider
            Class<?> gamlClass = Class.forName("gama.api.gaml.GAML");
            Class<?> infoProviderClass = Class.forName("gaml.compiler.gaml.resource.GamlResourceInfoProvider");
            Object infoProviderInstance = infoProviderClass.getField("INSTANCE").get(null);
            Class<?> infoProviderIface = Class.forName("gama.core.util.file.IGamlResourceInfoProvider");
            Method registerInfo = gamlClass.getMethod("registerInfoProvider", infoProviderIface);
            registerInfo.invoke(null, infoProviderInstance);
            Log.i(TAG, "GAML info provider registered");

            // 3. Register ecore utils
            Class<?> egamlClass = Class.forName("gaml.compiler.gaml.EGaml");
            Object egamlInstance = egamlClass.getMethod("getInstance").invoke(null);
            Class<?> ecoreUtilsIface = Class.forName("gama.gaml.compilation.IGamlEcoreUtils");
            Method registerEcore = gamlClass.getMethod("registerGamlEcoreUtils", ecoreUtilsIface);
            registerEcore.invoke(null, egamlInstance);
            Log.i(TAG, "GAML ecore utils registered");

            // 4. Register model builder
            Class<?> modelBuilderClass = Class.forName("gaml.compiler.gaml.validation.GamlModelBuilder");
            Object modelBuilderInstance = modelBuilderClass.getMethod("getDefaultInstance").invoke(null);
            Class<?> modelBuilderIface = Class.forName("gama.gaml.compilation.IGamlModelBuilder");
            Method registerModelBuilder = gamlClass.getMethod("registerGamlModelBuilder", modelBuilderIface);
            registerModelBuilder.invoke(null, modelBuilderInstance);
            Log.i(TAG, "GAML model builder registered");

            // 5. Register text validator
            Class<?> validatorClass = Class.forName("gaml.compiler.gaml.validation.GamlTextValidator");
            Object validatorInstance = validatorClass.getDeclaredConstructor().newInstance();
            Class<?> validatorIface = Class.forName("gama.gaml.compilation.IGamlTextValidator");
            Method registerValidator = gamlClass.getMethod("registerGamlTextValidator", validatorIface);
            registerValidator.invoke(null, validatorInstance);
            Log.i(TAG, "GAML text validator registered");

            callback.onProgress("GAML services registered (parser, info, ecore, builder, validator)");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to register GAML services", e);
            callback.onProgress("GAML registration skipped: " + e.getMessage());
        }

        initialized = true;
        callback.onSuccess("GAMA engine initialized! " + loaded + " plugins loaded.");
    }

    private static Bundle createBundle(String name, ClassLoader loader) {
        return new Bundle() {
            @Override
            public String getSymbolicName() { return name; }

            @Override
            public Class<?> loadClass(String className) throws ClassNotFoundException {
                try {
                    return loader.loadClass(className);
                } catch (ClassNotFoundException e) {
                    throw e;
                }
            }

            @Override
            public java.net.URL getResource(String resName) {
                return loader.getResource(resName);
            }

            @Override
            public java.util.Enumeration<java.net.URL> getResources(String resName) throws java.io.IOException {
                return loader.getResources(resName);
            }

            @Override
            public java.net.URL getEntry(String path) {
                return loader.getResource(path);
            }

            @Override
            public java.util.Enumeration<java.net.URL> findEntries(String path, String filePattern, boolean recurse) {
                return null;
            }
        };
    }
}
