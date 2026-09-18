# TODO: GPU 3D Renderer — opengl4 BasicShader Integration

## Status: GL abstraction layer complete, ready for device testing

## Architecture: GL Abstraction Layer

### New files in mygama/gama (opengl4 module)
```
gama.ui.display.opengl4/src/gama/ui/display/opengl4/renderer/gl/
├── GLWrapper.java         ← Interface: 78 GL methods
├── GLConstants.java       ← All GL constants (no more GL4.GL_* imports)
├── JoglGLWrapper.java     ← JOGL impl: delegates to GL4 instance
├── GLWrapperFactory.java  ← Platform detection
gama.ui.display.opengl4/src-android/gama/ui/display/opengl4/renderer/gl/
└── Gles2GLWrapper.java    ← GLES2 impl: delegates to GLES20 static methods
```

### How it works
1. `travis/build.sh` builds the full platform — JOGL-only (Maven passes)
2. Android JAR compiled separately: `src/` + `src-android/` with `android.jar` on classpath
3. `GpuDisplayRenderer` creates `new BasicShader(new Gles2GLWrapper())`
4. At runtime on Android, only GLES2 code path executes

### Files modified in mygama/gama
- `AbstractShader.java` — `GL4 gl` → `GLWrapper gl`
- `BasicShader.java` — constructor takes `GLWrapper`
- `FrameBufferObject.java` — `GL4 gl` → `GLWrapper gl`
- `AbstractPostprocessingShader.java` — constructor takes `GLWrapper`
- `KeystoneShaderProgram.java` — constructor takes `GLWrapper`
- `OpenGL.java` — `GLWrapper gl` field, `getGLWrapper()` method
- `GeometryCache.java` — GL4.GL_* → GLConstants.*
- `MeshDrawer.java` — GL4.GL_* → GLConstants.*
- `OverlayLayerObject.java` — GL4.GL_* → GLConstants.*
- Helpers (KeystoneHelper, CameraHelper, PickingHelper, AbstractRendererHelper) — GLConstants.*

## What was done

### opengl4 module (mygama/gama) — COMMITTED
- GL abstraction layer (GLWrapper, GLConstants, JoglGLWrapper, Gles2GLWrapper)
- GLSL shaders: `#version 100` (ES compatible)
- Maven build passes (`travis/build.sh` → BUILD SUCCESS)
- Gles2GLWrapper in `src-android/` (excluded from desktop build)

### GpuDisplayRenderer (gama-android) — COMMITTED
- Uses `new BasicShader(new Gles2GLWrapper())`
- Raw column-major `float[]` passed directly to `glUniformMatrix4fv`
- Fan triangulation for textured POLY prims
- Billboard quads bypass fan triangulation
- `AnimatedTexture` support in `getOrCreateTexture()`
- `GL_LEQUAL` depth test for 2D scenes
- Skips `fill==0` prims (matches Canvas renderer)
- Disabled GL lighting for solid batch (flat colors)
- Proper vertex attribute enable/disable between draw calls

## Build Commands

### opengl4 JAR (from mygama/gama)
```bash
cd /Users/hqnghi/git/mygama/gama

# 1. Build full GAMA platform (Maven)
bash travis/build.sh

# 2. Compile Android GL JAR (minimal: 10 files)
ANDROID_JAR=$(find ~/Library/Android/sdk/platforms -name "android.jar" | sort -V | tail -1)
JOML_JAR="/Users/hqnghi/git/gama-android/native-app/app/libs/joml-1.10.5.jar"
REPO="gama.product/target/configuration/target/repository/plugins"
PWD=$(pwd)

cat > /tmp/opengl4-android-sources.txt << 'EOF'
gama.ui.display.opengl4/src/gama/ui/display/opengl4/renderer/gl/GLWrapper.java
gama.ui.display.opengl4/src/gama/ui/display/opengl4/renderer/gl/GLConstants.java
gama.ui.display.opengl4/src/gama/ui/display/opengl4/renderer/gl/JoglGLWrapper.java
gama.ui.display.opengl4/src-android/gama/ui/display/opengl4/renderer/gl/Gles2GLWrapper.java
gama.ui.display.opengl4/src/gama/ui/display/opengl4/renderer/gl/GLWrapperFactory.java
gama.ui.display.opengl4/src/gama/ui/display/opengl4/renderer/shaders/AbstractShader.java
gama.ui.display.opengl4/src/gama/ui/display/opengl4/renderer/shaders/BasicShader.java
gama.ui.display.opengl4/src/gama/ui/display/opengl4/renderer/shaders/AbstractPostprocessingShader.java
gama.ui.display.opengl4/src/gama/ui/display/opengl4/renderer/shaders/KeystoneShaderProgram.java
gama.ui.display.opengl4/src/gama/ui/display/opengl4/renderer/shaders/FrameBufferObject.java
EOF

CP="$ANDROID_JAR:$JOML_JAR"
CP="$CP:$PWD/gama.ui.display.opengl/libs/jogl 2.6.0/jogl-all.jar"
CP="$CP:$PWD/gama.ui.display.opengl/libs/jogl 2.6.0/gluegen-rt.jar"
CP="$CP:$PWD/gama.dev/target/gama.dev-0.0.0-SNAPSHOT.jar"
CP="$CP:$PWD/gama.api/target/gama.api-0.0.0-SNAPSHOT.jar"

rm -rf /tmp/opengl4-build && mkdir -p /tmp/opengl4-build
javac --release 16 -cp "$CP" -d /tmp/opengl4-build @/tmp/opengl4-android-sources.txt

# 3. Copy GLSL shaders
cp gama.ui.display.opengl4/src/gama/ui/display/opengl4/renderer/shaders/glsl/* \
   /tmp/opengl4-build/gama/ui/display/opengl4/renderer/shaders/glsl/

# 4. Package JAR
cd /tmp/opengl4-build && jar cf gama.ui.display.opengl4_0.0.0.20260917.jar gama/
cp gama.ui.display.opengl4_0.0.0.20260917.jar \
   /Users/hqnghi/git/gama-android/native-app/app/libs/
```

### Android APK
```bash
cd /Users/hqnghi/git/gama-android/native-app && ./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -d | grep GpuDisplay
```

## Key File Locations
| File | Purpose |
|------|---------|
| `gama-android/.../GpuDisplayRenderer.java` | Main GL renderer |
| `gama-android/.../AndroidScene3D.java` | Scene graph, Prim class, `captureGpuFrame()` |
| `gama-android/.../GpuSnapshot.java` | Immutable snapshot passed to GL thread |
| `mygama/.../renderer/gl/GLWrapper.java` | GL abstraction interface |
| `mygama/.../renderer/gl/Gles2GLWrapper.java` | Android GLES2 implementation |
| `mygama/.../renderer/shaders/BasicShader.java` | Main shader (Phong lighting) |
| `mygama/.../shaders/glsl/basic.vert` | GLSL ES 1.00 vertex shader |
| `mygama/.../shaders/glsl/basic.frag` | GLSL ES 1.00 fragment shader |

## Known Issues (TODO)
1. **Lighting** — Bake `litColor()` into vertex colors in solid batch
2. **Trail/food/arrows** — Verify visibility after fill==0 fix
3. **Performance** — Batch textured prims with same texture
4. **Test on device** — Connect device and run ant foraging model
