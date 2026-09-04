# GAMA Native Android - Session Handoff

**Date:** 2026-07-18
**Branch:** `native_app` (in `/Users/hqnghi/git/agama`)

---

## 1. Project Overview

GAMA is a simulation platform normally running on desktop (Java/Swing/Eclipse/OSG). We are porting it to run natively on Android as an app. The `native-app` folder contains the Android Gradle project (`com.gama.nativeapp`) that bootstraps GAMA's engine classes from JARs on the classpath without OSGi.

---

## 2. Build Configuration

- **Gradle:** 8.7, **AGP:** 8.5.2, **compileSdk:** 34, **targetSdk:** 34, **minSdk:** 26
- **Java 21** required for builds: `JAVA_HOME=$(/usr/libexec/java_home -v 21)`
- Default `java`/`javac` on PATH may be JDK 25 — **must use `--release 21`** in javac calls
- **Core library desugaring:** enabled (`com.android.tools:desugar_jdk_libs:2.0.4`)
- **JDK 21 class files** (class version 65) require AGP 8.4+

### Key Dependencies (`app/build.gradle`)
- All GAMA JARs from `app/libs/` (excluding `gama.extension.physics*`)
- Guice 5.1.0 patched (`app/libs/guice-5.1.0-patched.jar`)
- JTS 1.19.0, JGraphT 1.5.2, JFreeChart 1.5.4, Java-WebSocket 1.5.4
- Guava 33.2.1-jre, StreamEx 0.8.3, Commons Math 3.6.1
- Eclipse XText/Xtend 2.35.0, EMF 2.31.0
- AndroidX AppCompat 1.6.1, Material 1.10.0, ConstraintLayout 2.1.4

### External Dependencies
- **GeoTools JARs:** `/Users/hqnghi/git/agama/gama.dependencies/geotools/`
- **StreamEx:** `/Users/hqnghi/git/agama/gama.dependencies/streamex/streamex-0.8.3.jar`
- **JSR-385 Units:** `/Users/hqnghi/git/agama/gama.dependencies/jsr 363/`
- **Swing+beans stubs JAR:** `swing-beans-stubs.jar` (in app/libs/)
- **ECJ:** `/Users/hqnghi/.m2/repository/org/eclipse/jdt/ecj/3.44.0/ecj-3.44.0.jar`
- **ASM 9.6:** in Gradle cache (`org.ow2.asm:asm:9.6` and `org.ow2.asm:asm-tree:9.6`)
- **Device:** physical device via ADB at `~/Library/Android/sdk/platform-tools/adb`

---

## 3. Architecture & File Map

### Application Entry
- `GamaApplication.java` — Application subclass, disables `java.util.prefs` via system property + `NoOpPreferencesFactory`
- `GamaNativeBootstrap.java` — Core bootstrap: registers 25 plugin bundles, loads GAML language additions, initializes metamodel/types/compiler, registers draw/create delegates, registers GAML services (parser, info, ecore, builder, validator), registers GAML constants (331 entries: CSS colors, units), initializes `ForkJoinPool` + `ANDROID_PARALLEL_EXECUTOR`, registers Android GUI handler and `android2d` display type
- `AndroidManifest.xml` — `ModelNavigatorActivity` is LAUNCHER activity

### UI Activities
- `ModelNavigatorActivity.java` — **Launcher activity.** Tree navigator for library models (from `assets/gama.library.jar`) and user's models (from `assets/models/`). Tap → opens editor. Long-press → launches experiment directly (checks for saved edited file first).
- `ModelEditorActivity.java` — Syntax-highlighted GAML editor with dark theme, line numbers, toolbar (←, filename, Save, ▶ Run). Saves to `getFilesDir()/models/<name>.gaml`. Passes `file_path` extra to ExperimentActivity.
- `ExperimentActivity.java` — Compiles model, shows experiment selection buttons, runs experiment with play/pause/step/stop controls. Canvas display container + console. State polling with cycle count tracking every 1 second.

### Display System
- `AndroidDisplaySurface.java` — `View` implementing `IDisplaySurface`. Uses `Canvas` for 2D rendering. Has `setWillNotDraw(false)` (critical for `onDraw()` to fire). `updateDisplay()` calls `requestLayout()` + `invalidate()`. Manual agent drawing fallback when `layerManager.drawLayersOn()` draws nothing.
- `AndroidDisplayGraphics.java` — Extends `AbstractDisplayGraphics`. Converts GAMA geometry/shape/image/text drawing calls to Android `Canvas` operations. Uses JTS geometry → `Path` conversion for polygons, lines, points.
- `GamaAndroidDisplaySetup.java` — Registers `"android2d"` and `"2d"` display types in `IGui.DISPLAYS` and `GAML.CONSTANTS`.
- `AndroidGuiHandler.java` — `implements IGui`. Handles `createDisplaySurfaceFor()`, `showView()`, `arrangeExperimentViews()`, `run()`, console, dialogs. Creates `AndroidDisplaySurface` on UI thread with `CountDownLatch` synchronization.
- `AndroidGamaView.java` — Simple `IGamaView.Display` implementation.

### Tree Data
- `ModelTreeItem.java` — POJO: `name`, `fullPath`, `type` (CATEGORY/MODEL_FILE), `depth`, `parent`, `children`, `expanded`. Tree pruning flattens `models/` subdirectories and removes empty dirs.

---

## 4. ASM Patchers (in `tools/` directory)

All patchers use ASM 9.6 library. They are compiled and run during `patchGamaJars` Gradle task.

### ParallelRunnerPatcher.java
Patches `gama.core` for Android parallel execution:
- **GamaExecutorService:**
  - Adds `ANDROID_PARALLEL_EXECUTOR` field (`public static volatile ExecutorService`)
  - Patches `setConcurrencyLevel()` to initialize it via `Executors.newCachedThreadPool()` (with shutdown-before-reinit)
  - Patches `executeThreaded()` to submit to `ANDROID_PARALLEL_EXECUTOR` + `AndroidTaskWrapper.await()`
- **ParallelAgentRunner:**
  - `execute(ForkJoinTask)` → calls `task.invoke()` directly
  - `compute()` → uses `AndroidTaskWrapper` + `ANDROID_PARALLEL_EXECUTOR.submit()` instead of `ForkJoinTask.fork()/join()`
- Adds `AndroidTaskWrapper.class` to JAR
- Uses `ClassWriter.COMPUTE_MAXS`

### AndroidTaskWrapper.java
- `implements Callable<T>`, wraps `ParallelAgentRunner<T>`
- Static `await(Future)` method replaces `ForkJoinTask.join()` — handles `InterruptedException`/`ExecutionException`

### Display3DPatcher.java
- Patches `LayeredDisplayOutput.createSurface()` to remove3D early-return
- Replaces `is3D()` check with `POP` + `GOTO <skip>` so `createDisplaySurfaceFor()` is always called
- Uses `ClassWriter.COMPUTE_MAXS`
- **Note:** Pattern `is3D@-1 IFEQ@-1` may not be found after previous patches (the committed JAR may already have this patched from an earlier commit)

### SimulationRunnerPatcher.java
- ASM patches `SimulationRunner$1.run()` to always release `experimentSemaphore` in catch block (fixes deadlock)

### MicroPopInitPatcher.java
- Patches `CreateStatement.findPopulation()` for lazy micro-pop initialization

### PrecisePredicatePatcher.java
- Fixes D8 lambda bug: changes `Containers.by()`/`inContainer()` return type to `java.util.function.Predicate`
- Updates invokedynamic descriptors + SAM name from `apply` to `test`

### ColorsPatcher.java
- Patches `gt-brewer` `Colors.class` for static init circular dependency fix

### Abandoned Patchers (exist but not used)
- `GlobalPredicatePatcher.java` — abandoned
- `ContainersPredicatePatcher.java` — abandoned
- `TargetedPredicatePatcher.java` — abandoned

---

## 5. Build Task: patchGamaJars

The `patchGamaJars` task in `build.gradle` does:
1. Strips `SkillDescription.class` from GAMA JARs
2. Compiles `AndroidTaskWrapper.java` (with `--release 21`)
3. Compiles and runs `ParallelRunnerPatcher` on `gama.core_0.0.0.202605140230.jar`
4. Compiles and runs `Display3DPatcher` on same JAR

### Task Dependencies (CRITICAL)
```groovy
tasks.configureEach { task ->
    if (task.name.startsWith('merge') && task.name.endsWith('JniLibFolders')) {
        task.dependsOn 'patchGamaJars'
    }
    if (task.name == 'compileDebugJavaWithJavac') {
        task.dependsOn 'patchGamaJars'
    }
}
```

