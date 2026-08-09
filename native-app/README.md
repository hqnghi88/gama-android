# GAMA Native Android

GAMA (GIS Agent-based Modelling Architecture) running natively as an Android app.
The `native-app` folder is the Android Gradle project that boots **the real GAMA
engine — the original GAMA JARs — on top of Android**, without OSGi and without a
desktop JVM.

> This document answers one recurring question in detail: **"I see lots of classes in
> `app/src/main/java`. Did you *port* or *translate* part of GAMA to Android? Or is the
> jar code actually being used — or is it all fake?"**
>
> Short answer: **The GAMA engine is 100% the real jars, running in the APK.**
> The ~133 files in `app/src/main/java` are (1) a thin Android host shell,
> (2) small API-compatibility stubs for Java SE / Eclipse / OSGi classes that Android
> does not provide, (3) one ported SVG renderer, and (4) **one** GAMA-package shadow
> class. None of them reimplement GAMA engine logic. The proof is in Section 2.

---

## 1. The big picture

GAMA is a desktop Java application built on:
- Java SE (Swing/AWT for UI)
- Eclipse platform (OSGi plugin runtime + extension registry)
- Xtext/ANTLR-generated GAML compiler
- ~50 `gama.*`/`gaml.*` OSGi bundles (JARs)

Android has **none** of those as a platform (no `java.awt`, no Swing, no OSGi, no
Eclipse registry). The whole game of this project is:

1. Take the **unmodified GAMA jars** (`app/libs/*.jar`).
2. Give the jars, at build time, everything they reference that Android lacks:
   - **Java SE stubs** for `java.awt.*`, `javax.swing.*`, `javax.imageio.*`
   - **Eclipse/OSGi stubs** (`org.eclipse.core.runtime.Platform`, extension registry, `Bundle`, …)
   - **Maven runtime deps** the jars expect on a desktop classpath: Xtext 2.35.0,
     Xtend 2.35.0, EMF 2.31.0, ANTLR runtime, JTS, JGraphT, JFreeChart, Guava, StreamEx…
3. Provide an Android **host**: activities, a `View`-based display surface, a GAMA
   `IGui` implementation, and a bootstrap that manually performs what OSGi's
   extension mechanism would otherwise do automatically (register bundles, delegates,
   constants, display types).
4. Dex the whole thing into an APK.

The engine logic — GAML parsing/compilation, agent scheduling, the simulation loop,
experiment plans, layer managers, draw statements, output display — all of it comes
from the jars unchanged and executes on-device.

---

## 2. Is the engine really the jars? (Evidence)

### 2.1 The build links the real jars

`app/build.gradle:566`:

```groovy
implementation fileTree(dir: 'libs', include: ['*.jar'], exclude: ['gama.extension.physics*', '*.original.jar'])
```

Every GAMA jar in `app/libs/` is a compile/runtime dependency of the app:

| Jar | Size | Role |
|-----|------|------|
| `gama.core_0.0.0.202605140230.jar` | 2.7 MB / **1,149 classes** | engine core: agents, experiments, displays, layers, memory |
| `gaml.compiler_0.0.0.202605140230.jar` | 655 KB / **343 classes** | GAML compiler: Xtext-generated parser, model builder, validator |
| `gama.ui.shared`, `gama.ui.display.*`, `gama.ui.experiment`, … | ~13 MB total | display/output/experiment layer (hosted by our Android view) |
| `gama.extension.*` (image, network, maths, bdi, pedestrian, stats, traffic, …) | ~15 MB total | optional model capabilities |
| `gama.headless`, `gama.library`, `gama.processor`, `gama.annotations` | ~2 MB | headless runner, built-in model library, annotation processing |
| `gama.dependencies` | 70 MB | third-party libs GAMA bundles (GeoTools, KML, OSM, …) |

The jars the compiler needs beyond GAMA are pulled from Maven — Xtext/Xtend **2.35.0**
and EMF **2.31.0** (`app/build.gradle:581-604`) — so the GAML compiler is the genuine
Xtext/ANTLR pipeline, not a reimplementation.

### 2.2 The APK dex contains the actual engine classes

The built `app-debug.apk` was unzipped and its `classes*.dex` searched. The real
engine class names are physically in the dex:

```
gaml.compiler.gaml.validation.GamlModelBuilder      (compiler entry point)
gama.gaml.compilation.GAML                          (GAML language kernel)
gama.core.kernel.experiment.ExperimentPlan          (experiment runtime)
gama.core.outputs.display.LayerManager              (display layer manager)
gama.gaml.statements.draw.DrawStatement             (draw statement)
gama.core.outputs.layers.EventLayerStatement        (event layer)
gaml.compiler...parser.antlr.internal.InternalGamlParser  (16 class files — the
    Xtext-generated ANTLR GAML parser shipped inside gaml.compiler.jar)
```

