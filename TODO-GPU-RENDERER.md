# TODO: GPU 3D Renderer — opengl4 BasicShader Integration

## Status: Partial integration, rendering works but needs refinement

## What was done

### opengl4 module (mygama/gama) — COMMITTED
- Ported 30 files from JOGL to GLES 2.0 (Android-compatible)
- GLSL shaders downgraded from `#version 120` to `#version 100` (ES)
- Replaced GL instance calls with `GLES20` static methods
- Removed VAO (not in GLES 2.0), replaced NIO Buffers with ByteBuffer
- Replaced `gluProject` with pure-Java perspective division
- Replaced GLU tessellator with ear-clipping triangulation
- Built JAR: `gama.ui.display.opengl4_0.0.0.20260917.jar` (includes glsl/ shaders)
- Copied JAR + joml-1.10.5.jar to `native-app/app/libs/`

### GpuDisplayRenderer (gama-android) — COMMITTED
- Replaced inline GLSL shader compilation with `new BasicShader()`
- Raw column-major `float[]` passed directly to `glUniformMatrix4fv` (bypasses JOML `Matrix4f.set()` row-major interpretation)
- Fan triangulation for textured POLY prims (4+ vertices → triangles)
- Billboard quads bypass fan triangulation (already 6 verts = 2 tris)
- `AnimatedTexture` support in `getOrCreateTexture()`
- `GL_LEQUAL` depth test (needed for 2D scenes where polygons share z)
- Skips `fill==0` prims in solid batch (matches Canvas renderer behavior)
- Disabled GL lighting for solid batch (Canvas applies `litColor()` on CPU)
- Proper vertex attribute enable/disable between draw calls

## Known Issues (TODO)

### 1. Lighting not working properly
- **Problem:** BasicShader uses Phong model with per-fragment lighting. Canvas renderer applies `litColor()` on CPU before prims reach GL renderer. Solid batch now has `useLighting=false` (flat colors) to avoid double-lighting.
- **Fix needed:** Either:
  - (a) Apply `litColor()` on CPU in `captureGpuFrame()` before handing prims to GL renderer, then enable `useLighting=true` in shader — OR
  - (b) Pre-lit vertex colors in the solid batch buffer by calling `litColor()` per-prim when building `solidBuf` — OR
  - (c) Keep shader lighting but fix normals: many prims (from `addQuad`) have zero normals; need to compute face normals for all POLYs in `captureGpuFrame()`
- **Preferred:** Option (b) — bake lighting into vertex colors during solid batch build, matching what Canvas does

### 2. Trail / food / arrows visibility
- **Problem:** 852 solid + 201 textured + 3 lines are in the data but trail/food/arrows may not be visible.
- **Root cause (partially fixed):** `fill==0` prims were drawn as transparent-black with depth writes. Now skipped.
- **Still needs testing:** After the `fill==0` skip and flat-color fix, verify trail, food, and arrows render. If not, investigate:
  - Check if trail prims have correct fill colors (log first few `p.fill` values)
  - Check if textured prims (food) have valid textures
  - Check if line prims have correct vertex data

### 3. Texture rendering
- **Problem:** Textured prims (food, agents) may not show correctly.
- **Check:** The `drawTexturedPrim` method sets `useTexture=true` and binds the texture. Verify:
  - `getOrCreateTexture()` returns valid texture ID for `Bitmap` and `AnimatedTexture`
  - UV coordinates are correct (some prims may have `uv=null`)
  - Texture sampler `texture1` is bound to unit 0

### 4. Billboard rendering
- **Status:** Ants (billboards) now visible. The `drawBillboard` method creates a 6-vertex camera-facing quad.
- **Check:** Verify billboard size (`bbW`, `bbH`) and rotation (`bbRot`) work correctly

### 5. Alpha / transparency
- **Problem:** Trail layers need alpha blending to show transparent overlays.
- **Status:** `GL_BLEND` enabled with `SRC_ALPHA, ONE_MINUS_SRC_ALPHA`. Fragment shader preserves `baseColor.a`. Readback to Bitmap preserves alpha channel.
- **Verify:** Check that transparent prims composite correctly over the ground