**IMPORTANT:** This ensures `patchGamaJars` runs BEFORE `dexBuilderDebug` (D8). Without this dependency, D8 processes the ORIGINAL (unpatched) JAR and the patched classes never end up in the APK. Previously (before this fix), the APK always contained original bytecode because the patcher ran after D8.

The task execution order is now:
```
patchGamaJars → compileDebugJavaWithJavac → ... → dexBuilderDebug → ... → packageDebug
```

### JAR Restore
The committed JAR at HEAD (`app/libs/gama.core_0.0.0.202605140230.jar`) may already contain patches from previous commits. Run `git checkout HEAD -- app/libs/gama.core_0.0.0.202605140230.jar` before building to ensure a clean base. The patcher should be idempotent (checks for existing fields/instructions before adding).

---

## 6. Known Issues & Current Blockers

### BLOCKER: BootstrapMethodError in GAML Compiler
```
BootstrapMethodError: Exception from call site #17 bootstrap method
  at gama.gaml.descriptions.SymbolDescription.compile(SymbolDescription.java:1407)
Caused by: ClassCastException: java.lang.Class cannot be cast to java.lang.Object
```

**Bootstrap method #17** in `SymbolDescription` is:
```
17: StringConcatFactory.makeConcatWithConstants recipe: "\u0001 is defined twice. Only one definition is allowed in \u0001"
```

This is a **D8 dexing issue** with Java 21's `StringConcatFactory`. D8 generates incorrect dex code for this bootstrap call site. The error manifests when compiling any GAML model (both `SimpleTest.gaml` and library models).

**Key finding:** This error ONLY appeared after fixing the task ordering. Before the fix, D8 processed the ORIGINAL unpatched JAR (patcher ran after D8). Now D8 processes the PATCHED JAR. The patcher's `COMPUTE_MAXS` rewrite of `ParallelAgentRunner` and `GamaExecutorService` may be producing class files that confuse D8's lambda/string desugaring for other classes in the same JAR.

**Potential solutions to try:**
1. Try `ClassWriter.COMPUTE_FRAMES` but override `getCommonSuperClass()` to use `Class.forName()` properly (not just returning `java/lang/Object`)
2. Instead of rewriting the whole JAR, use a separate approach: put patched classes in a separate JAR that's loaded after the original
3. Use R8 instead of D8 (might handle `StringConcatFactory` better)
4. Patch `SymbolDescription` class directly to replace `StringConcatFactory` invokeDynamic with `StringBuilder` concatenation
5. Pre-dex the GAMA JARs separately and exclude them from D8 processing
6. Try setting `android.enableD8.desugaring=false` and manually desugar only specific features

### gama.ui.display.java2d Fails (Expected)
1 plugin fails — requires `javax.swing.JPanel` (desktop-only Swing). This is expected and acceptable.

### Memory Low Error
GAMA's `RuntimeMemoryManager` reports 0MB available memory on Android. Execution continues but may need patching.

### GeoTools CRS Factory Chain
Fails with `RecursiveSearchException` on Android (non-fatal warnings).

---

## 7. GAMA Bootstrap Process (How It Works)

Since OSGi is unavailable, we manually simulate the GAMA plugin loading:

1. **Register 25 plugin bundles** — fake `Bundle` objects delegating to app classloader
2. **Load GAML language additions** — instantiate `gaml.additions.<plugin>.GamlAdditions` and call `initialize()` for each plugin (20/25 succeed, `java2d` fails)
3. **Initialize metamodel** — `GamaMetaModel.INSTANCE.build()`
4. **Initialize types** — `Types.init()`
5. **Set bundle loader** — `GamaBundleLoader.LOADED = true`
6. **Initialize dates** — `Dates.initialize()`
7. **Init ForkJoinPool** — `GamaExecutorService.reset()` (creates `ForkJoinPool` with parallelism=4, plus our `ANDROID_PARALLEL_EXECUTOR`)
8. **Register GUI handler** — `GAMA.setHeadlessGui(new AndroidGuiHandler())`
9. **Register android2d display** — `IGui.DISPLAYS.put("android2d", ...)` and `GAML.CONSTANTS.add("android2d")`
10. **Init GAML compiler** — `GamlStandaloneSetup.doSetup()` (XText injector)
11. **Register draw delegates** — `ShapeDrawer`, `TextDrawer`, `AssetDrawer`, `AspectDrawer`
12. **Register create delegates** — `CreateFromNullDelegate`, `CreateFromCSVDelegate`, `CreateFromGeometriesDelegate`, `CreateFromGridFileDelegate`
13. **Register GAML services** — parser provider, info provider, ecore utils, model builder, text validator
14. **Register GAML constants** — `CoreConstantsSupplier.supplyConstantsTo(GAML.getConstantAcceptor())` → 331 entries (CSS colors, units)

---

## 8. GAMA Controller Execution Flow

Understanding how experiments run (critical for debugging):

- **`DefaultExperimentController`** constructor starts `executionThread` (runs `step()` loop) and `commandThread` (processes commands)
- Constructor acquires `lock` semaphore (permits: 1→0)
- `paused` defaults to `true`
- `step()` blocks on `lock.acquire()` while `paused=true`
- **`_START` command:** `paused=false`, `lock.release()`
- Execution thread loop: `while (experimentAlive) { step(); }`
- `step()`: if `paused` → `lock.acquire()`; then if `scope==null` → release `previouslock` and return; else call `scope.step(agent)`. If result `!passed()` → `paused=true` (model finished or error)
- **`processStart(false)`** is async: `asynchronousStart()` → offers `_START` to command queue
- **`processPause(false)`** is async: `asynchronousPause()` → offers `_PAUSE` to command queue (but `_PAUSE` handler only sets `experimentState=NONE`, does NOT set `paused=true`)

### Play/Pause/Step in ExperimentActivity
- **Play/Pause:** Reflectively sets `paused` field on `AbstractExperimentController` superclass + releases `lock` semaphore when unpausing
- **Step:** Sets `paused=false` + releases `lock` (one-shot step, execution will re-pause after one cycle)
- **Stop:** Calls `IExperimentController.close()`

### SimulationRunner Two-Semaphore Pattern
- `simulationsSemaphore` and `experimentSemaphore` both `Semaphore(1, true)` with `withInitialPermits(0)`
- ASM-patched to always release `experimentSemaphore` in catch block
- `SimulationRunner.step()` releases `simulationsSemaphore(activeThreads)` then acquires `experimentSemaphore(activeThreads)`
- `$1.run()` first does `experimentSemaphore.release()` (initial permit), then loops

---

## 9. Key Bugs Fixed in Previous Sessions

