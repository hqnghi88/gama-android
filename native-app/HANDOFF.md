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
