# AGENTS.md — Build & Architecture Guide for gama-android

## Quick Start (Fresh Machine)

```bash
scripts/build_app.sh
```

This single command handles everything: downloads JDK 21, Android SDK, GAMA jars from
GitHub releases, patches them, builds the extension, and produces an APK.

Output: `native-app/app/build/outputs/apk/debug/app-debug.apk`

For release builds:
```bash
scripts/build_app.sh   # then:
cd native-app && ./gradlew assembleRelease
```

## CRITICAL: JARs Are Not In Git

The `native-app/app/libs/` directory contains ~120 JARs that are **gitignored** (`*.jar`
in `.gitignore`). A fresh `git clone` gives you an empty `libs/`. You MUST either:

1. Run `scripts/build_app.sh` (downloads them automatically), or
2. Download `native-app-deps.tar.gz` from https://github.com/hqnghi88/gama-android/releases
   and extract to `native-app/app/libs/` (including `pristine/` subdirectory)

**Never manually create or edit JARs in libs/.** The build system restores from
`libs/pristine/` before patching. Always use the build script.

## Project Structure

```
gama-android/
├── native-app/                    # Android project root
│   ├── app/
│   │   ├── build.gradle           # Main build config (patchGamaJars task, dependencies)
│   │   ├── libs/                  # GAMA JARs (gitignored, downloaded by build_app.sh)
│   │   │   ├── pristine/          # Original JARs before patching (also gitignored)
│   │   │   ├── gama.ui.display.opengl4_*.jar  # GL abstraction layer (Key JAR)
│   │   │   └── ...                # ~120 other GAMA JARs
│   │   └── src/main/java/com/gama/nativeapp/
│   │       ├── ModelNavigatorActivity.java    # Launcher / model browser
│   │       ├── ExperimentActivity.java        # Main experiment runner (toolbar, controls)
│   │       └── display/
│   │           ├── AndroidDisplaySurface.java # Display surface (triple-buffer, GPU/CPU mode)
│   │           ├── AndroidDisplayGraphics.java# Canvas wrapper (2D rendering)
│   │           ├── AndroidScene3D.java        # 3D scene (CPU rasterizer, prim management)
│   │           ├── GpuDisplayRenderer.java    # GPU renderer (opengl4→GLES2.0 port)
│   │           ├── GpuSnapshot.java           # Snapshot data (prims, matrices, lights)
│   │           └── GamaAndroidDisplaySetup.java
│   └── build.gradle               # Root build (AGP 9.0.0)
├── scripts/
│   ├── build_app.sh               # Full reproducible build from fresh clone
│   └── build_extension.sh         # Build androidsensor extension JAR
└── gama.extension.androidsensor/  # Sensor extension source
```

## Build Commands

```bash
# From fresh clone (handles everything):
scripts/build_app.sh

# Gradle-only (requires JARs already in libs/):
cd native-app
./gradlew assembleDebug          # Debug APK
./gradlew assembleRelease        # Release APK

# Patch JARs only:
./gradlew app:patchGamaJars

# Clean:
./gradlew clean
```

**Gradle version:** 9.1.0 (wrapper included)
**AGP version:** 9.0.0
**Min SDK:** 24, **Target SDK:** 34

## Architecture: CPU vs GPU Renderer

The app has two 3D rendering paths, selectable via toolbar toggle (default: CPU):

### CPU Renderer (default)
- Uses `AndroidScene3D.render()` which draws to a software `Canvas`
- `drawPath()`, `drawRect()`, `drawBitmap()` — flat colors, no Phong lighting
- **HWUI-accelerated** (LAYER_TYPE_SOFTWARE was removed) — Canvas ops go through
  Skia's GPU backend automatically
- Triggered by: toolbar "3D:CPU" button, or any non-GPU mode

### GPU Renderer (opengl4 → GLES2.0 port)
- Uses `GpuDisplayRenderer` which renders via OpenGL ES 2.0 on Android
- Inherits opengl4's `BasicShader` (Phong lighting, textures, depth testing)
- Shader source: GLES2-compatible GLSL (`#version 100`, `attribute`/`varying`)
- Modes:
  - **Window mode** (TextureView): renders directly to screen, no `glReadPixels`
  - **Pbuffer fallback**: renders to offscreen buffer, reads back at 50% resolution
- Triggered by: toolbar "3D:GPU" button

### Key Files