| Bug | Fix |
|-----|-----|
| `java.util.prefs` lock loop | `System.setProperty` in `GamaApplication` + `NoOpPreferencesFactory` |
| `IGui` static init crash | `DISPLAYS = new LinkedHashMap<>()` |
| Guice `IncompatibleClassChangeError` | ASM-patched Guice 5.1.0 JAR |
| `GamaColor.colors` static init | ASM-patched `gt-brewer` `Colors.class` |
| `DrawStatement.canDraw()` always false | ASM patch to return `true` |
| `CreateStatement.findPopulation()` NPE | `MicroPopInitPatcher` — lazy micro-pop init |
| `SimulationRunner$1` deadlock | `SimulationRunnerPatcher` — always release semaphore in catch |
| `ForkJoinPool.invoke()` broken on Android | `ParallelRunnerPatcher` — `ANDROID_PARALLEL_EXECUTOR` (regular `ExecutorService`) |
| `ParallelAgentRunner.join()` broken | `AndroidTaskWrapper.await()` — `Future.get()` replacement |
| `Containers` D8 lambda bug | `PrecisePredicatePatcher` — changes SAM name from `apply` to `test` |
| 3D display skipped on Android | `Display3DPatcher` — removes `is3D()` early return |
| GAML constants not registered | `CoreConstantsSupplier.supplyConstantsTo()` in bootstrap |
| Grid models failed: `#white` / `element 'white' cannot be resolved` | Constants were registered AFTER `BuiltinGlobalScopeProvider.initialize()` (called by `GamlStandaloneSetup.initializeAfterPlatformReady()`), so the built-in scope snapshot lacked colors/units. Fixed by moving event-delegate + constants registration BEFORE the XText init block in `GamaNativeBootstrap.java` (verified 2026-08-10) |
| `setWillNotDraw(false)` missing | Added in `AndroidDisplaySurface` constructor |
| Ant Foraging `NoClassDefFoundError` (`renderer.output.Output`) | jsvg stubs in `app/src/main/java/com/github/weisj/` had wrong packages (`renderer.Output` vs `renderer.output.Output`). Fixed (2026-08-10): deleted the stubs, bundled real `jsvg-2.0.0.jar` + StAX stack (`stax-api-1.0-2`, `stax2-api-4.2.2`, `woodstox-core-7.1.1`) in `app/libs`, and patched jsvg's `StaxSVGLoader` `newFactory()`->`newInstance()` via `tools/StaxNewFactoryPatcher.java`. NOTE: after adding jars to `app/libs`, `rm -rf app/build/intermediates/dex` or AGP's dex task stays up-to-date and new classes never reach the APK (only their resources get packaged) |
| jsvg needs `java.awt.MultipleGradientPaint$CycleMethod` | Added stub `app/src/main/java/java/awt/MultipleGradientPaint.java` (abstract, nested enums `CycleMethod {NO_CYCLE,REFLECT,REPEAT}` and `ColorSpaceType {SRGB,LINEAR_RGB}`); jsvg's `SpreadMethod` enum maps to it at `<clinit>` time |
| `AffineTransform` float ctor / `setTransform` missing | Added `AffineTransform(float...x6)` ctor and `setTransform(AffineTransform)` to the awt stub |
| `java.awt.geom.Path2D` absent (jsvg core geometry) | New real `Path2D` impl in `app/src/main/java/java/awt/geom/Path2D.java` (Float+Double, segment storage, working PathIterator, `getCurrentPoint()`, `setWindingRule`, `append`, bounds) |
| `java.awt.geom.Area` absent (gama SVG clipping) | New `Area` impl wrapping a Shape with bounds-based `add`/`intersect` (approximate) + `isRectangular()`; `RectangularShape.getPathIterator` was `null` -> now emits real rect outline |
| **BUG:** `PathIterator` constants were WRONG (`SEG_MOVETO=1...` instead of real `0..4`) | Any external consumer (JTS `ShapeReader`) does raw switch on segment ints; wrong values caused NPE. Fixed to canonical `SEG_MOVETO=0..SEG_CLOSE=4` |
| `as_matrix(image_file, dims)` returned all zeros -> Ant Foraging `Division by zero` at `(grid_values-min)/range` | `ImageHelper.resize`/`matrixValueFromImage` draw into a `BufferedImage` via `Graphics2D.drawImage`, but (a) `CanvasGraphics2D.drawImage` was a no-op and (b) `BufferedImage.getRGB` read the `data[]` array while drawing went to the android `Bitmap` (never synced). Fixed: real Bitmap-based `drawImage` (scaled + AffineTransform variants) in `CanvasGraphics2D`, and `BufferedImage.getRGB` now reads `androidBitmap.getPixel` when present |
| Boids `BootstrapMethodError: Exception from call site #0 bootstrap method` (root: `ClassCastException: String cannot be cast to Object`) at `AbstractTopology.accept` (via `overlapping` -> `getAgentsIn`) | JDK 17+/25 `javac` emits `invokedynamic java/lang/runtime/SwitchBootstraps.enumSwitch` for any enum `switch` containing `case null:` (GAMA's `accept()` has `case null: default:`). `SwitchBootstraps` does not exist on ART, and D8 (debug, no R8) does NOT desugar it (lambdas are desugared, this isn't). A hand-written `app/libs/switch-bootstraps-stub.jar` cannot work (ART ignores app-defined `java.*`). **Fix (2026-08-10): `tools/EnumSwitchPatcher.java`** — ASM tree patcher (same shape as `TypeSwitchPatcher`) that replaces each `enumSwitch` indy with an unrolled `ldc <name>; aload sel; Enum.name(); String.equals(); ifne caseLabel` chain + null->no-match jump, preserving the surrounding `tableswitch` and re-targeting loop-back gotos. Semantics match the JDK: match by name -> case index, else -1. Wired into `patchGamaJars` for ALL toolchain jars (`toolchainJars` list). Also **extended `TypeSwitchPatcher` to the same full `toolchainJars` list** (it was missing `gama.workspace`, which contained an unpatched `typeSwitch` in `FileMetaDataProvider`). Boids "Basic" + Ant Foraging "Classic" + Life2 verified with 0 runtime errors. NOTE: `switch-bootstraps-stub.jar` is dead weight now (harmless, ineffective) — can be removed from `app/libs` |

| `requestLayout()+invalidate()` missing | Added in `updateDisplay()` |
| Patchers run after D8 (not in APK) | Added `compileDebugJavaWithJavac` dependency on `patchGamaJars` |

---

## 10. What Was Working Before This Session's Last Change

Before the task-order fix (`patchGamaJars` before D8):
- Bootstrap: 20/25 plugins loaded
- GAML compiler initialized successfully
- Canvas drawing worked: agents rendered, `shapesCount=40`, `drewShapes=true`
- SimpleTest: 20 agents created, canvas drew them
- Life.gaml: reached cycle 13+
- Execution thread was stepping correctly
- All draw/create delegates registered
- GAML constants (331 entries) registered

**The APK worked because D8 processed the ORIGINAL (unpatched) JAR.** The patcher ran after D8 and modified the JAR on disk, but these changes were never included in the APK. The bootstrap code manually created ForkJoinPool via `GamaExecutorService.reset()`, and the `ANDROID_PARALLEL_EXECUTOR` field was only present at runtime if the patcher had run before D8.

---

## 11. Immediate Next Steps

1. **Grid model compilation RESOLVED** (2026-08-10) — constants now registered before XText init. Verified: `g5_freq`, `m_grid_bare`, `m_diffuse`, `m_grid_fixed`, and the flagship `Life.gaml` (Game of Life) all compile. Life runs end-to-end: experiment starts, grid steps, `AndroidDisplaySurface` invalidates every cycle, screenshot shows rendered cells.
2. **m_grid's errors were model bugs, not engine bugs** — `center` is a user-declared global in the real Ant model (`point center ... const: true <- {...}`); the test model omitted it. `distance_between [self, center]` failed only downstream of the unknown `center`. The `color` redefinition from skill `grid` is INFO-level (override is legal).
3. **Regression-test remaining grid models** on device: `g1`–`g5`, `m_diffuse`, plus other library models that previously failed on `#white`.
4. **Cleanup:** stale `/data/data/com.gama.nativeapp/files/tests/Life.gaml` (owned by u0_a0, 5252 bytes) blocks overwrite; workaround = use a fresh filename (`Life2.gaml`) or clear via device settings.
5. **Cosmetic:** initial `updateDisplays: surface is not a View: null` probe warning self-heals into `invalidating AndroidDisplaySurface` — safe to silence.

---

## 12. Quick Build & Deploy Commands

```bash
# Set Java 21
export JAVA_HOME=$(/usr/libexec/java_home -v 21)

# Navigate to project
cd /Users/hqnghi/git/agama/native-app

# Restore clean JAR from git (before patching)
git checkout HEAD -- app/libs/gama.core_0.0.0.202605140230.jar

# Full clean build
./gradlew clean assembleDebug

# Install on device
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk

# Launch
~/Library/Android/sdk/platform-tools/adb shell am start -n com.gama.nativeapp/.ModelNavigatorActivity

# View logs
~/Library/Android/sdk/platform-tools/adb logcat -d | grep -E "GamaNative|Experiment|AndroidDisplay"

# Force stop
~/Library/Android/sdk/platform-tools/adb shell am force-stop com.gama.nativeapp
```

---

## 13. Project File Structure

```
native-app/
├── app/
│   ├── build.gradle                          # Build config + patchGamaJars task
│   ├── libs/
│   │   ├── gama.core_0.0.0.202605140230.jar # Main GAMA core JAR (patched at build time)
│   │   ├── gama.library_0.0.0.202605140230.jar # Library models JAR
│   │   ├── guice-5.1.0-patched.jar          # ASM-patched Guice
│   │   ├── swing-beans-stubs.jar             # Stubs for java.awt.*
│   │   └── [other gama.*.jar files]
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── assets/
│       │   ├── gama.library.jar              # Copy of library JAR for runtime access
│       │   └── models/
│       │       └── SimpleTest.gaml           # Test model
│       └── java/com/gama/nativeapp/
│           ├── GamaApplication.java
│           ├── GamaNativeBootstrap.java
│           ├── ModelNavigatorActivity.java
│           ├── ModelEditorActivity.java
│           ├── ModelTreeItem.java
│           ├── ExperimentActivity.java
│           ├── NoOpPreferencesFactory.java
│           ├── MainActivity.java
│           ├── display/
│           │   ├── AndroidDisplaySurface.java
│           │   ├── AndroidDisplayGraphics.java
│           │   └── GamaAndroidDisplaySetup.java
│           └── gui/
│               ├── AndroidGuiHandler.java
│               └── AndroidGamaView.java
├── tools/
│   ├── ParallelRunnerPatcher.java
│   ├── AndroidTaskWrapper.java
│   ├── Display3DPatcher.java
│   ├── SimulationRunnerPatcher.java
│   ├── MicroPopInitPatcher.java
│   ├── PrecisePredicatePatcher.java
│   ├── ColorsPatcher.java
│   ├── GlobalPredicatePatcher.java           # Abandoned
│   ├── ContainersPredicatePatcher.java       # Abandoned
│   └── TargetedPredicatePatcher.java         # Abandoned
└── HANDOFF.md                                # This file
```

