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
> The ~164 files in `app/src/main/java` are (1) a thin Android host shell,
> (2) small API-compatibility stubs for Java SE / Eclipse / OSGi classes that Android
> does not provide, and (3) a handful of GAMA-package constant shadows. None of them
> reimplement GAMA engine logic. The proof is in Section 2.

---

## 1. The big picture

GAMA is a desktop Java application built on:
- Java SE (Swing/AWT for UI)
- Eclipse platform (OSGi plugin runtime + extension registry)
- Xtext/ANTLR-generated GAML compiler
- ~35 `gama.*`/`gaml.*` OSGi bundles (JARs)

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

`app/build.gradle` (the dependencies block):

```groovy
implementation fileTree(dir: 'libs', include: ['*.jar'], exclude: ['*.original.jar', 'compile-stubs.jar'])
compileOnly fileTree(dir: 'libs', include: ['compile-stubs.jar'])
```

Every GAMA jar in `app/libs/` is a compile/runtime dependency of the app:

| Jar | Size | Role |
|-----|------|------|
| `gama.api` + `gama.core` (0.0.0.202609050134) | 1.4 MB (+0.9) / **474 + 639 classes** | engine core: agents, experiments, displays, layers, memory |
| `gaml.compiler` (0.0.0.202609050134) | 808 KB / **410 classes** | GAML compiler: Xtext-generated parser, model builder, validator |
| `gama.ui.shared`, `gama.ui.display.*`, `gama.ui.experiment`, … | ~9.3 MB total | display/output/experiment layer (hosted by our Android view) |
| `gama.extension.*` (image, network, maths, bdi, pedestrian, stats, traffic, physics, …) | ~87 MB total | optional model capabilities |
| `gama.headless`, `gama.library`, `gama.processor`, `gama.annotations` | ~8 MB | headless runner, built-in model library, annotation processing |
| `gama.dependencies` | 27 MB | third-party libs GAMA bundles (GeoTools, KML, OSM, …) |

The jars the compiler needs beyond GAMA are pulled from Maven — Xtext/Xtend **2.35.0**
and EMF **2.31.0** (`app/build.gradle:450-476`) — so the GAML compiler is the genuine
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
gaml.compiler...parser.antlr.internal.InternalGamlParser  (the Xtext-generated ANTLR
    GAML parser shipped inside gaml.compiler.jar)
```

Reference counts found across all dex files, by source package (fresh dex of
`0.1.60`):

| Source | Type references in dex |
|--------|------------------------|
| `gaml/additions` (generated GAML language classes) | 4,256 |
| `gama/ui` | 3,005 |
| `gama/core` | 926 |
| `gama/gaml` | 548 |
| `gaml/compiler` | 1,754 |
| `gama/extension` | 1,316 |
| `gama/dependencies` (GeoTools/KML/OSM) | 136 |
| `com/gama/nativeapp` (the Android host) | 603 |
| `gama/headless` | 154 |
| `java/awt` (SE stubs + jar references) | 860 |
| `javax/swing` + `javax/imageio` (stubs) | 323 |
| `org/eclipse` (Maven EMF/Xtext + stubs) | 18,623 |
| `org/osgi` (OSGi runtime + stubs) | 883 |
| `org/antlr/runtime` (ANTLR 3 runtime from Maven) | 253 |
| `org/eclipse/xtext` (Xtext runtime from Maven) | 11,204 |

(These are type-string occurrences — every call site counts — so they over-count
unique classes, but they prove the classes exist and are linked into the app.)

### 2.3 It actually runs

Behavioural proof, not just static proof: the `corridor_model` (a real GAMA model
with event layers, `#mouse_down`/`#mouse_move` constants, dynamic populations) is
compiled by this exact pipeline on-device and simulated cycle after cycle while the
display updates. The compile path is `GamlModelBuilder` → Xtext parser →
`ExperimentPlan` — all jar code.

---

## 3. What exactly is in `app/src/main`? (the 164 files)

Every `.java` file in `app/src/main/java`, counted by package:

| Files | Package | What it is |
|------:|---------|------------|
| **23** | `com/gama/nativeapp` | **The Android host shell** (real app code, not GAMA) |
| **60** | `java/awt` | **Java SE API stubs** — Android has no `java.awt` |
| **11** | `javax/swing` | Swing stubs (used by the jar's GUI classes) |
| **11** | `javax/imageio` | ImageIO stubs |
| **49** | `org/eclipse/core` | Eclipse runtime/registry stubs |
| **4** | `org/osgi/framework` | OSGi framework stubs |
| **6** | `gama/dev` | GAMA-package constants shadow classes |
| **164** | **total** | |

### 3.1 `com.gama.nativeapp` — the Android host (23 files)

These are the *phone side*. They implement GAMA's own extension points so the jar
engine can talk to Android. They contain **no GAMA engine logic**.

| File | Role |
|------|------|
| `GamaApplication.java` | App entry; disables `java.util.prefs` |
| `GamaNativeBootstrap.java` | Boots the engine: registers plugin bundles, loads GAML additions, inits metamodel/types, sets up the Xtext injector, registers draw/create/event delegates & GAML constants, snapshots display-type constants |
| `MainActivity.java` | Generic container activity |
| `ModelNavigatorActivity.java` | Launcher: model/library tree |
| `ModelEditorActivity.java` | GAML text editor |
| `ExperimentActivity.java` | Compiles a model, runs an experiment, play/pause/step/stop |
| `WorkspaceManager.java` | Maps app storage to a GAMA workspace |
| `AndroidWorkspaceManager.java` | Workspace shell on Android storage |
| `LibraryJarUtil.java` | Extracts library models from `assets/gama.library.jar` |
| `ModelTreeItem.java` | Tree data holder |
| `NoOpPreferencesFactory.java` | Java-preferences no-op (Android lacks `java.util.prefs` backend) |
| `PluginManager.java` | Loads GAML plugin jars (e.g. sensors) via a `DexClassLoader` |
| `SensorBridge.java` | Sensor → GAMA bridge (gyro/etc.) |
| `UiSystemBars.java` | Edge-to-edge insets + system-bar control (targetSdk 36) |
| `gui/AndroidGuiHandler.java` | Implements GAMA's `IGui` for Android |
| `gui/AndroidGamaView.java` | Implements `IGamaView.Display` |
| `gui/AndroidDialogs.java` | Dialog/option implementations for the engine GUI |
| `gui/ParamsPanelBuilder.java` | Builds the experiment parameter panel |
| `display/AndroidDisplaySurface.java` | `View` implementing `IDisplaySurface`; hosts both 2D and 3D rendering (software, CPU) |
| `display/AndroidDisplayGraphics.java` | Implements `AbstractDisplayGraphics`; translates GAMA `draw` calls → `Canvas` (2D) or `AndroidScene3D` prims (3D) |
| `display/AndroidScene3D.java` | Software perspective rasterizer that mimics GAMA's desktop OpenGL 3D output (CPU, no GPU) |
| `display/GamaAndroidDisplaySetup.java` | Registers GAML display-type constants: `android2d`, `2d`, `android3d`, `3d`, `opengl`, `opengl2` (see Section 6.1 for how they resolve) |
| `util/LayerManagerHelper.java` | Layer-manager convenience wrapper |

### 3.2 `java.awt` / `javax.swing` / `javax.imageio` — Java SE stubs (82 files)

**What happens.** Android ships no `java.awt`, no Swing, no AWT. But the GAMA jars
were compiled against them and reference `java.awt.Color`, `java.awt.geom.*`,
`java.awt.image.BufferedImage`, `java.awt.Font`, `java.awt.Rectangle`,
`java.awt.Shape`, etc. — on a desktop classpath those resolve to the JRE. On Android
the class doesn't exist at all, so **the jar bytecode cannot even verify** if they
are absent. Something must provide those class names.

**What is implemented.** Small, API-compatible stubs with the **same fully-qualified
class name and the same public API surface**, backed by Android types instead of a
real AWT toolkit:

| Stub | Backed by |
|------|-----------|
| `java.awt.Color` | `int` ARGB |
| `java.awt.geom.Path2D` / `PathIterator` | `android.graphics.Path` |
| `java.awt.image.BufferedImage` / `javax.imageio.ImageIO` | `android.graphics.Bitmap` |
| `java.awt.Font` / `FontMetrics` | `android.graphics.Typeface` / paint |
| `java.awt.Rectangle`, `Point`, `Dimension`, `Graphics2D` | plain Java fields / `android.graphics.Canvas` |

60 files in `java/awt`, 11 in `javax/swing`, 11 in `javax/imageio`. The Swing and
ImageIO classes in the *desktop GUI* jars only need to **verify** (they are never
instantiated on Android), so they can be skeletal; the display/image path
(`BufferedImage`, `Graphics2D`, `Shape`) needs a working implementation because the
image layer and draw statements use it.

**What is missing.** There is no real AWT/Swing event loop, windowing, layout, or
GUI toolkit. The stubs contain **no GAMA logic** — they exist only so the unmodified
jar bytecode links and runs against Android-native backing types. The dex confirms
860 `java.awt` type references resolve to these stubs (Section 2).

### 3.3 `org.eclipse.*` / `org.osgi.*` — a two-tier approach (53 files)

GAMA desktop is an Eclipse/OSGi product: plugins are containers discovered through
the OSGi registry, and the whole platform runtime (extension registry, bundles,
preferences, `IPath`/workspace) is part of the desktop host. Android has no OSGi and
no Eclipse platform. The handling is **split in two tiers**:

**Tier 1 — fully replaced by app-source stubs (what has NO equivalent on Android):**

- **The extension registry** — `Platform.getExtensionRegistry()` returns an *empty*
  in-memory registry. This is the decisive replacement: nothing auto-discovers
  plugins on device, which is **why the bootstrap must "do manual OSGi."**
  `GamaNativeBootstrap` explicitly registers the draw delegates, create delegates,
  event-layer delegates, GAML constants, and display types as a substitute for
  OSGi's automatic plugin discovery.
- **Workspace / storage APIs** — `IPath`, `IFileStore`, `IWorkspace`, `IProject`,
  `IResource`, `IResourceDelta` etc. (`org.eclipse.core.resources`,
  `org.eclipse.core.filesystem`) are mapped onto Android storage paths.
- **The bundle API** — `Bundle`, `BundleContext`, `BundleActivator`,
  `FrameworkUtil` are tiny wrappers that delegate to the app classloader rather than
  a running OSGi container (`BundleContext` is literally `Bundle getBundle();`).
- **Runtime plumbing** — `Platform`, `IProgressMonitor`, `CoreException`,
  `IConfigurationElement`, `IPreferencesService`, etc. 49 files under
  `org.eclipse.core.*` and 4 under `org.osgi.framework`.

**Tier 2 — the real thing, pulled from Maven / delivered patched (what the engine
genuinely needs at runtime):**

- **EMF 2.31.0, Xtext/Xtend 2.35.0, ANTLR** are real Maven dependencies
  (`app/build.gradle:450-476`). These `org.eclipse.emf.*` / `org.eclipse.xtext.*`
  classes execute on-device for real — including the Xtext-generated GAML parser.
- **`org.eclipse.osgi-patched.jar`** (`app/libs/`) carries a real OSGi framework
  implementation (`org.eclipse.osgi.internal.framework.SystemBundleActivator`,
  `org.osgi.framework.launch.Framework`, `FrameworkUtil`, …), patched only so it
  fits Android classloading instead of booting a full embedded OSGi container.
- **The heavy `gama.*` bundles** (including `gama.ui.display.opengl`/`java2d`) are
  dexed into the APK and kept by the proguard rules; they run — they are just never
  *instantiated* for desktop UI.

**What is missing.** The OSGi *platform* — the running container, extension
discovery, declarative services, auto-activation — and the AWT/Swing *toolkit* do not
exist on Android. They are replaced, not emulated. What *did* survive is every class
the engine actually executes: GAMA jars + real EMF/Xtext/OSGi-runtime from Maven.
Only the platform-adapter classes around them are stand-ins.

### 3.4 SVG rendering — a libs jar, not a source port (0 files)

Android has no built-in SVG renderer, and GAMA's display pipeline can render vector
assets. SVG support is delivered as a **dependency jar** — `app/libs/jsvg-2.0.0.jar` —
plus a build-time ASM patch (`StaxNewFactoryPatcher`, `app/build.gradle:215`). It is a
dependency, not app-source code, so there are no `.java` files for it in `app/src`.

### 3.5 `systems.uom.*` — no app-source stub (0 files)

JSR-385 unit constants are supplied by the bundled jars; there is no
`systems.uom.*` source stub in `app/src` in the current tree.

### 3.6 `gama.dev` — the GAMA-package constants shadows (6 files)

The engine's `gama.dev.DEBUG`/`FLAGS`/`STRINGS`/`THREADS`/`COUNTER`/`BANNER_CATEGORY`
helpers are referenced by the jar bytecode but are not bundled in this GAMA
distribution. These six `gama.dev.*` classes in `gama/dev/` are minimal Android
stubs (e.g. `DEBUG` routes to `android.util.Log`) that let `gama.api.gaml.types.Types`
and friends load. They are GAMA-package *constants*, not ports of engine logic.
`patchGamaJars` also strips a few engine classes that D8/ART mis-dex
(`SkillDescription`, `java.awt.geom.Line2D`/`GeneralPath`/`Area`, the
`org.xmlpull.v1.*` framework duplicates) at `app/build.gradle:123`.

---

## 4. Build pipeline: how the jars become an APK

```
libs/*.jar ──► patchGamaJars ──► compileDebugJavaWithJavac ──► dexBuilderDebug (D8) ──► APK
   (real        rewrite class-file versions >65 down to 65 (clear preview flag)
    jars)       restore pristine jars from libs/pristine/ when present
                strip D8-hostile classes from every jar (SkillDescription,
                  java.awt.geom.Line2D/GeneralPath/Area, org.xmlpull.v1.*)
                prepare guava-patched.jar (android-gradle variant)
                compile AndroidTaskWrapper (gama.api.runtime, tools/AndroidTaskWrapper.java)
                run ASM patchers (tools/patchers/*.java) — never fail the build:
                  - ParallelRunnerPatcher   (ForkJoinPool → Android ExecutorService)
                  - TypeSwitch/EnumSwitch   (Java-21 switch invokedynamic → dex-safe)
                  - SpiPatcher, MapProjectionPatcher (GeoTools projection SPI)
                  - EclipseCorePatcher      (org.eclipse.core → workspace stubs)
                  - GuavaJreCompat, StaxNewFactory (guava/jsvg classpath fixes)
                  - ColorBrewer, FontRenderContext, AwtFontMetrics (chart rendering)
                  - XtSDSAXProperty, XSDPluginBaseURL (GeoTools GML XSD/EMF fixes)
                  - WorldGlobalPatcher      (gama.core world globals)
                + app/src classes
```

Key points:

- **Linking** (`app/build.gradle`): the jars are first-class dependencies
  (`implementation fileTree(dir:'libs', ...)`). No reflection-based "trick" — D8 sees
  them as ordinary input classes.
- **`patchGamaJars`** (`app/build.gradle`): a build-time task (a) rewrites class-file
  versions that newer JDKs emit, (b) strips a short list of classes that D8/ART
  mis-dex or that Android provides itself, and (c) runs the list of ASM patchers
  (`tools/patchers/*.java`) for bytecode features that Android/D8 cannot run
  (ForkJoinPool, Java-21 switch packaging, GeoTools SPI/EMF lookups).
- **Ordering is critical**: `patchGamaJars` must run before D8, otherwise the APK
  contains the unpatched jar. Enforced via `task.dependsOn` on the compile/merge tasks.
- **Dexing**: D8 compiles jars + app classes together; the engine classes physically
  end up in the APK (Section 2.2).

---

## 5. The honest nuance: "untouched" vs "patched"

It is accurate to say the engine is the real jars — but it is not literally true that
every byte is byte-identical to upstream. Four categories:

1. **Runs as-is from the jar** — the vast majority of the engine (~3,200 classes
   across the `gama.*`/`gaml.*` jars; ~19,700 more in bundled third-party tooling).
   GAML grammar/parser, model builder, agent metamodel, experiment controller,
   display layers, draw statements, built-in functions, extensions.
2. **Bytecode-patched (ASM)** — a small number of classes/bytecode rewritten at
   build time because Android's runtime/dexer can't do what the desktop JVM does:
   - `ParallelAgentRunner` / parallel loops: Android `ForkJoinPool` is broken for
     this use, replaced with a regular `ExecutorService`
     (`ANDROID_PARALLEL_EXECUTOR`).
   - Java-21 `typeSwitch`/`enumSwitch` invokedynamic packaging that D8 cannot desugar
     (the `TypeSwitchPatcher`/`EnumSwitchPatcher`).
   - GeoTools projection SPI wiring (`SpiPatcher`, `MapProjectionPatcher`), the
     `org.eclipse.core` → Android workspace mapping (`EclipseCorePatcher`), and the
     XSD/EMF base-URL handling needed for GML files (`XtSDSAXPropertyPatcher`,
     `XSDPluginBaseURLPatcher`).
3. **Stripped/rebuilt at build time** — a few classes are removed from the jars
   (`SkillDescription`, `java.awt.geom.Line2D`/`GeneralPath`/`Area`, the
   `org.xmlpull.v1.*` framework duplicates) because D8/ART mis-dexes them or Android
   provides its own copy. One class is injected back into a jar:
   `AndroidTaskWrapper` (in package `gama.api.runtime`) is compiled from
   `tools/AndroidTaskWrapper.java` and replaces `ForkJoinTask.join()` on Android.
4. **GAMA-package constants shadows** — the six `gama.dev.*` classes (Section 3.6).

So: **the engine code is GAMA's; only the platform adapter is ours.** Roughly 135 of
the 164 app-source files are platform stubs (the `java.awt`/`javax.*` and
`org.eclipse`/`org.osgi` families, Section 3.2/3.3), 23 are the Android host shell,
and 6 are GAMA-package constants shadows (`gama/dev`).

---

## 6. How 3D rendering works on Android — and what it is *not*

One sentence: **the Android app renders 3D in software, on the CPU, with a custom
rasterizer that mimics the visual output of GAMA's desktop OpenGL renderer.**

It is **not** OpenGL, OpenGL ES, WebGL, JOGL, jMonkey, or any GPU-accelerated
pipeline. The GAMA desktop OpenGL bundle (`gama.ui.display.opengl`, with embedded
JOGL) ships *inside* the jars and the APK, but it can never run on Android: its
JOGL natives are desktop-only (macOS/Linux/Windows), so the `com.jogamp.*` classes
are not present at runtime (hence the `-dontwarn com.jogamp.*` rules in
`proguard-rules.pro`). The app never instantiates the desktop GL surface.

### 6.1 What happens at runtime

```
GAML:  display Sky type: 2d            display view type: 3d / opengl
                    │                                │
                    └──────────────┬─────────────────┘
                                   ▼
              LayeredDisplayData.is3D()   (set from the display `type:` facet)
                                   │
                                   ▼
        AndroidGuiHandler.createDisplaySurfaceFor(ldo)
        (gui/AndroidGuiHandler.java:130 — the one place the app
         chooses the surface; desktop GL surface is never built)
                                   │
                                   ▼
              AndroidDisplaySurface  (a custom View)
                setLayerType(LAYER_TYPE_SOFTWARE)
                triple-buffered ARGB_8888 bitmaps
                rendered on the simulation thread, blitted in onDraw()
                                   │
                                   ▼
        AndroidDisplayGraphics  (AbstractDisplayGraphics impl)
           │                                  │
      is3dMode()==false                  is3dMode()==true
           │                                  │
           ▼                                  ▼
      android.graphics.Canvas        AndroidScene3D (software
      (2D vector fill)               perspective rasterizer)
```

Every display — 2D **and** 3D — is the same custom `View` forced to CPU software
rendering (`LAYER_TYPE_SOFTWARE`). A snapshot bitmap is rendered on the simulation
thread at each cycle end and blitted on the UI thread. The engine-side surface
creation (`LayeredDisplayOutput.createSurface()`) always delegates back to the app
via `IGui.createDisplaySurfaceFor(...)` instead of building the desktop GL surface
(Section 4/5).

### 6.2 What is implemented (the software renderer)

The 3D pipeline is two files working together:

- **`display/AndroidScene3D.java`** — the rasterizer core (the "OpenGL replacement"):
  - `lookAt` / `perspective` matrix math, viewport and camera projection
  - depth sorting via the painter's algorithm (analogue of the GL depth test)
  - primitives: boxes/cuboids, polygons, spheres (adaptive tessellation), prisms,
    tapered meshes, lines, text, billboards, textured and flat-shaded polys
  - lighting: ambient / point / spot / directional lights (read from the experiment
    via reflection on `LayeredDisplayData`)
  - world axes, camera orbit / tilt / pan (gesture handlers in
    `AndroidDisplaySurface`), GAMA camera position / target / lens state
  - overlay layers composited on a separate bitmap and blended on top
- **`display/AndroidDisplayGraphics.java`** — the front-end translator: converts GAMA
  `draw` statements and display layers into `AndroidScene3D` prims
  (`drawShape3D`, `drawImage3D`, `addPrism3D`, `addSphereMesh`, `addTaperedMesh`,
  `addRotatedBox`, textured image drawing) or into plain `Canvas` calls for 2D.

In practice this reproduces the *visual* output of typical GAMA 3D models — geometry,
textures, lights, camera controls, overlays — at modest polygon counts.

### 6.3 What is missing compared to the desktop OpenGL (JOGL) renderer

| Missing | Desktop has | Consequence on Android |
|---------|-------------|------------------------|
| GPU acceleration | OpenGL/JOGL on the GPU | Everything runs single-threaded on the CPU; fill-rate and polygon count are the hard limit |
| Depth buffer (z-buffer) | GL depth test | Painter's-algorithm sorting breaks on intersecting/interpenetrating or transparent geometry |
| Real alpha blending | GL blending | Transparency only approximate (sort order + overlay bitmap) |
| Antialiasing / MSAA | GL multisampling | Jagged edges at low internal resolution |
| Per-pixel shading | GLSL shaders per material | Software renderer uses flat/varying fills only, no real materials |
| Shadows, PBR, environment/reflection | shader-based effects | Not present |
| Texture filtering / mip-maps | GL texture pipeline | Per-pixel software texture reads, no bilinear/anisotropic/mipmap quality |
| General mesh import / NURBS | GLU + indexed meshes | Only the coded primitives; no free-form geometry import |
| Render targets / post-processing | FBOs + filters | No filters, no glow/blur/etc.; output is plain ARGB bitmaps |
| Float/HDR buffers | float-frame buffers | No gamma/HDR pipeline or tone mapping |
| Desktop `GeometryDrawer`/`MeshDrawer` | rich grid/floor/elevation/text drawing | Only the common subset re-implemented |

There is also no `GLSurfaceView`, `android.opengl`, EGL, or Vulkan anywhere in the
app — the software path is the *only* path.

### 6.4 Why it is done this way

The desktop `JOGLRenderer` targets JOGL 2.6.0 immediate-mode GL2 and is not portable
to Android's OpenGL ES without a large rewrite; and JOGL's Android natives would
still have to be sourced. A software rasterizer reusing the same camera/light/prim
abstraction was the pragmatic route and gives visual parity for the models people
actually run on a phone.

**The seam where real OpenGL could plug in later**: `AndroidGuiHandler
.createDisplaySurfaceFor()` (`gui/AndroidGuiHandler.java:130`) — the single place the
app chooses which surface object to build per display. Swapping `AndroidDisplaySurface`
for a `GLSurfaceView` (still fed by `AndroidDisplayGraphics`'s prim-list API) is the
intended upgrade path if GPU 3D is ever needed.

---

## 7. FAQ

**Q: Did you translate/port GAMA's logic to Kotlin/Java Android code?**
No. There is no ported engine. Search `app/src/main/java` — the only GAMA-package
files are the six `gama/dev` constants shadows (Section 3.6). The engine logic is
inside the jars.

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
`assets/gama.library.jar` (a copy of the real library jar) holds the built-in GAMA
models; it is extracted to app storage at startup and supplemented by sample models
from engine bundles at build time. User models are loaded from the workspace folder on
device storage, not bundled in `assets/`.

**Q: Is `gama.extension.physics` excluded?**
No — it ships as a jar in `app/libs/` and is registered in the bundle manifest
(`gama.bundles`), so its pure-GAML parts load. However, the Box2D native bindings
(Libbulletjme) require platform `.so` libraries that are not present, so actual
physics simulation via the native Bullet library will fail at runtime; only pure-GAML
agent logic is available.

**Q: Is the 3D display real OpenGL?**
No — it is a custom CPU software rasterizer (`AndroidScene3D`) that mimics desktop
OpenGL output (Section 6). There is no OpenGL ES/GLSurfaceView/WebGL/JOGL at runtime:
GAMA's desktop GL bundle is inside the APK but its JOGL natives are desktop-only and
it is never instantiated. 2D and 3D both render through the same software `View`.

---

## 8. Quick reference

### File map

```
native-app/
├── app/
│   ├── build.gradle                 # deps + patchGamaJars task + ASM patchers
│   ├── libs/*.jar                   # the real GAMA jars + engine deps (~3,200 GAMA classes, ~22,900 class entries total)
│   └── src/main/
│       ├── assets/gama.library.jar  # built-in model library
│       ├── assets/gama.bundles      # engine bundle manifest
│       └── java/
│           ├── com/gama/nativeapp/…    (23) Android host shell
│           ├── java/awt/…              (60) Java SE stubs
│           ├── javax/swing/…           (11) Swing stubs
│           ├── javax/imageio/…         (11) ImageIO stubs
│           ├── org/eclipse/…           (49) Eclipse platform stubs
│           ├── org/osgi/…               (4) OSGi stubs
│           └── gama/dev/…               (6) GAMA-package constants shadows
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