### 6. JOML Matrix4f transpose issue
- **Workaround:** Raw `float[]` arrays passed directly to `glUniformMatrix4fv(loc, 1, false, arr, 0)` bypassing `Matrix4f.set()`/`Matrix4f.get()`
- **Still applies:** Any code using `Matrix4f` for matrix math must be aware that `Matrix4f.set(float[])` reads row-major but `matrix.get(float[])` outputs row-major, while `glUniformMatrix4fv(false)` expects column-major. The double-transposition cancels for individual matrices but REVERSES multiplication order in `projMat.mul(viewMat)`.

### 7. Performance
- Each textured prim is a separate draw call (per-draw VBO upload). OK for <200 prims but could be slow for dense scenes.
- Consider batching textured prims with the same texture into a single draw call.

### 8. CPU text pass
- Text prims drawn on CPU after GL readback via `Canvas.drawText()`. Works but creates a Canvas per frame.
- Consider rendering text as textured quads in the GL pipeline.

## Build Commands
```bash
# Build APK
cd native-app && ./gradlew assembleDebug

# Build APK (skip deps)
scripts/build_app.sh --repo /Users/hqnghi/git/gama-android --skip-deps

# Install
adb install -r native-app/app/build/outputs/apk/debug/app-debug.apk

# Check logs
adb logcat -d | grep GpuDisplay

# Build opengl4 JAR (if needed)
cd /tmp/opengl4-build && jar cf gama.ui.display.opengl4_0.0.0.20260917.jar gama/
cp gama.ui.display.opengl4_0.0.0.20260917.jar /Users/hqnghi/git/gama-android/native-app/app/libs/
```

## Key File Locations
| File | Purpose |
|------|---------|
| `native-app/app/src/main/java/com/gama/nativeapp/display/GpuDisplayRenderer.java` | Main GL renderer (THE primary file being modified) |
| `native-app/app/src/main/java/com/gama/nativeapp/display/AndroidScene3D.java` | Scene graph, Prim class, Canvas rendering path, `captureGpuFrame()` |
| `native-app/app/src/main/java/com/gama/nativeapp/display/GpuSnapshot.java` | Immutable snapshot passed to GL thread |
| `native-app/app/src/main/java/com/gama/nativeapp/display/AndroidDisplayGraphics.java` | Calls `captureGpuFrame()`, manages scene lifecycle |
| `native-app/app/src/main/java/com/gama/nativeapp/display/AndroidDisplaySurface.java` | EGL surface, `useGpu3D()` flag, `gpuRenderer.renderSync()` |
| `native-app/app/libs/gama.ui.display.opengl4_0.0.0.20260917.jar` | opengl4 JAR with BasicShader |
| `native-app/app/libs/joml-1.10.5.jar` | JOML math library |
| `mygama/gama/gama.ui.display.opengl4/src/.../shaders/BasicShader.java` | Target shader class |
| `mygama/gama/gama.ui.display.opengl4/src/.../shaders/AbstractShader.java` | Base class, loads shaders from resources |
| `mygama/gama/gama.ui.display.opengl4/src/.../shaders/glsl/basic.vert` | GLSL ES 1.00 vertex shader |
| `mygama/gama/gama.ui.display.opengl4/src/.../shaders/glsl/basic.frag` | GLSL ES 1.00 fragment shader (Phong) |

## Rendering Pipeline
1. Sim thread calls `AndroidDisplayGraphics` → `scene3d.captureGpuFrame()` → `GpuSnapshot`
2. Sim thread calls `gpuRenderer.renderSync(snap, targetBitmap)` → blocks
3. GL thread: `renderFrame(snap, targetBitmap)`
   - Clear (bg color from `snap.bgColor`)
   - Solid batch: non-textured POLYs with `fill!=0` → `drawSolidBatch()` (flat colors, no lighting)
   - Lines: LINE prims → `drawLineBatch()` (MVP only)
   - Textured: textured POLYs → `drawTexturedPrim()` (per-draw, fan triangulation)
   - Billboards: BILLBOARD prims → `drawBillboard()` (camera-facing quad, pre-triangulated)
   - `glReadPixels` → RGBA→ARGB conversion + Y-flip → `targetBitmap`
   - CPU text pass: `drawTextPrims()` (Canvas overlay)
4. Sim thread resumes, paints `targetBitmap` to screen