---

## Session 2 (2026-08-11) — Adaptation to new GAMA jars `202608091252`

Repo moved to `/Users/hqnghi/git/gama-android`. All GAMA jars replaced with the new
mainline build `0.0.0.202608091252` (from `/Users/hqnghi/git/gama/gama.product/target/repository/plugins/`).
Everything that ran on the old jars (`202605140230`) must still run — verified by full-library regression.

### Regression harness
- `scripts/regression.sh` — 6 asset models + `--lib` subset; `scripts/discover_library_experiments.py`
  extracts all `(jar_path, experiment)` pairs (inline `experiment X` + `import "…" as` resolution from
  sibling `.experiment` files) → **142 experiments**; `scripts/sweep_library.sh` drives the background
  sweep (~40s/model, ~1.5-2h), logs to `/var/folders/.../opencode/sweep/`.
- Key zsh pitfalls fixed in the harness: `status` is read-only; `path` is tied to `PATH`; `ACT` must be
  `com.gama.nativeapp/.ExperimentActivity` (with `/`); asset models need `--es file_path` (else library
  fallback → "Entry not found"); `adb shell` joins args with spaces so **each arg is single-quoted**
  (spaces in "Traffic and Pollution.gaml" otherwise truncate); `--es key value` must be separate argv
  elements each quoted; backgrounded subshells inherit stdin and steal `while read` input (use fd 3 +
  `< /dev/null`).

### New-gamma adaptation fixes (this session)
1. **`gama.extension.androidsensor` no longer exists** in mainline. Ported to source against the new API:
   - `app/src/main/java/gama/extension/androidsensor/AndroidSensorSkill.java` — extends
     `gama.api.kernel.skill.Skill`; `@skill(name="android_sensor", doc={@doc(…)})` is **mandatory**
     (missing → NPE `SkillDescription.getDocAnnotation`); reads `AndroidSensorBridge.getSnapshot()`.
   - `app/src/main/java/gaml/additions/androidsensor/GamlAdditions.java` — extends
     `gama.api.additions.AbstractGamlAdditions`; 18 `_var` + 3 `_action`; keyword constants from
     `gama.annotations.constants.IKeyword`, T/F/SC/AS/O/B from `UtilsForGamlAdditions`.
   - `AndroidSensorBridge.java` (existing) unchanged.
2. **3D light color**: new `GamaColor` is a `Record` with `getAWTColor()`/`internalColor()`, no
   `getRed/getGreen/getBlue/getAlpha`. `AndroidDisplayGraphics.java` now uses `colorToARGB()` helper.
3. **Math/ODE solver constants not registered** (SIR (ABM vs EBM) failed with
   `rk4 is not a unit or constant name`): `GamaNativeBootstrap.java` fetched the `supplyConstantsTo`
   method from `CoreConstantsSupplier.class`, so invoking it on `MathConstantSupplier` threw
   `IllegalArgumentException` (receiver mismatch) that was swallowed. Fixed by fetching the method from
   the `gama.api.additions.delegates.IConstantsSupplier` interface — math constants now register
   (364 total constants) and `#rk4`-using models compile.

### Sweep results (142 experiments, fixed build)
- **135 PASS** (includes SIR (ABM vs EBM) after the constants fix), all user-named categories green
  (Traffic and Pollution, Evacuation, Flooding comodels, etc.).
- **6 pre-existing Android limitations** (also broken on the old jars — documented, not regressions):
  - Save to ASC / KML / PNG (Data Exportation), Image vectorization (shp export): no file-format
    providers registered on Android → "Unknown file extension. Accepted formats are: []".
  - Voronoi (Art), Waterflow Field Elevation: `ColorBrewer` loads palette XMLs via
    `Class.getResource()` which fails on Android; `Colors.BREWER` stays null (ColorsPatcher fail-softs).
- **2 discovery artifacts**: `hydrocomodel`/`movecomodel` come from `import "Experiment_comodel/…"
  as …` — they are micro-model agent species, not runnable experiments of the importing file.

### Bootstrap constants flow (for future jar upgrades)
`CoreConstantsSupplier`, `MathConstantSupplier`, `ImageConstantSupplier` supply GAML constants via
`IConstantsSupplier.supplyConstantsTo(IConstantAcceptor)`. Desktop discovers them through the Eclipse
`gama.constants` extension point; the Android app registers them explicitly in `GamaNativeBootstrap`
(before XText init) using `GAML.getConstantAcceptor()`. The method must always be fetched from the
interface, not from a concrete supplier class.

---

## Session 3 (2026-08-11) — Align GeoTools stack to 33.4 + fix GIS runtime errors

Traffic (and GIS) models crashed at runtime with the 202608091252 jars. The GeoTools jars in `app/libs/`
were a mixed bag (gt-main/gt-shapefile 33.4 but gt-referencing 25.0 + jsr-363 units 2.0.1). Fixed by
matching the desktop `gama.dependencies` bundle exactly:

### GeoTools 33.4 alignment (`app/libs/`)
- `gt-referencing-25.0.jar` → `gt-referencing-33.4.jar` (from `/Users/hqnghi/git/gama/gama.dependencies/libs/geotools 33.4/`).
- JSR-363 units upgraded to the desktop set: `unit-api-2.2.jar`, `indriya-2.2.jar`,
  `si-units-2.1.jar`, `si-quantity-2.1.jar`, `uom-lib-common-2.2.jar`.
- **`systems-common-2.1.jar` added** (also in `geotools 33.4/`) — provides
  `systems.uom.common.USCustomary` (FOOT/YARD/MILE…), which gt-metadata-33.4's `PrefixDefinitions`
  references. Without it: `NoSuchFieldError: YARD`.
- Old versions removed: `indriya-2.1.2`, `si-units-2.0.1`, `si-quantity-2.0.1`, `unit-api-2.1.2`,
  `uom-lib-common-2.1`.

### Patchers (`app/build.gradle` + `patchers/`)
- `SpiPatcher` args updated to `gt-referencing-33.4.jar` — 33.4's SPI files are under
  `META-INF/services/org.geotools.api.referencing.*` (the 25.0-era paths `org.geotools.referencing.*`
  no longer exist). Patches the `LongitudeFirst*` factories out of the SPI files.
- `MapProjectionPatcher` args updated to the same 33.4 jar (patches `transform([DI[DII)V` /
  `transform([FI[FII)V` in `MathTransformProxy`).

### Shadowing stub removed
- `app/src/main/java/systems/uom/common/USCustomary.java` was an early shim that shadowed the real
  class in the project dex (classes11.dex) and **stripped it to 5 fields** — hiding the full version
  from `systems-common-2.1.jar` in classes13.dex → `NoSuchFieldError: YARD`. Deleted; the real class
  now loads. Lesson: stub classes in `app/src/main/java` can shadow lib classes in an earlier dex.

### Display scope fix (`AndroidDisplaySurface.java`)
- The display scope was being re-bound to the **simulation** agent on first frames
  (`GridLayerData.compute()` needs `getPopulationFor()`). Desktop keeps the **experiment** agent
  (`output.getScope().copyForGraphics(...)`), and `getPopulationFor` walks the host chain. The
  simulation-agent override broke overlay aspect scoping: `loop over: pollutions.pairs` (experiment
  variable) resolved to nil → NPE. Reverted to the experiment-agent scope; grid layers still work.

### Verified on device
- **Traffic and Pollution** (Traffic and Pollution.gaml / experiment `traffic`): compiles, starts,
  road/building shapefiles load via GeoTools 33.4, overlay legend renders, sim steps every ~1s.
- **Segregation (GIS)** (nha2.shp + nha2.prj real CRS): compiles, starts, renders, steps, no errors.

### Procedural City blank display fix (EFS stubs)
- Symptom: blank display; `NoSuchMethodError: IFileSystem.getStore(IPath)` per building.
- Chain: `draw shape texture:[...]` → `ShapeDrawer.addTextures` (String branch) →
  `GamaFileType.createFile` → `GamaImageFile.<init>` → `FileUtils.constructAbsoluteFilePath`
  → `findOutsideWorkspace` → `EFS.getLocalFileSystem().getStore(new Path(fp))`.