Reference counts found across all dex files, by source package:

| Source | Type references in dex |
|--------|------------------------|
| `gaml/additions` (generated GAML language classes) | 1,984 |
| `gama/ui` | 1,366 |
| `gama/core` | 1,286 |
| `gama/gaml` | 819 |
| `gaml/compiler` | 731 |
| `gama/dependencies` (GeoTools/KML/OSM) | 525 |
| `gama/extension` | 292 |
| `com/gama/nativeapp` (the Android host) | 229 |
| `gama/headless` | 73 |
| `java/awt` (SE stubs + jar references) | 632 |
| `javax/swing`, `javax/imageio` (stubs) | 237 |
| `org/eclipse`, `org/osgi` (Maven EMF/OSGi + stubs) | 9,514 |
| `org/antlr/runtime` (ANTLR 3 runtime from Maven) | 109 |
| `org/eclipse/xtext` (Xtext runtime from Maven) | 5,730 |

(These are type-string occurrences — every call site counts — so they over-count
unique classes, but they prove the classes exist and are linked into the app.)

### 2.3 It actually runs

Behavioural proof, not just static proof: the `corridor_model` (a real GAMA model
with event layers, `#mouse_down`/`#mouse_move` constants, dynamic populations) is
compiled by this exact pipeline on-device and simulated cycle after cycle while the
display updates. The compile path is `GamlModelBuilder` → Xtext parser →
`ExperimentPlan` — all jar code.

---

## 3. What exactly is in `app/src/main`? (the 133 files)

Every `.java` file in `app/src/main/java`, counted by package:

| Files | Package | What it is |
|------:|---------|------------|
| **18** | `com/gama/nativeapp` | **The Android host shell** (real app code, not GAMA) |
| **52** | `java/awt` | **Java SE API stubs** — Android has no `java.awt` |
| **11** | `javax/swing` | Swing stubs (used by the jar's GUI classes) |
| **9** | `javax/imageio` | ImageIO stubs |
| **26** | `org/eclipse/core` | Eclipse runtime/registry stubs |
| **4** | `org/osgi/framework` | OSGi framework stubs |
| **11** | `com/github/weisj/jsvg` | Ported SVG renderer |
| **1** | `systems/uom/common` | Unit-system stub |
| **1** | `gama/gaml/descriptions/SkillDescription` | **The single GAMA-package shadow class** |
| **133** | **total** | |

### 3.1 `com.gama.nativeapp` — the Android host (18 files)

These are the *phone side*. They implement GAMA's own extension points so the jar
engine can talk to Android. They contain **no GAMA engine logic**.

| File | Role |
|------|------|
| `GamaApplication.java` | App entry; disables `java.util.prefs` |
| `GamaNativeBootstrap.java` | Boots the engine: registers plugin bundles, loads GAML additions, inits metamodel/types, sets up the Xtext injector, registers draw/create/event delegates, registers 331 GAML constants + `android2d` display type |
| `MainActivity.java` | Generic container activity |
| `ModelNavigatorActivity.java` | Launcher: model/library tree |
| `ModelEditorActivity.java` | GAML text editor |
| `ExperimentActivity.java` | Compiles a model, runs an experiment, play/pause/step/stop |
| `WorkspaceManager.java` | Maps app storage to a GAMA workspace |
| `LibraryJarUtil.java` | Extracts library models from `assets/gama.library.jar` |
| `ModelTreeItem.java` | Tree data holder |
| `NoOpPreferencesFactory.java` | Java-preferences no-op (Android lacks `java.util.prefs` backend) |
| `SensorBridge.java` | Sensor → GAMA bridge (gyro/etc.) |
| `gui/AndroidGuiHandler.java` | Implements GAMA's `IGui` for Android |
| `gui/AndroidGamaView.java` | Implements `IGamaView.Display` |
| `display/AndroidDisplaySurface.java` | `View` implementing `IDisplaySurface`; Canvas rendering |
| `display/AndroidDisplayGraphics.java` | Implements `AbstractDisplayGraphics`; GAMA draw calls → Canvas |
| `display/AndroidScene3D.java` | 3D scene |
| `display/GamaAndroidDisplaySetup.java` | Registers `"android2d"` display type |
| `util/LayerManagerHelper.java` | Layer-manager convenience wrapper |

### 3.2 `java.awt` / `javax.swing` / `javax.imageio` — Java SE stubs (72 files)

Android ships no `java.awt`. But the gama jars were compiled against it and reference
`java.awt.Color`, `java.awt.geom.*`, `java.awt.image.BufferedImage`, `java.awt.Font`,
`java.awt.Rectangle`, `java.awt.Shape`, etc. throughout. These stubs are small,
API-compatible, Android-friendly stand-ins (`java.awt.Color` is backed by an `int`
ARGB, `java.awt.geom.Path2D` by `android.graphics.Path`, …) so the *unmodified jar
bytecode* links and runs. The dex confirms 632 `java.awt` type references resolve to
these stubs.

Same story for `javax.swing` (referenced by the jar's desktop GUI classes, which we
never instantiate but which must still *verify*) and `javax.imageio`
(`BufferedImage`/`ImageIO` used by the image display layer).

### 3.3 `org.eclipse.*` / `org.osgi.*` — platform stubs (30 files)

GAMA desktop is an Eclipse/OSGi product: it discovers its plugins through the OSGi
extension registry. Android has no OSGi. These stubs give the jars a working
substitute: `Platform.getExtensionRegistry()` returns an empty in-memory registry,
`Bundle`/`BundleContext` fake wrappers delegate to the app classloader, and the
preferences/`IPath`/`IFileStore` APIs are mapped to Android storage.

**This is why the bootstrap must do "manual OSGi."** Because the extension registry is
empty, nothing auto-discovers delegates — so `GamaNativeBootstrap` explicitly
registers the draw delegates, create delegates, event-layer delegates
(`MouseEventLayerDelegate`, `KeyboardEventLayerDelegate`), GAML constants, and the
display type. The empty-registry stub is literally the reason event-based models
(`#mouse_down`/`#mouse_move`) failed to compile until the bootstrap registered the
event delegates.

(The heavy Eclipse machinery the jars actually need at runtime — EMF, Xtext, the real
OSGi runtime — is not stubbed; it is pulled from Maven / `org.eclipse.osgi-patched.jar`,
see Section 2.)

### 3.4 `com.github.weisj.jsvg` — SVG renderer (11 files)

Android has no built-in SVG renderer, and GAMA's display pipeline can render vector
assets. This is a port of the pure-Java JSVG library to satisfy that dependency.

### 3.5 `systems.uom.common.USCustomary` — unit stub (1 file)

JSR-385 unit constants needed by GAMA's unit system, adapted for Android.

### 3.6 `gama.gaml.descriptions.SkillDescription` — the one shadow class (1 file)

This is the **only file in the whole app source that lives in a GAMA package**. It is
a re-implementation of one engine class, and it is *not* a port of GAMA logic — it is
a replacement class written to avoid a JVM feature (`StringConcatFactory` bootstrap
method #17 / Java 21 string concat) that D8 mis-dexes for that class.

`patchGamaJars` **strips** the original `SkillDescription.class` out of the jars
(`app/build.gradle:9`) and the app's copy (compiled into the dex) takes its place at
runtime. Same class name, same public API — its job is to make one JVM bytecode path
dex-compatible.

---

## 4. Build pipeline: how the jars become an APK

```
libs/*.jar ──► patchGamaJars ──► compileDebugJavaWithJavac ──► dexBuilderDebug (D8) ──► APK
   (real        strip SkillDescription
    jars)       recompile ~19 classes from repo GAMA source (with Android fixes)
                and inject back via `jar uf`
                ASM-patch bytecode:
                  - ParallelRunnerPatcher   (ForkJoinPool → Android ExecutorService)
                  - Display3DPatcher        (enable 3D surface creation)
                  - TypeSwitchPatcher       (Java-21 type-switch invokedynamic → dex-safe)
                  - GamaPopulation/GamlAgent/etc. recompiled from
                    ../../gama.core/src and ../../gaml.compiler/src
                + app/src classes
```

Key points:

- **Linking** (`app/build.gradle:566`): the jars are first-class dependencies. No
  reflection-based "trick" — D8 sees them as ordinary input classes.
- **`patchGamaJars`** (`app/build.gradle:6`): a build-time task that (a) strips
  `SkillDescription.class`, (b) recompiles a small set of engine classes **from this
  repository's own GAMA source tree** (`gama.core/src`, `gaml.compiler/src`) with
  Android-specific fixes and injects them into `gama.core` via `jar uf`
  (`app/build.gradle:160`), and (c) runs ASM patchers (`tools/*.java`) for bytecode
  features that Android/D8 cannot run (ForkJoinPool on Android, Java-21 string/type
  concatenation, the 3D display early-return).
- **Ordering is critical**: `patchGamaJars` must run before D8, otherwise the APK
  contains the unpatched jar. Enforced in `app/build.gradle:492-495`.
- **Dexing**: D8 compiles jars + app classes together; the engine classes physically
  end up in the APK (Section 2.2).

---

## 5. The honest nuance: "untouched" vs "patched"

It is accurate to say the engine is the real jars — but it is not literally true that
every byte is byte-identical to upstream. Three categories:

1. **Runs as-is from the jar** — the vast majority of the engine (~11,000 classes
   across the libs jars). GAML grammar/parser, model builder, agent metamodel,
   experiment controller, display layers, draw statements, built-in functions,
   extensions.
2. **Bytecode-patched (ASM)** — a handful of classes rewritten at build time because
   Android's runtime/dexer can't do what the desktop JVM does:
   - `GamaExecutorService` / `ParallelAgentRunner`: Android `ForkJoinPool` is broken
     for this use, replaced with a regular `ExecutorService`
     (`ANDROID_PARALLEL_EXECUTOR`).
   - `LayeredDisplayOutput`: remove the desktop-only `is3D()` early-return.
   - Various: Java-21 `typeSwitch`/`StringConcatFactory` invokedynamic rewrites that
     D8 cannot desugar.
   - `SimulationRunner$1`: release a semaphore in the catch path (deadlock fix).
3. **Recompiled from this repo's GAMA source with Android fixes** — ~19 classes
   (`GamaPopulation`, `GamlAgent`, `AbstractAgent`, `ExperimentAgent`,
   `DefaultExperimentController`, `GamlModelBuilder`, `AbstractOutputManager`,
   `LayeredDisplayOutput`, `ImageLayer`, `SimulationPopulation`, `GamaGridFile`,
   `GridPopulation`, `GamaList`, chart classes, …). These are compiled from the real
   GAMA sources (this repository *is* the GAMA source tree) with small patches, then
   injected into `gama.core` with `jar uf` (`app/build.gradle:124-209`). They are
   GAMA's own code — not a rewrite.
4. **Replaced by an app-source shadow** — `SkillDescription` (one class, Section 3.6).

So: **the engine code is GAMA's; only the platform adapter is ours.** Roughly 114 of
the 133 app-source files are platform stubs and the host shell; 1 lives in a GAMA
package; the remainder is a ported SVG library.

---

## 6. FAQ

**Q: Did you translate/port GAMA's logic to Kotlin/Java Android code?**
No. There is no ported engine. Search `app/src/main/java` — the only GAMA-package file
is `gama/gaml/descriptions/SkillDescription.java`, a compatibility replacement for
one class. The engine logic is inside the jars.

**Q: Then why do the stubs look like reimplementations (e.g. `java.awt.Color`)?**
Because Android genuinely lacks those APIs and the jar bytecode references them. A
stub provides the *same class name and API surface* so the existing jar code links
unchanged. The stub has no GAMA logic in it — `java.awt.Color` just stores an ARGB
int instead of delegating to a real AWT implementation.

**Q: How do I know the APK really contains the engine and not just stubs?**
Unzip the APK and inspect the dex (Section 2.2), or simply run it: a real GAML model
compiles and simulates. A fake would have no `GamlModelBuilder`, no Xtext parser, no
`ExperimentPlan`.

**Q: What about the models in `assets/`?**
`gama.library.jar` (a copy of the real library jar) holds the built-in GAMA models;
user models live in `assets/models/` and are compiled with the same engine.

**Q: Why exclude `gama.extension.physics`?**
It carries native/desktop physics bindings that do not fit the Android build; it is
excluded at `app/build.gradle:566`.

---

## 7. Quick reference

### File map

```
native-app/
├── app/
│   ├── build.gradle                 # deps (line 566), patchGamaJars task (line 6)
│   ├── libs/*.jar                   # the real GAMA jars (11,499 class entries total)
│   └── src/main/
│       ├── assets/gama.library.jar  # built-in model library
│       ├── assets/models/*.gaml     # sample/user models
│       └── java/
│           ├── com/gama/nativeapp/…    (18) Android host shell
│           ├── com/github/weisj/jsvg/… (11) ported SVG renderer
│           ├── java/awt/…              (52) Java SE stubs
│           ├── javax/swing/…           (11) Swing stubs
│           ├── javax/imageio/…          (9) ImageIO stubs
│           ├── org/eclipse/…           (26) Eclipse platform stubs
│           ├── org/osgi/…               (4) OSGi stubs
│           ├── systems/uom/…            (1) unit stub
│           └── gama/gaml/descriptions/… (1) SkillDescription shadow class
├── tools/*.java                  # ASM patchers run by patchGamaJars
└── HANDOFF.md                    # session handoff / build commands
```

### Build & deploy

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)   # Java 21 required
cd native-app
./gradlew assembleDebug
~/Library/Android/sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Verifying the engine is in the APK

```bash
unzip -o app/build/outputs/apk/debug/app-debug.apk 'classes*.dex' -d /tmp/apkcheck
cat /tmp/apkcheck/classes*.dex | strings -a | grep -E "GamlModelBuilder|ExperimentPlan|InternalGamlParser"
```