| File | Role |
|------|------|
| `GpuDisplayRenderer.java` | GPU renderer: GLES2.0, BasicShader, Phong lighting, TextureView support |
| `GpuSnapshot.java` | Data snapshot: prims list, view/proj matrices, lights, bgColor |
| `AndroidScene3D.java` | CPU renderer: `render()` draws to Canvas, `captureGpuFrame()` creates snapshot |
| `AndroidDisplaySurface.java` | Triple-buffer display, `submitGpuFrame()`/`renderFrame()`, GPU/CPU mode toggle |
| `AndroidDisplayGraphics.java` | Canvas wrapper for 2D drawing |
| `ExperimentActivity.java` | UI: toolbar, renderer mode toggle, model controls |

## OpenGL4 Module (upstream source)

The GPU renderer inherits from `gama.ui.display.opengl4` in the main GAMA repo:
- Source: `/Users/hqnghi/git/mygama/gama/gama.ui.display.opengl4/`
- Key classes: `BasicShader`, `LayerObject`, `GLWrapper`, `GLConstants`
- Built into JAR: `gama.ui.display.opengl4_*.jar` (in `libs/`)
- GLSL shaders are GLES2-compatible on Android (`#version 100`)
- Desktop source files remain GL4 (`#version 410 core`) for Maven build

**IMPORTANT:** Do NOT copy opengl4 code. Work directly in the opengl4 source tree
for desktop changes, then rebuild the JAR for Android.

## Rendering Pipeline

```
Model simulation (SIM thread)
  → AndroidScene3D.captureGpuFrame() or .render()
    → GPU: GpuDisplayRenderer.renderSync(snap, bitmap)
       → BasicShader.start() → set uniforms → draw prims → basicShader.stop()
       → Window: eglSwapBuffers()  |  Pbuffer: glReadPixels() → bitmap
    → CPU: Canvas.drawPath()/drawRect() (HWUI-accelerated)
  → AndroidDisplaySurface.submitGpuFrame() or renderFrame()
    → Triple-buffer swap → UI thread invalidate → onDraw() → canvas.drawBitmap()
```

## Prim Types

| Type | Value | Notes |
|------|-------|-------|
| POLY | 0 | Polygon (convex/concave), stencil fill for concave |
| LINE | 1 | Line segments (GL_LINES) |
| TEXT | 2 | Text labels (drawn to overlay bitmap) |
| BILLBOARD | 3 | Camera-facing sprites |

- `fill=0` means "no fill" → falls back to border color or white
- `altZ` = average world-space Z (for depth sorting)
- `depth` = view-space Z (for depth buffer)
- `layerIdx` = layer stack index

## Pitfalls

1. **JARs are gitignored** — fresh clone has no `libs/`. Use `scripts/build_app.sh`.
2. **JOML Matrix4f** — `set(float[])` is row-major but view/proj are column-major.
   Fix: bypass JOML, pass raw column-major arrays via `glUniformMatrix4fv(false)`.
3. **BasicShader attribute layout** — 0=aPos, 1=aColor, 2=aTexCoord, 3=aNormal.
   Stride: 48 bytes (pos3+col4+uv2+norm3).
4. **BasicShader does NOT bind texture sampler** — must set `glUniform1i(texture1, 0)`.
5. **Samsung highp** — Adreno GPUs require `highp` for `fragPos`, `lightPosition`,
   `viewPos` in fragment shader.
6. **Zero normals** — prims with `lnx=lny=lnz=0` get default `(0,0,1)`.
7. **HARDWARE bitmaps** — Texture creation converts non-ARGB_8888 bitmaps.
8. **`setLayerType(LAYER_TYPE_SOFTWARE)` was removed** — HWUI now accelerates Canvas.
   Do NOT re-add it.
9. **`scene3d` is private final** — created once, reused across sessions.
   Call `resetForNewModel()` to clear state.
10. **`staticCache`** — must be cleared between model sessions.

## Releasing

```bash
# Build release APK
cd native-app && ./gradlew assembleRelease

# Commit, tag, push, create GitHub release
git add -A && git commit -m "description"
git tag -a vX.Y-description -m "Release notes"
git push origin main --tags

# Create GitHub release with APK
gh release create vX.Y-description \
  native-app/app/build/outputs/apk/release/app-release.apk \
  --title "vX.Y: title" \
  --notes "## Changes\n- bullet points"
```