- The `org.eclipse.core.filesystem` stubs were empty interfaces returning null; the gama.api jar
  is compiled against the real Eclipse `IFileSystem.getStore(IPath)`, so ART's verifier threw
  NoSuchMethodError on every textured draw. (Shapefiles worked because relative paths skip this.)
- Fix: implemented the stubs backed by `java.io.File`:
  - `IFileSystem` — added `getStore(IPath)`, `getStore(URI)`, `attributes()`, `canDelete()`, `canWrite()`.
  - `EFS.getLocalFileSystem()` → `LocalFileSystem.INSTANCE`.
  - New `LocalFileSystem`, `LocalFileStore`, `LocalFileInfo` (real file I/O; `fetchInfo().exists()`
    reflects the on-device cache where the library jar project is extracted).
  - `findOutsideWorkspace` then resolves via real `exists()`; `createLinkToExternalFile` returns
    null on Android → absolute path used as-is.
- Verified: Procedural City renders textured buildings (screenshot ~1750 distinct colors);
  Traffic still passes (no regression).

### Evacuation Phuc Xa blank display fix (ColorBrewer + commons-collections4)
- Symptom: blank display; `NullPointerException: ColorBrewer.hasPalette` on a null object at
  `Colors.brewerPaletteColors` during sim init (`brewer_colors(qualitativePalette, N)` in the
  model globals). This was previously documented as a "pre-existing Android limitation" — it is
  now FIXED.
- Root cause: `ColorBrewer.load()` reads the palette XMLs with the DOM idiom
  `node.getFirstChild().toString()`. On the JVM, DOM text nodes override `toString()` to return
  their text; Android's Harmony DOM (`org.apache.harmony.xml.dom.TextImpl`) does NOT, so the
  parser saw `"org.apache.harmony.xml.dom.TextImpl@..."` and threw `NumberFormatException`
  (sample size parse). `ColorBrewer.instance()` then threw, and `ColorsPatcher`'s try-catch left
  `Colors.BREWER = null` → every `brewer_colors(...)`/`palette(...)` NPE'd.
- Fix: `tools/ColorBrewerPatcher.java` (new, wired into `patchGamaJars` for `gt-brewer-33.4.jar`):
  rewrites the 7 `invokevirtual Object.toString()` calls in `ColorBrewer.load` (each preceded by
  `invokeinterface Node.getFirstChild`) to `invokeinterface Node.getNodeValue()` — identical stack
  effect, real text on Android, equivalent on JVM. Must skip `LineNumberNode`/`LabelNode`/`FrameNode`
  when finding the preceding instruction, and capture `getNext()` before `InsnList.set()` (set
  detaches the node). Idempotent (a second run finds no remaining `toString` pairs).
- Also added `app/libs/org.apache.commons.commons-collections4_4.5.0.jar` (from
  `/Users/hqnghi/git/gama/gama.product/target/repository/plugins/`) + pristine copy:
  `gama.extension.traffic`'s `RoadSkill` (the `driving` skill) extends
  `commons.collections4.bidimap.DualTreeBidiMap` → NoClassDefFoundError otherwise.
- Verified on device: **Evacuation Phuc Xa** compiles, starts, renders roads (lightgray), buildings,
  green evacuation points and red vehicles, animates, and pauses at `time > 15000` (model's own
  `end_sim`). **Traffic and Pollution** re-tested: no regression. **Color Brewer.gaml** recipe
  (`BrewerPalette` experiment) compiles/starts clean — `brewer_colors` now fully functional.

### Waterflow Field Elevation fix (RenderingHints + ImageIO stub + GridFileFallbackPatcher)
- Symptom: `NullPointerException: IProjection.getProjectedEnvelope()` on null during sim init
  (`geometry shape <- envelope(dem_file)` for `DEM_100m_PP.asc`). Previously documented as a
  ColorBrewer limitation — it is now FIXED.
- Chain: `GamaGridFile.computeEnvelope` (`if (gis == null) createCoverage(scope); return
  gis.getProjectedEnvelope();`) → `createCoverage` → `privateCreateCoverage` →
  `new ArcGridReader(fis, hints)`. `gis` (`GamaGisFile.public IProjection gis`) is only set by
  `computeProjection` inside `privateCreateCoverage`; when the ArcGridReader path failed, `gis`
  stayed null and the constructor-era exception was swallowed (`System.out.println("On est ici: ...")`).
- Fix 1 — `app/src/main/java/java/awt/RenderingHints.java`: added `implements Cloneable` + `clone()`.
  Fixed `CloneNotSupportedException: Class org.geotools.util.factory.Hints doesn't implement
  Cloneable` (geotools `Hints extends java.awt.RenderingHints`; real `RenderingHints` implements
  `Cloneable`; `AbstractGridCoverage2DReader` calls `Hints.clone()` → `super.clone()`).
- Fix 2 — `app/src/main/java/javax/imageio/ImageIO.java`: added `getUseCache()`, `setUseCache()`,
  `getCacheDirectory()`, `createImageOutputStream()`, `getImageReaders()`. Fixed
  `NoSuchMethodError: No static method getUseCache()Z in class javax/imageio/ImageIO` (the stub
  shadowed the JDK class but lacked the cache API called by `ArcGridReader`, `ImageIOExt`,
  `MaskOverviewProvider`). Also added `stream/ImageOutputStream.java` +
  `FileImageOutputStream.java` (minimal write-side counterparts).
- Fix 3 — `tools/GridFileFallbackPatcher.java` (new, wired into `patchGamaJars` for
  `gama.core_*.jar`): in `GamaGridFile.privateCreateCoverage`, widens `catch (Exception e)`
  to `catch (Throwable e)` and rethrows as `RuntimeException`. This routes ALL grid-read failures
  (Errors included, which the old `catch(Exception)` swallowed or let escape) to `createCoverage`'s
  existing `customAscReader` fallback, which fully populates `gis`/`ascData`/`numRows`/`numCols`.
  ASM details: update the handler's StackMapTable `FrameNode` (stack top + local slot 4 → Throwable)
  or the device verifier rejects the widened catch; insert `new RuntimeException, dup, aload, <init>(Throwable), athrow`
  immediately after the handler's `astore` (rest of handler becomes dead code, harmless).
- Verified on device: **Waterflow Field Elevation** compiles (`WaterOnFields_model`), starts, opens
  the `d` 3D display on `OpenGLDisplayView` (3D display type preserved), renders the terrain mesh
  (`palette(#burlywood..#green)`) and animates the `flow` mesh (Blues palette) — no errors, no
  `"On est ici"`, no NPE. Regressions: **Evacuation Phuc Xa** and **Traffic and Pollution** still
  compile/render with no errors.

---

## Session 4 (2026-08-14) — `json_file(URL)` works on Android

`json_file("https://...")` crashed with `NoClassDefFoundError: org.json.simple.JSONObject` at
`gama.dependencies.webb.Webb._execute`. Three separate problems were fixed:

### 1. Missing `json-simple-1.1.1.jar` (root cause of the NoClassDefFoundError)
- `Webb._execute()` runs `body instanceof JSONObject` / `JSONArray` for every request (even GETs),
  so the `org.json.simple` classes must be on the runtime classpath.
- Desktop GAMA ships it inside the geotools bundle; the app bundled the `gt-*.jar` files but not
  json-simple. Added `app/libs/json-simple-1.1.1.jar` (copied from
  `/Users/hqnghi/git/gama/gama.dependencies/libs/geotools 33.4/`). Only `gama.dependencies` (the
  webb package) references `org.json.simple`, so this single jar covers the whole fetch path.
  Needs no patching → not added to `libs/pristine/` (same as the other geotools jars).

### 2. Missing `INTERNET` permission
- `AndroidManifest.xml` had no `android.permission.INTERNET` at all. Added it. Without it the fetch
  fails at DNS/socket time regardless of jars.

