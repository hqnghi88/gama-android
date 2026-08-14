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

        WorkspaceManager.setEngineWorkspacePath(WorkspaceManager.workspaceRoot(context).getAbsolutePath());
        Log.i(TAG, "Engine workspace root: " + WorkspaceManager.engineWorkspacePath());

        ClassLoader appClassLoader = context.getClassLoader();

        List<String> pluginNames = Arrays.asList(
            "gama.api", "gama.core", "gama.library", "gama.headless", "gaml.compiler",
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

        // Install an Android workspace manager. The desktop implementation tracks
        // the workspace through OSGi service discovery, which does not exist on
        // Android; without a workspace, GamlResourceIndexer.<clinit> fails.
        try {
            Class<?> gamaClass = Class.forName("gama.api.GAMA");
            gamaClass.getMethod("setWorkspaceManager", Class.forName("gama.api.runtime.IWorkspaceManager"))
                .invoke(null, new AndroidWorkspaceManager());
            Log.i(TAG, "Android workspace manager registered");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to register workspace manager", e);
        }

        // Initialize the concrete data-type factories (shapes, topologies, paths, matrices,
        // graphs, messages). Desktop does this in gama.core.CoreActivator.initializeFactories(),
        // but that method starts with ProjectionFactory.initialize() which uses Java 21
        // Thread.ofVirtual() (unavailable on Android), so we register each factory directly.
        // Without these, GamaShapeFactory.InternalFactory is null and shape/topology creation crashes.
        try {
            String[][] factories = {
                {"gama.api.types.geometry.GamaShapeFactory", "gama.api.types.geometry.IShapeFactory", "gama.core.geometry.InternalGamaShapeFactory"},
                {"gama.api.types.topology.GamaTopologyFactory", "gama.api.types.topology.ITopologyFactory", "gama.core.topology.InternalTopologyFactory"},
                {"gama.api.types.graph.GamaPathFactory", "gama.api.types.graph.IPathFactory", "gama.core.util.path.InternalGamaPathFactory"},
                {"gama.api.types.matrix.GamaMatrixFactory", "gama.api.types.matrix.IMatrixFactory", "gama.core.util.matrix.InternalGamaMatrixFactory"},
                {"gama.api.types.graph.GamaGraphFactory", "gama.api.types.graph.IGraphFactory", "gama.core.util.graph.InternalGamaGraphFactory"},
                {"gama.api.types.message.GamaMessageFactory", "gama.api.types.message.IMessageFactory", "gama.core.util.messaging.GamaMessage$Factory"},
            };
            for (String[] f : factories) {
                Class<?> holder = Class.forName(f[0]);
                Class<?> iface = Class.forName(f[1]);
                Method setBuilder = holder.getMethod("setBuilder", iface);
                setBuilder.invoke(null, Class.forName(f[2]).getDeclaredConstructor().newInstance());
            }
            Log.i(TAG, "GAMA data-type factories initialized");
            callback.onProgress("GAMA data-type factories initialized");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to initialize data-type factories", e);
            callback.onProgress("Factory init error: " + e.getMessage());
        }

        // Register GAML compiler services BEFORE additions load (replicates gaml.compiler.GamlActivator.start()).
        // Additions call GAML.getDescriptionFactory()/getArtefactFactory()/getExpressionFactory() via desc()/_facet()/_operator().
        try {
            Class<?> gamlClass = Class.forName("gama.api.gaml.GAML");

            // 1. Symbol description factories (statements, species, experiments, ...)
            Method registerSymbolFactory = gamlClass.getMethod("registerSymbolFactory",
                Class.forName("gama.api.compilation.factories.ISymbolDescriptionFactory"));
            String[] symbolFactoryClasses = {
                "gaml.compiler.factories.ExperimentFactory",
                "gaml.compiler.factories.ModelFactory",
                "gaml.compiler.factories.PlatformFactory",
                "gaml.compiler.factories.SpeciesFactory",
                "gaml.compiler.factories.StatementFactory",
                "gaml.compiler.factories.VariableFactory",
                "gaml.compiler.factories.SkillFactory",
                "gaml.compiler.factories.ClassFactory"
            };
            for (String fqcn : symbolFactoryClasses) {
                registerSymbolFactory.invoke(null, Class.forName(fqcn).getMethod("getInstance").invoke(null));
            }

            // 2. Description factory
            Method registerDescriptionFactory = gamlClass.getMethod("registerDescriptionFactory",
                Class.forName("gama.api.compilation.descriptions.IDescriptionFactory"));
            registerDescriptionFactory.invoke(null, Class.forName("gaml.compiler.descriptions.DescriptionFactory")
                .getMethod("getInstance").invoke(null));

            // 3. Artefact factory (operators)
            Method registerArtefactFactory = gamlClass.getMethod("registerArtefactProtoFactory",
                Class.forName("gama.api.compilation.artefacts.IArtefactFactory"));
            registerArtefactFactory.invoke(null, Class.forName("gaml.compiler.prototypes.ArtefactFactory")
                .getMethod("getInstance").invoke(null));

            // 4. Expression factory
            Method registerExpressionFactory = gamlClass.getMethod("registerExpressionFactory",
                Class.forName("gama.api.compilation.factories.IExpressionFactory"));
            registerExpressionFactory.invoke(null, Class.forName("gaml.compiler.expressions.GamlExpressionFactory")
                .getMethod("getInstance").invoke(null));

            // 5. Expression description factory
            Method registerExpressionDescriptionFactory = gamlClass.getMethod("registerExpressionDescriptionFactory",
                Class.forName("gama.api.compilation.factories.IExpressionDescriptionFactory"));
            registerExpressionDescriptionFactory.invoke(null, Class.forName("gaml.compiler.factories.ExpressionDescriptionFactory")
                .getMethod("getInstance").invoke(null));

            // 6. GAML content provider (URI -> syntactic contents)
            Method registerContentProvider = gamlClass.getMethod("registerGamlContentProvider", java.util.function.Function.class);
            java.util.function.Function<org.eclipse.emf.common.util.URI, ?> contentProvider = uri -> {
                try {
                    return Class.forName("gaml.compiler.resource.GamlResourceServices")
                        .getMethod("getOrCreateSyntacticContents", org.eclipse.emf.common.util.URI.class)
                        .invoke(null, uri);
                } catch (Exception e) {
                    throw new RuntimeException("Failed to build syntactic contents for " + uri, e);
                }
            };
            registerContentProvider.invoke(null, contentProvider);

            // 7. Model builder
            Method registerModelBuilder = gamlClass.getMethod("registerGamlModelBuilder",
                Class.forName("gama.api.compilation.validation.IGamlModelBuilder"));
            registerModelBuilder.invoke(null, Class.forName("gaml.compiler.validation.GamlModelBuilder")
                .getMethod("getInstance").invoke(null));

            // 8. Text validator
            Method registerTextValidator = gamlClass.getMethod("registerGamlTextValidator",
                Class.forName("gama.api.compilation.validation.IGamlTextValidator"));
            registerTextValidator.invoke(null, Class.forName("gaml.compiler.validation.GamlTextValidator")
                .getMethod("getInstance").invoke(null));

            Log.i(TAG, "GAML services registered (symbol factories, descriptions, artefact factory, expression factories, content provider, model builder, validator)");
            callback.onProgress("GAML services registered (parser, factories, builder, validator)");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to register GAML services", e);
            callback.onProgress("GAML registration skipped: " + e.getMessage());
        }

        callback.onProgress("Loading GAML language additions...");

        String additionsBase = "gaml.additions";
        String additionsClass = "GamlAdditions";

        List<String> loadOrder = new ArrayList<>();
        loadOrder.add("gama.api");
        loadOrder.add("gama.core");
        for (String name : registeredBundles.keySet()) {
            if (!name.equals("gama.core") && !name.equals("gama.api")) {
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

        // Register the base agent classes for species. Desktop does this in
        // gama.core.CoreActivator.initializeAgentClasses(); without it,
        // SpeciesDescription.getJavaBase() -> AgentConstructorsRegistry.getBaseClass()
        // returns null and every species fails with "Java base is unknown".
        try {
            Class<?> registryClass = Class.forName("gama.api.additions.registries.AgentConstructorsRegistry");
            Method register = registryClass.getMethod("register", Class.class, boolean.class);
            register.invoke(null, Class.forName("gama.core.agent.GamlAgent"), false);
            register.invoke(null, Class.forName("gama.core.agent.MinimalAgent"), true);
            callback.onProgress("Agent base classes registered");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to register agent base classes", e);
            callback.onProgress("Agent base registration error: " + e.getMessage());
        }

        // Register the JSON encoder/parser. Desktop does this in
        // gama.core.CoreActivator.initialize() (Json.getNew() -> GAMA.setJsonEncoder());
        // without it json_file(http://...) and json() fail with a null encoder.
        try {
            Object json = Class.forName("gama.core.util.json.Json").getMethod("getNew").invoke(null);
            Class.forName("gama.api.GAMA")
                .getMethod("setJsonEncoder", Class.forName("gama.api.utils.json.IJson"))
                .invoke(null, json);
            Log.i(TAG, "JSON encoder registered");
            callback.onProgress("JSON encoder registered");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to register JSON encoder", e);
            callback.onProgress("JSON encoder registration error: " + e.getMessage());
        }

        // Register event layer delegates (normally loaded via Eclipse extension points)
        // MUST run before CoreConstantsSupplier below, which registers the mouse/keyboard
        // event constants (#mouse_down, #mouse_move, etc.) by browsing EventLayerStatement.delegates
        try {
            Class<?> registryClass = Class.forName("gama.api.additions.registries.GamaAdditionRegistry");
            Class<?> eventDelegateIface = Class.forName("gama.api.additions.delegates.IEventLayerDelegate");
            Method addEventDelegate = registryClass.getMethod("addDelegate", eventDelegateIface);
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

        // Register GAML constants (units, colors, etc.) BEFORE XText init.
        // BuiltinGlobalScopeProvider.initialize() is invoked by
        // GamlStandaloneSetup.initializeAfterPlatformReady() and snapshots GAML.getConstants()
        // / getUnits() at that moment; if constants are registered afterwards, the built-in
        // scope never contains the color/unit names (e.g. '#white') and synthetic expression
        // compilation (grid defaults, facet defaults) fails to link them.
        try {
            Class<?> gamlConstClass = Class.forName("gama.api.gaml.GAML");
            Class<?> acceptorClass = Class.forName("gama.api.additions.IConstantAcceptor");
            Method getAcceptor = gamlConstClass.getMethod("getConstantAcceptor");
            Object acceptor = getAcceptor.invoke(null);

            Class<?> supplierClass = Class.forName("gama.api.gaml.constants.CoreConstantsSupplier");
            Object supplier = supplierClass.getDeclaredConstructor().newInstance();
            Class<?> supplierIface = Class.forName("gama.api.additions.delegates.IConstantsSupplier");
            Method supply = supplierIface.getMethod("supplyConstantsTo", acceptorClass);
            supply.invoke(supplier, acceptor);

            // Also register math constants (ODE solvers)
            try {
                Class<?> mathSupplierClass = Class.forName("gama.extension.maths.ode.MathConstantSupplier");
                Object mathSupplier = mathSupplierClass.getDeclaredConstructor().newInstance();
                supply.invoke(mathSupplier, acceptor);
                Log.i(TAG, "Math constants registered");
            } catch (Throwable e) {
                Log.e(TAG, "Math constants registration failed", e);
            }

            // Also register image constants
            try {
                Class<?> imgSupplierClass = Class.forName("gama.extension.image.ImageConstantSupplier");
                Object imgSupplier = imgSupplierClass.getDeclaredConstructor().newInstance();
                supply.invoke(imgSupplier, acceptor);
                Log.i(TAG, "Image constants registered");
            } catch (Throwable ignored) {}

            Method getUnits = gamlConstClass.getMethod("getUnits");
            @SuppressWarnings("unchecked")
            java.util.Map<?, ?> units = (java.util.Map<?, ?>) getUnits.invoke(null);
            Log.i(TAG, "GAML constants registered: " + units.size() + " entries (includes colors, units)");
            callback.onProgress("GAML constants registered: " + units.size() + " entries");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to register GAML constants", e);
            callback.onProgress("Constants registration failed: " + e.getMessage());
        }

        // Register the Xtext resource factories for the "gaml" extension and
        // initialize the built-in global scope provider. Mirrors what desktop
        // does with GamlStandaloneSetup.doSetup() + initializeAfterPlatformReady().
        // Must run AFTER the constants registration above so the built-in scope is
        // populated with all color/unit constants.
        try {
            Class<?> setupClass = Class.forName("gaml.compiler.GamlStandaloneSetup");
            Object injector = setupClass.getMethod("doSetup").invoke(null);
            setupClass.getMethod("initializeAfterPlatformReady", Class.forName("com.google.inject.Injector"))
                .invoke(null, injector);
            callback.onProgress("Xtext GAML resource factory registered");
        } catch (Throwable e) {
            Log.w(TAG, "Xtext setup failed", e);
            callback.onProgress("Xtext setup failed: " + e.getMessage());
        }

        // Force-initialize ALL GAMAPreferences nested classes BEFORE the meta-model is
        // built. The platform species description snapshots the currently-registered
        // prefs in PlatformSpeciesDescription.copyJavaAdditions(), and the runtime
        // 'platform' species is compiled (and cached) from that description shortly
        // afterwards. Any pref class that initializes AFTER that point only reaches the
        // description (via GamaPreferences.register -> addPrefAsVariable) and never the
        // cached runtime species, so creating the platform population later NPEs on a
        // null getVar() (e.g. pref_console_size). On desktop the preference classes are
        // all initialized during UI startup; on Android we must do it here explicitly.
        try {
            for (String prefClass : new String[] {
                "gama.api.utils.prefs.GamaPreferences$Theme",
                "gama.api.utils.prefs.GamaPreferences$Network",
                "gama.api.utils.prefs.GamaPreferences$Interface",
                "gama.api.utils.prefs.GamaPreferences$Modeling",
                "gama.api.utils.prefs.GamaPreferences$Runtime",
                "gama.api.utils.prefs.GamaPreferences$Displays",
                "gama.api.utils.prefs.GamaPreferences$External",
                "gama.api.utils.prefs.GamaPreferences$Experimental"
            }) {
                Class.forName(prefClass);
            }
            Log.i(TAG, "GAMA preference classes initialized");
            callback.onProgress("GAMA preferences initialized");
        } catch (Throwable e) {
            Log.w(TAG, "Failed to initialize GAMA preference classes", e);
        }

        callback.onProgress("Initializing GAMA meta-model...");
        try {
            Class<?> metaModelClass = Class.forName("gama.api.kernel.GamaMetaModel");
            Method buildMethod = metaModelClass.getMethod("build");
            buildMethod.invoke(null);
            callback.onProgress("Meta-model initialized");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to init meta-model", e);
            callback.onProgress("Meta-model init error: " + e.getMessage());
        }

        callback.onProgress("Initializing type system...");
        try {
            Class<?> typesClass = Class.forName("gama.api.gaml.types.Types");
            Method initMethod = typesClass.getMethod("init");
            initMethod.invoke(null);
            callback.onProgress("Type system initialized");
        } catch (Throwable e) {
            Log.e(TAG, "Failed to init types", e);
            callback.onProgress("Types init error: " + e.getMessage());
        }

        // Note: GamaBundleLoader (gama.api.additions) no longer exposes a LOADED
        // flag in the new architecture; extension loading is driven by the
        // gaml.additions bundles loaded above, so this step is not needed.
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
            Class<?> executorClass = Class.forName("gama.api.runtime.GamaExecutorService");
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

        // Register draw delegates (normally loaded via Eclipse extension points)
        try {
            Class<?> registryClass = Class.forName("gama.api.additions.registries.GamaAdditionRegistry");
            Class<?> drawDelegateIface = Class.forName("gama.api.additions.delegates.IDrawDelegate");
            Method addDelegate = registryClass.getMethod("addDelegate", drawDelegateIface);
            addDelegate.invoke(null, Class.forName("gama.gaml.statements.draw.ShapeDrawer").getDeclaredConstructor().newInstance());
            addDelegate.invoke(null, Class.forName("gama.gaml.statements.draw.TextDrawer").getDeclaredConstructor().newInstance());
            addDelegate.invoke(null, Class.forName("gama.gaml.statements.draw.AssetDrawer").getDeclaredConstructor().newInstance());
            addDelegate.invoke(null, Class.forName("gama.gaml.statements.draw.AspectDrawer").getDeclaredConstructor().newInstance());
            Log.i(TAG, "Draw delegates registered");

            // Verify registry contents
            Method getDrawDelegates = registryClass.getMethod("getDrawDelegates");
            @SuppressWarnings("unchecked")
            java.util.Map<?, ?> delegates = (java.util.Map<?, ?>) getDrawDelegates.invoke(null);
            Log.i(TAG, "Draw delegates map size: " + delegates.size() + " keys: " + delegates.keySet());
        } catch (Throwable e) {
            Log.w(TAG, "Failed to register draw delegates", e);
            callback.onProgress("Draw delegates registration skipped: " + e.getMessage());
        }

        // Register create delegates (normally loaded via Eclipse extension points)
        try {
            Class<?> registryClass = Class.forName("gama.api.additions.registries.GamaAdditionRegistry");
            Class<?> createDelegateIface = Class.forName("gama.api.additions.delegates.ICreateDelegate");
            Method addCreateDelegate = registryClass.getMethod("addDelegate", createDelegateIface);
            addCreateDelegate.invoke(null, Class.forName("gama.gaml.statements.create.CreateFromNullDelegate").getDeclaredConstructor().newInstance());
            addCreateDelegate.invoke(null, Class.forName("gama.gaml.statements.create.CreateFromCSVDelegate").getDeclaredConstructor().newInstance());
            addCreateDelegate.invoke(null, Class.forName("gama.gaml.statements.create.CreateFromGeometriesDelegate").getDeclaredConstructor().newInstance());
            addCreateDelegate.invoke(null, Class.forName("gama.gaml.statements.create.CreateFromGridFileDelegate").getDeclaredConstructor().newInstance());
            Log.i(TAG, "Create delegates registered (4)");

            Method getCreateDelegates = registryClass.getMethod("getCreateDelegates");
            @SuppressWarnings("unchecked")
            java.lang.Iterable<?> createDelegates = (java.lang.Iterable<?>) getCreateDelegates.invoke(null);
            int createCount = 0;
            for (Object ignored : createDelegates) { createCount++; }
            Log.i(TAG, "Create delegates count: " + createCount);
        } catch (Throwable e) {
            Log.w(TAG, "Failed to register create delegates", e);
            callback.onProgress("Create delegates registration skipped: " + e.getMessage());
        }

        // Register save delegates (normally loaded via Eclipse extension points)
        try {
            Class<?> registryClass = Class.forName("gama.api.additions.registries.GamaAdditionRegistry");
            Class<?> saveDelegateIface = Class.forName("gama.api.additions.delegates.ISaveDelegate");
            Method addSaveDelegate = registryClass.getMethod("addDelegate", saveDelegateIface);
            String[] saverClasses = {
                "gama.gaml.statements.save.ShapeSaver",
                "gama.gaml.statements.save.TextSaver",
                "gama.gaml.statements.save.CSVSaver",
                "gama.gaml.statements.save.JsonSaver",
                "gama.gaml.statements.save.ExcelSaver",
                "gama.gaml.statements.save.GeoJSonSaver",
                "gama.gaml.statements.save.KmlSaver",
                "gama.gaml.statements.save.ParquetSaver",
                "gama.gaml.statements.save.AvroSaver",
                "gama.gaml.statements.save.ASCSaver",
                "gama.gaml.statements.save.GeoTiffSaver"
            };
            int registered = 0;
            for (String clsName : saverClasses) {
                try {
                    Class<?> cls = Class.forName(clsName);
                    addSaveDelegate.invoke(null, cls.getDeclaredConstructor().newInstance());
                    registered++;
                } catch (Throwable e) {
                    Log.w(TAG, "Skipped save delegate " + clsName + ": " + e.getMessage());
                }
            }
            Log.i(TAG, "Save delegates registered (" + registered + "/" + saverClasses.length + ")");

            Method getSaveDelegates = registryClass.getMethod("getSaveDelegates");
            @SuppressWarnings("unchecked")
            java.util.Map<?, ?> saveDelegates = (java.util.Map<?, ?>) getSaveDelegates.invoke(null);
            Log.i(TAG, "Save delegates map size: " + saveDelegates.size() + " keys: " + saveDelegates.keySet());
        } catch (Throwable e) {
            Log.e(TAG, "Failed to register save delegates: " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            callback.onProgress("Save delegates registration skipped: " + e.getClass().getSimpleName() + ": " + e.getMessage());
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