### 3. Workspace root + JSON encoder not initialized on Android
- `WorkspaceRoot.getLocation()` (generated by `EclipseCorePatcher` into `eclipse-core-stubs.jar`)
  hardcoded `new Path("/")`, so `FileUtils.getCache()` → `/.cache` (read-only) and downloads failed
  with `FileSystemException: /.cache: Read-only file system`.
  - `tools/EclipseCorePatcher.java`: `createWorkspaceRoot()` now emits `getLocation()` returning
    `new Path(WorkspaceManager.engineWorkspacePath())` (and `getLocationURI()` = that file's URI),
    and `addNewClasses()` always regenerates `WorkspaceRoot.class` (skipped during the copy loop) so
    the stale hardcoded version is replaced every build.
  - `WorkspaceManager.java`: added `setEngineWorkspacePath()` / `engineWorkspacePath()` (falls back
    to the default app-private workspace via `GamaApplication.getAppContext()`).
  - `GamaApplication.java`: stores `appContext` in `onCreate()`.
  - `GamaNativeBootstrap.java`: calls `WorkspaceManager.setEngineWorkspacePath(workspaceRoot(...))`
    at the top of `initialize()`, and registers the JSON encoder (`gama.core.util.json.Json.getNew()`
    → `GAMA.setJsonEncoder(...)`, normally done by `CoreActivator.initialize()` which is not run on
    Android). Without the encoder, `GamaJsonFile.fillBuffer` crashed on a null `IJson`.

### Verified on device
- `tools/JsonUrlTest.gaml` = `json_file("https://httpbin.org/json")` then `write jf.contents`.
  Prints the parsed map (`slideshow` → author/date/slides/title) on the console. Plain and
  query-string URLs both work. Download cache lands in
  `files/workspace/.cache/httpbin.org+_++_+json` (desktop-GAMA-compatible layout).
- Note: the emulator occasionally loses its virtual WiFi (`eth0` DOWN / `wlan0` NO-CARRIER);
  `adb shell 'cmd wifi set-wifi-enabled enabled'` (or `svc wifi enable`) restores it.

## Session 5 (2026-08-14) — Scale1 `isTimeDependent` NPE fix

`Scale1.gaml` (`exp2`/`estim`) crashed during model validation with
`NullPointerException: gama.api.gaml.expressions.IExpression.isTimeDependent() on a null object reference`.

### Root cause (engine bug, not a model bug)
- `global { float step <- 1 #s; }` compiles the `<-` into an `init` facet.
- `AttributeDeclaration$VarValidator.validate()` (gama.api, JDK 25 build)
  does `final IExpression expr = cd.getFacetExpr(INIT); if (expr.isTimeDependent()) { ... }`
  with **no null check**. When the `init` facet expression is null (facet not compiled / failed
  compile), it NPEs.
- Upstream desktop rarely hits this because facet expressions almost always compile; on Android
  the `step <- 1 #s` init was occasionally left uncompiled, so the validator dereferenced null.

### Fix — `tools/VarValidatorNullGuardPatcher.java`
- ASM tree patcher (same shape as `GamlModelBuilderPatcher`) on
  `gama/api/gaml/variables/AttributeDeclaration$VarValidator.class`.
- Rewrites the `isTimeDependent()` call site from
  `ALOAD x; INVOKEINTERFACE isTimeDependent` to
  `ALOAD x; IFNULL <same-target>; ALOAD x; INVOKEINTERFACE isTimeDependent`,
  i.e. `if (expr != null && expr.isTimeDependent())`.
- **Frame-safety trap:** the first attempt introduced a *new* `IFNULL` branch target, so D8
  crashed with `ArrayIndexOutOfBoundsException: Index -1 out of bounds` (missing stack-map frame
  at the new target). Fixed by jumping the `IFNULL` to the *existing* `IFEQ` label — no new
  branch targets, so the original frames stay valid under `COMPUTE_MAXS`. (`COMPUTE_FRAMES` is
  NOT usable here: ASM 9.6 throws `NegativeArraySizeException` on this JDK-25 class.)
- Wired into `patchGamaJars` as `['VarValidatorNullGuardPatcher', ..., [gamaApiJar]]`.

### Reproducibility note (why standalone patcher tests "fail")
- `patchGamaJars` **restores `app/libs/*.jar` from `app/libs/pristine/` first**, then
  **downgrades class file major >65 → 65** (JDK-25 jars → readable by ASM 9.6 + AGP 8.5.2 D8),
  then runs the ASM patchers. The pristine jars are major 69, so running a patcher standalone on
  them fails with `Unsupported class file major version 69` — test on a post-downgrade copy.

### Verified on device
- `Scale1.gaml` (experiment `exp2`) now compiles, builds the world, and runs cycles
  (`updateDisplays: 1 output(s)` every second), zero runtime errors.
- For a full run the model's data dirs must be on the device (`files/includes/` 156 MB GIS,
  `files/images/` textures) — pushed via `adb push` + `run-as tar -xzf` (plain `run-as cp -r`
  of a whole directory tree hits a permission error; a tarball works).
- Remaining model-side messages are not errors: `File denoted by ... not found` (data not yet
  pushed) and `DIAG: ant population not found` (a model diagnostic for an absent "ant" species).

## Session 6 (2026-08-14) — URL Image Import: two-layer WebbException fix

`URL Image Import.gaml` (`image_file("https://...")` + `save shuffled_copy`) threw `WebbException`.
Two independent problems, fixed in order:

### 1. Emulator DNS broken (root `WebbException: UnknownHostException`)
- The emulator had been up 40+ h since before the Mac's DNS moved behind an IPv6 VPN
  (`2001:ee0:26::26`), so SLIRP's DNS forwarder (`10.0.2.3`) could not resolve anything
  (`EAI_NODATA`/`ETIMEDOUT`); raw IP still worked.
- Android-side workarounds (restart `netd`, `svc wifi`, private DNS, `ndc resolver`, hosts
  bind-mount) all failed — `/` is read-only and the resolver is managed by ConnectivityService.
- **Fix:** relaunched the emulator with a pinned public DNS:
  `emulator -avd Medium_Phone_2 -dns-server 8.8.8.8 -no-snapshot-load` (same flags as before).
  App data and pushed models persisted (userdata not wiped). DNS then resolved
  (`doQuery: rcode=0`), and the 404/HTTP responses from the server proved the download path works.
- Also note: the model's bundled URL `.../wiki/gama-platform/gama/resources/images/general/GamaPlatform.png`
  is dead (HTTP 404, file removed upstream). The model works with any live image URL
  (tested with `.../master/gama.library/gama.feature.library.png`).

### 2. Missing `ImageIO.write(RenderedImage, String, File)` (root `NoSuchMethodError`)
- With DNS fixed, `save shuffled_copy` → `GamaImageFile.flushBuffer` → `ImageIO.write(...)` failed:
  `NoSuchMethodError: No static method write(Ljava/awt/image/RenderedImage;Ljava/lang/String;Ljava/io/File;)Z`.
  The app-source `javax.imageio.ImageIO` stub lacked every `write` overload, and
  `java.awt.image.RenderedImage` did not exist anywhere (no jar provides it).
- **Fixes (app stubs, no jar patch needed):**
  - `app/src/main/java/java/awt/image/RenderedImage.java` — new API-complete stub interface
    (tile/grid/getData/getSources/getNumSources etc.).
  - `app/src/main/java/java/awt/image/BufferedImage.java` — now `implements RenderedImage`;
    tiles/grid map to the single raster, `getSources()` = empty.
  - `app/src/main/java/javax/imageio/ImageIO.java` — added
    `write(RenderedImage, String, File)` and `write(RenderedImage, String, OutputStream)`,
    encoding via `BufferedImage.getAndroidBitmap().compress()` (PNG/JPEG).
- **Verified on device:** model compiles, downloads the image, shuffles the matrix, and
  writes `files/images/local_copy.png`; both displays (`Original`, `Shuffled_copy`) render
  (`updateDisplays: 2 output(s)`), zero runtime errors.

## Session 7 (2026-08-14) — Builtin 3D shapes render correctly (sphere/cone/pyramid)

### Problem
In 3D displays, `sphere(r)` drew as a **cylinder** (vertical extrusion of the circle
footprint). Root cause: `AndroidDisplayGraphics.drawShape3DRec()` only special-cased
`CUBE`/`BOX`; every other polygon with `depth > 0` fell through to `addPrism3D`.

### Fix (native-app app code only, no jar patches)
- `AndroidDisplayGraphics.drawShape3DRec()`: new branch for `depth > 0` and
  `type ∈ {SPHERE, CONE, PYRAMID}`:
  - **SPHERE** → `addSphereMesh(...)` — 16-ring × 24-seg lat/long UV sphere
    (optional spherical UV texture), radius = `max(footprintR, depth/2)`, resting on z0
    (center z = z0 + r). Footprint radius/centroid are computed from the transformed
    shell (`transformShell(shell, center, k, rot)` + ox/oy), matching `addPrism3D`.
  - **CONE / PYRAMID** → `addTaperedMesh(...)` — base polygon at z0 (from shell) +
    side triangles up to the apex at the shell centroid at z0+depth. Handles the
    square 5-pt pyramid footprint and the ~33-pt circle cone footprint (nth side tri
    is degenerate for the closing vertex, harmless).
  - CYLINDER stays a prism (already correct), CUBE/BOX unchanged.
- Debug: the `SHAPE3D` dedup key now includes `attributes.getType()` + `depth`, so each
  distinct shape logs once (`ishape=... depth=...`), and the log line prints them too.

### Verified on device (Shape3DTest.gaml, camera {155,50,60} → {90,50,0})
- Logs: `ishape=SPHERE depth=3.0`, `CONE 6.0`, `CYLINDER 6.0`, `PYRAMID 6.0`, `CUBE 6.0`;
  `prims=464` = sphere 384 + cone 34 + cyl 34 + pyramid 6 + cube 6 (exact).
- Screenshot silhouettes: sphere = circle, cone3D = apex-to-wide taper, cylinder = constant
  width, pyramid = taper widest at base, cube = square. Two shaded faces visible on the
  pyramid (per-face flat lighting).

### Gotcha: legacy `cone(r,h)` is a 2D triangle, not a 3D cone
- `cone(3,6)` (Integer,Integer operator) builds `polygon(loc, loc+cos(r)*worldMax,
  loc+cos(h)*worldMax)` — args are treated as **headings/angles**, radius = max(topology
  w/h). So it renders as a huge flat triangle (matches desktop GAMA). The 3D cone is
  **`cone3D(radius, height)`** → `buildCone3D` = circle + `setDepth(h)` + type CONE.
- Note: `buildTeapot` also exists (circle + setDepth + type TEAPOT); still falls through
  to the prism path (acceptable stand-in, no teapot mesh implemented).

### Reusable device test loop
- Push model: `adb push <m>.gaml /sdcard/` then
  `adb shell 'cat /sdcard/<m>.gaml | run-as com.gama.nativeapp sh -c "cat > files/models/<m>.gaml"'`.
- Launch: `adb shell am force-stop com.gama.nativeapp; adb logcat -c;
  adb shell am start -n com.gama.nativeapp/.ExperimentActivity
  --es file_path /data/data/com.gama.nativeapp/files/models/<m>.gaml`.
- Verify: `adb shell logcat -d | grep SHAPE3D` (ishape/depth lines, SCENEBOUNDS, PRIMHIST)
  and `adb exec-out screencap -p > shot.png` (pixel/connected-component analysis in Python).


## Session 8 (2026-08-14) — Boids 3D Motion works: AlphaComposite.Src NoSuchFieldError fixed

### Problem
In the library model `Toy Models/Boids/models/Boids 3D Motion.gaml`, only the red goal
sphere rendered; the 100 boids (drawn as `bird.gif` icons) never appeared and nothing
moved. logcat (reproduced live on the installed build) showed, per display frame:
`java.lang.NoSuchFieldError: No field Src of type Ljava/awt/AlphaComposite; in class
Ljava/awt/AlphaComposite; ... (declaration ... appears in base.apk!classes16.dex)`
thrown from `gama.extension.image.ImageCache.getImageFromFile` (loading the GIF).

### Root cause
`java.awt.AlphaComposite` shipped only as a class inside `app/libs/awt-stubs.jar` and its
API was incomplete: it had the UPPERCASE int rule constants (CLEAR, SRC, SRC_OVER, ...)
but **not** the OpenJDK mixed-case static AlphaComposite-typed fields (`Src`, `SrcOver`,
`SrcAtop`, `SrcIn`, `SrcOut`, `Dst*`, `Clear`, `Xor`). GAMA + jsvg compiled against the
real JDK and do `getstatic AlphaComposite.Src` etc. (confirmed via javap across all jars:
needs `Src`, `SrcOver`, `SrcAtop`, `SrcIn`, `SrcOut`, `Xor` plus `getInstance(int[,float])`,
`derive(int/float)`, `getAlpha()`, `getRule()`).

### Fix (app code + jar cleanup)
- **New** `app/src/main/java/java/awt/AlphaComposite.java` — full OpenJDK-compatible stub:
  all 18 int rule constants + the 11 mixed-case singleton fields (Clear, Src, Dst, SrcOver,
  DstOver, SrcIn, DstIn, SrcOut, DstOut, SrcAtop, DstAtop, Xor) + getInstance/derive
  (returns `this` when args match, else new), getAlpha/getRule/getTransparency.
- **Removed** `java/awt/AlphaComposite.class` from `app/libs/awt-stubs.jar` (`zip -d`)
  so D8 doesn't see a duplicate class. awt-stubs.jar has NO pristine copy (it's not in
  `app/libs/pristine/`), so this removal persists across `patchGamaJars` runs (its two
  in-place patchers only touch FontRenderContext/AwtFontMetrics).

### Verified on device (launch command for library models)
```
adb shell am force-stop com.gama.nativeapp; adb logcat -c
adb shell "am start -n com.gama.nativeapp/.ExperimentActivity \
  --es model_name 'models/Toy Models/Boids/models/Boids 3D Motion.gaml' \
  --es jar_path  'models/Toy Models/Boids/models/Boids 3D Motion.gaml' \
  --es experiment_name '3D' --ez from_library true"
```
(NOTE: the `models/` prefix IS required in jar_path — compileModelFromLibrary looks the
entry up directly in the cached `gama.library.jar`, whose entries are `models/...`.)
- Logs: `Auto-starting experiment: 3D`; ZERO `NoSuchFieldError`/`AlphaComposite` and ZERO
  `Error when drawing in a display` (only benign `AWT_CHART: setComposite:AlphaComposite`).
- Screenshots 4-5 s apart: 74k-104k pixels changed in the viewport (boids flock moving);
  connected components show a dense swarm blob (~486x368 px) plus ~20 smaller boid
  clusters. New AlphaComposite confirmed in classes12.dex with both int + mixed-case fields.

## Session 9 (2026-09-03) — `.asc` grid envelope fix + `jdk.incubator.vector` Android compat (engine source, v0.1.54)

### Goals
Fix two Android-facing engine problems **in the engine source** (fork `~/git/mygama/gama`,
remote `hqnghi88/gama`), then drop the bytecode `AscEnvelopeFixPatcher` from this repo:

1. `GamaGridFile.customAscReader` computed the grid envelope with swapped axes
   (`of(xC, yC, xC+cols*dX, ascInfo[3], ...)`), producing a degenerate envelope for real
   `.asc` files. Correct order is **(x1, x2, y1, y2)**.
2. The newer engine build (`gama.core_0.0.0.20260903*`) uses the **jdk.incubator.vector**
   JVM Vector API in core math/matrix/diffusion code. Android's ART does not bundle
   `jdk.incubator.vector`, so the `GamaFloatMatrix` static initializer
   (`DoubleVector.SPECIES_PREFERRED`) threw `NoClassDefFoundError` whenever a float/int
   matrix or grid field was allocated (e.g. any `grid_file` test).

### Engine fork commits (`~/git/mygama/gama`, pushed to `hqnghi88/gama@main`)
- `78f3ac99e` — `GamaGridFile.customAscReader` envelope axis order:
  `GamaEnvelopeFactory.of(xC, xC + nbCols * dX, yC, ascInfo[3], 0, 0)`.
- `84d497e33` — Replace `jdk.incubator.vector` (VectorSpecies/DoubleVector/IntVector/
  VectorMask/VectorOperators/lanewise) with existing scalar fallbacks in:
  `FieldDiffuser` (`fastDiffusionWithConvolution`), `GamaField`, `GamaFloatMatrix`,
  `GamaIntMatrix`, `Maths` (`pow`, `abs`, `cos`, `sin`), `Comparison` (`>`, `<`, `==`
  on matrices), `Logic` (`ifelse`). Removed every `SPECIES` static + vector import.
  Net -360/+38 lines; behavior unchanged, now JVM- and Android-compatible.

### Rebuild / integration
- Engine: `bash travis/build.sh` → `BUILD SUCCESS`, produced
  `gama.core_0.0.0.202609031525.jar`. Verified the jar has **zero** `jdk.incubator.vector`
  strings and still calls `GamaEnvelopeFactory.of` in `GamaGridFile`.
- Android: dropped old `202609031425` jar, added new pristine
  `app/libs/pristine/gama.core_0.0.0.202609031525.jar`. `patchGamaJars` restores newest
  pristine → working jar and runs patchers (GridFileFallback, TypeSwitch, etc.).
  **`AscEnvelopeFixPatcher` removed** (committed `c16804d`). Build with Java 25
  (`JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-25.jdk/Contents/Home`),
  `./gradlew -p app assembleDebug` → SUCCESS.
- Version bumped to **0.1.54** (`versionCode 49`, `versionName "0.1.54"`) in
  `app/build.gradle`.

### Verified on emulator (`ASC File Import` → `gridloading` experiment)
- ZERO `DoubleVector` / `jdk.incubator` / `NoClassDefFoundError`.
- Grid read via the GeoTools path fails with the benign (Android) gap
  `javax.imageio.stream.MemoryCacheImageInputStream`; `GridFileFallbackPatcher` catches
  it (catch `Throwable`) and the model falls through to `customAscReader` — now with the
  corrected envelope — and the grid loads/renders.
- UI shows `gridloading` at 1 cycle, stable, with **"As DEM / As 2D grid"** + Zoom
  render options (proving a valid, non-degenerate envelope).

### Notes / state
- Engine jars are **not** git-tracked (`*.jar` in `.gitignore`); the swap is a build
  artifact only. Tracked changes: patcher removal (`c16804d`), version bump (`0.1.54`).
- `gama.engine.version` is empty; engine bundle version is carried by the jar filename
  (`gama.core_0.0.0.<timestamp>.jar`), globbed by `resolveBundle`.
- `app/build.gradle` still pins `gaml.compiler_0.0.0.202608091252.jar` (different bundle,
  untouched).

## Session 10 (2026-09-04) — Eliminate `gambuild` patchers into engine source + full bundle refresh (Gradle 9 / Java 25)

### Goals
Per directive, stop using bytecode ASM patchers and move each patcher's effect into real
GAMA engine source (fork `~/git/mygama/gama`), then remove the patcher. Start with the
`gambuild` group, then refresh the whole engine so old/new bundle version-skew doesn't
recur.

### gambuild patchers → engine source
- `VarValidatorNullGuardPatcher` (gama.api): in
  `AttributeDeclaration$VarValidator.validate()`, `cd.getFacetExpr(INIT)` can return `null`
  when the INIT facet fails to compile (e.g. `float step <- 1 #s;`), and
  `expr.isTimeDependent()` NPE'd on Android. Fixed in source — engine fork commit
  `ea6b07479`: `if (expr != null && expr.isTimeDependent())`. Verified in the rebuilt
  `gama.api` bytecode: `astore 6 / aload 6 / ifnull skip / isTimeDependent()`.
- `GamlModelBuilderPatcher` (gaml.compiler): the newer engine source ALREADY discards
  validation contexts in `buildModelDescription`'s `finally`
  (`getResources()...forEach(GamlResourceServices::discardValidationContext)`); confirmed
  present in the rebuilt `gaml.compiler` (stream `.filter().map().forEach()`).
- Deleted both `tools/patchers/gambuild/*.java` and removed their two entries from the
  `patchers` list in `app/build.gradle`.

### Gradle 9 / Java 25 build fix (unblocker)
`Project#exec`/`Project#javaexec` were **removed in Gradle 9.0**. `patchGamaJars` called
bare `exec {}` (class-version downgrade, D8-strip via `jar`, ASM patcher compile/run) and
failed with `Could not find method exec()`. Fixed by injecting `ExecOperations`:
- Added top-level `InjectedExecOps` interface (`@Inject ExecOperations getExecOps()`),
  instantiated via `project.objects.newInstance(...)` inside the task; replaced all
  `exec`/`project.exec` call sites with `execOps.exec(...)`.

### Full engine refresh (all bundles -> one consistent build)
The mixed state (fresh `gama.api`/`core`/`gaml.compiler` + old everything-else) was
**broken**: fresh `gama.api` moved `IDataFrame` out of `gama.api.types.dataframe` into a
new `gama.extension.dataframe` bundle (package `gama.extension.dataframe`). The old
`gama.extension.database` still referenced the old location → `NoClassDefFoundError:
Lgama/api/types/dataframe/IDataFrame;` at bootstrap.
- Rebuilt the whole engine via `bash travis/build.sh` → `BUILD SUCCESS` (1m42s); all
  bundles produced at `0.0.0.202609032313`.
- Swapped **all** GAMA/GAML bundles in `app/libs/pristine/` to `202609032313` (29 jars),
  plus added the new `gama.extension.dataframe_0.0.0.202609032313.jar`.
- Added `gama.extension.dataframe` to: `GamaNativeBootstrap.pluginNames` (before
  `database`, so df operators resolve) and `build.gradle` `toolchainJars` (so
  TypeSwitch/EnumSwitch patch its SwitchBootstrap sites).
- Verified `IDataFrame`/database `NoClassDefFoundError` is gone at runtime; app reaches
  the main menu (`ModelNavigatorActivity`) with no fatal bootstrap errors.
- Note: batched extension is now `gama.extension.batch_0.0.0.202609032313.jar` (was
  `-0.0.0-SNAPSHOT.jar`); `resolveBundle` already matches both `-*`/`_*` patterns.

### Build status
`./gradlew :app:assembleDebug` (Java 25, Gradle 9.1.0, AGP 9.0.0) → **BUILD SUCCESS**
(~2 min). APK `app/build/outputs/apk/debug/app-debug.apk` (227 MB) rebuilt + reinstalled
on emulator-5554. Remaining D8 warnings (non-fatal, pre-existing pattern):
`guice-5.1.0-patched.jar` and `gama.core_...202609032313.jar: Invalid stack map table …
Expected frame instruction`.

### Still to do (next sessions)
- Eliminate remaining GAMA-source patchers (started: `GridFileFallbackPatcher` is now a
  **no-op** on the fresh `gama.core` — "Target not found or not patched" — so it can be
  dropped next): Display3D, Crs, Colors, SimulationRunner, MeshLayer, LayerManager,
  ImageLayer, CacheBuilder, Projection, EclipseCore, ChartOutput, WorldGlobal, GridColor,
  ParallelRunner, TypeSwitch, EnumSwitch, GridFileFallback.
- Third-party jar patchers (Spi, MapProjection, GuavaJreCompat, StaxNewFactory,
  ColorBrewer, FontRenderContext, AwtFontMetrics) cannot be fixed in GAMA source; decide
  separately.

## Session 11 (2026-09-04) — Release v0.1.55 (15 patchers eliminated + ASM CI fix)

### What shipped
- **v0.1.55** published via the `auto-release` GitHub workflow (JDK 21, Gradle 9.1.0, AGP 9.0.0).
  Tag `v0.1.55` → APK `app-debug.apk` attached.
- Total ASM patchers removed this session: **15** (Display3D, Colors, LayerManager, ImageLayer,
  MeshLayer, GridColor, CacheBuilder, ChartOutput, Crs, Projection, GridFileFallback,
  SimulationRunner, VarValidator, GamlModelBuilder). Effects moved into engine source
  (fork commits `ea6b07479`, `12c6950c1`). Remaining source patchers: ParallelRunner,
  WorldGlobal, EclipseCore. Remaining third-party jar patchers: Spi, MapProjection,
  GuavaJreCompat, StaxNewFactory, ColorBrewer, FontRenderContext, AwtFontMetrics.

### CI risk discovered + fixed
- `app/libs` is gitignored; CI downloads engine jars from the `native-app-deps` **release
  asset**. That asset was stale (2026-08-22, pre-Session engine) and lacked the new
  `gama.extension.dataframe` bundle → `patchGamaJars` crashed with
  "Cannot access first() element from an empty Iterable".
- **Fix**: regenerated `native-app-deps.tar.gz` from the verified local `app/libs`
  (306 MB, contains all 29 GAMA/GAML bundles at 202608032313/202609032346 + dataframe) and
  uploaded it (`gh release upload native-app-deps ... --clobber`).
- Second CI failure: `patchGamaJars` resolved ASM from a hardcoded Gradle-cache path
  (`~/.gradle/caches/modules-2/files-2.1/org.ow2.asm/asm/9.6`) that fresh runners don't have.
  **Fix** (`53275c0`): added an `asmDeps` project configuration
  (`org.ow2.asm:asm/asm-tree/asm-util:9.6`) and use `configurations.asmDeps.files`.
  Verified: with ASM cache dir removed, networked build resolves it fine.

### Release workflow notes
- Release is **tag-triggered** (`on: push: tags: 'v*'`); can't re-push same tag to re-trigger,
  so to rebuild a failed release with fixes: delete+recreate the tag at the fixed commit
  (`git tag -d`, `git push --delete`, `git tag -a`, `git push`).
- CI-build APK (160 MB) is smaller than local (183 MB) but contains the *same* verified fresh
  engine jars (confirmed via log: `Downgraded class versions in gama.api/core_...202609032346`,
  dataframe bundle present and TypeSwitch-patched).

### Build status
Local `./gradlew app:patchGamaJars app:assembleDebug` (Java 25) → **BUILD SUCCESS**; all 8
active patchers run OK. CI release run → **success in 5m36s**.

### To do (next)
- Drive a functional model run on emulator to smoke-test GridColor/Colors/LayerManager engine
  source fixes (deferred from Session 10).
- Evaluate WorldGlobal (codegen), ParallelRunner (gama.api threading), EclipseCore for source
  migration; decide third-party patcher fate.
- Next version bump 0.1.56 after model-run verification.
