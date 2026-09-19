package com.gama.nativeapp.display;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLUtils;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import android.opengl.GLES20;

import gama.ui.display.opengl4.renderer.shaders.BasicShader;
import gama.ui.display.opengl4.renderer.gl.Gles2GLWrapper;

/**
 * GPU-accelerated 3D renderer using OpenGL ES 2.0.
 * <p>
 * Renders the {@link AndroidScene3D.Prim} scene graph on a dedicated GL thread
 * with Phong lighting (ambient + directional/point/spot), texture mapping,
 * depth testing, and backface culling. Primitives are drawn in insertion order
 * with per-prim z-offsets and GL depth buffer, matching desktop GAMA's approach.
 * <p>
 * Thread model: the simulation thread calls {@link #renderSync} which blocks
 * until the GL thread finishes rendering into the target bitmap (triple-buffer
 * integration).
 */
public final class GpuDisplayRenderer {

    private static final String TAG = "GpuDisplayRenderer";

    private final GlThread glThread = new GlThread();

    // Sync state
    private GpuSnapshot pendingSnap;
    private Bitmap pendingTarget;
    private java.util.concurrent.CountDownLatch pendingLatch;
    private java.util.concurrent.CountDownLatch initLatch = new java.util.concurrent.CountDownLatch(1);

    // GL resources (owned by GL thread)
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private BasicShader basicShader;
    private int lineProgram;
    private int solidVbo, lineVbo;
    private int[] intBuf;

    private static final float[] IDENTITY4 = {1,0,0,0, 0,1,0,0, 0,0,1,0, 0,0,0,1};

    private int renderW, renderH;
    private boolean initialized = false;

    // Texture cache: Bitmap identity → GL texture name
    private final Map<Bitmap, Integer> texCache = new HashMap<>();
    private int texVbo;

    // CPU text paint (used after readback, on GL thread)
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public void init(int w, int h) {
        renderW = w;
        renderH = h;
        glThread.setDaemon(true);
        glThread.setName("GpuDisplay-GL");
        glThread.start();
        try { initLatch.await(); } catch (InterruptedException ignored) {}
        if (!initialized) {
            Log.e(TAG, "GL init failed; GPU rendering disabled");
        }
    }

    /**
     * Renders the given snapshot synchronously into targetBitmap.
     * Called from the simulation thread; blocks until the GL thread finishes.
     * targetBitmap must be mutable ARGB_8888 and sized (snapshot.viewW × snapshot.viewH).
     */
    public void renderSync(GpuSnapshot snap, Bitmap targetBitmap) {
        if (!initialized || snap == null || snap.prims.isEmpty()) return;
        synchronized (this) {
            pendingSnap = snap;
            pendingTarget = targetBitmap;
            pendingLatch = new java.util.concurrent.CountDownLatch(1);
            notify();
        }
        try { pendingLatch.await(); } catch (InterruptedException ignored) {}
    }

    public void shutdown() {
        glThread.running = false;
        synchronized (this) { notify(); }
        try { glThread.join(2000); } catch (InterruptedException ignored) {}
    }

    // ── EGL / GL init ──────────────────────────────────────────────────

    private void initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay failed");
        int[] vers = new int[2];
        EGL14.eglInitialize(eglDisplay, vers, 0, vers, 1);

        int[] cfgAttribs = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_STENCIL_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, cfgAttribs, 0, configs, 0, 1, numConfigs, 0);
        if (numConfigs[0] == 0) throw new RuntimeException("eglChooseConfig failed");

        int[] pbAttribs = { EGL14.EGL_WIDTH, renderW, EGL14.EGL_HEIGHT, renderH, EGL14.EGL_NONE };
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw new RuntimeException("eglCreatePbufferSurface failed");

        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext failed");

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
            throw new RuntimeException("eglMakeCurrent failed");

        intBuf = new int[renderW * renderH];
    }

    private void destroyEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
            EGL14.eglTerminate(eglDisplay);
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY;
        eglContext = EGL14.EGL_NO_CONTEXT;
        eglSurface = EGL14.EGL_NO_SURFACE;
    }

    // ── Shader compilation ─────────────────────────────────────────────

    private static int compileShader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(s);
            GLES20.glDeleteShader(s);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return s;
    }

    private static int linkProgram(int vertShader, int fragShader) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, vertShader);
        GLES20.glAttachShader(p, fragShader);
        GLES20.glLinkProgram(p);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new RuntimeException("Program link failed: " + log);
        }
        return p;
    }

    private void initShaders() {
        // ── Solid/textured shader: reuse opengl4 BasicShader ─────────
        basicShader = new BasicShader(new Gles2GLWrapper());
        int bsErr = GLES20.glGetError();
        Log.i(TAG, "BasicShader created: programID=" + basicShader.getProgramID() + " glErr=0x" + Integer.toHexString(bsErr));

        // ── Line shader ──────────────────────────────────────────────
        String lineVert =
            "attribute vec3 aPos;\n" +
            "attribute vec4 aColor;\n" +
            "uniform mat4 uMVP;\n" +
            "varying vec4 vColor;\n" +
            "void main(){\n" +
            "  vColor = aColor;\n" +
            "  gl_Position = uMVP * vec4(aPos, 1.0);\n" +
            "}\n";
        String lineFrag =
            "precision mediump float;\n" +
            "varying vec4 vColor;\n" +
            "void main(){ gl_FragColor = vColor; }\n";

        int lv = compileShader(GLES20.GL_VERTEX_SHADER, lineVert);
        int lf = compileShader(GLES20.GL_FRAGMENT_SHADER, lineFrag);
        lineProgram = linkProgram(lv, lf);
        GLES20.glDeleteShader(lv);
        GLES20.glDeleteShader(lf);

        // VBOs
        int[] bufs = new int[3];
        GLES20.glGenBuffers(3, bufs, 0);
        solidVbo = bufs[0];
        lineVbo = bufs[1];
        texVbo = bufs[2];

        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glLineWidth(1.0f);
    }

    // ── Per-frame render ───────────────────────────────────────────────

    private void renderFrame(GpuSnapshot snap, Bitmap targetBitmap) {
        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return;

            // Draw prims in insertion order (matching desktop GAMA).
            // Desktop GAMA does NOT sort primitives — it draws them in execution
            // order and relies on GL depth buffer + per-object z-increment for
            // correct occlusion at any camera angle.
            List<AndroidScene3D.Prim> prims = snap.prims;

            int nSolid = 0, nTex = 0, nLine = 0, nBill = 0, nText = 0;
            for (AndroidScene3D.Prim p : prims) {
                switch (p.kind) {
                    case AndroidScene3D.POLY: if (p.texture != null) nTex++; else nSolid++; break;
                    case AndroidScene3D.LINE: nLine++; break;
                    case AndroidScene3D.BILLBOARD: nBill++; break;
                    case AndroidScene3D.TEXT: nText++; break;
                }
            }
            Log.d(TAG, "prims: solid=" + nSolid + " tex=" + nTex + " line=" + nLine + " bill=" + nBill + " text=" + nText + " lights=" + snap.lights.length);

            int w = snap.viewW, h = snap.viewH;
            GLES20.glViewport(0, 0, w, h);

            GLES20.glEnable(GLES20.GL_DEPTH_TEST);
            GLES20.glDepthFunc(GLES20.GL_LEQUAL);

            float bgR = ((snap.bgColor >> 16) & 0xFF) / 255f;
            float bgG = ((snap.bgColor >> 8) & 0xFF) / 255f;
            float bgB = (snap.bgColor & 0xFF) / 255f;
            GLES20.glClearColor(bgR, bgG, bgB, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

            // Matrices: column-major, passed directly to GL
            float[] mvp = new float[16];
            mat4Mul(mvp, snap.proj, snap.view);

            // Identity model matrix
            float[] NORMAL_ID = {1,0,0, 0,1,0, 0,0,1};

            // Compute camera position from view matrix for CPU-side lighting
            float[] vv = snap.view;
            float cvx = -(vv[0]*vv[12] + vv[1]*vv[13] + vv[2]*vv[14]);
            float cvy = -(vv[4]*vv[12] + vv[5]*vv[13] + vv[6]*vv[14]);
            float cvz = -(vv[8]*vv[12] + vv[9]*vv[13] + vv[10]*vv[14]);

            // ── Render ALL prims in insertion order with per-prim zOffset ──
            // Matching desktop GAMA: each object gets a cumulative z-increment
            // so the GL depth buffer handles occlusion correctly at any angle.
            int totalPrims = prims.size();
            float zStep = totalPrims > 1 ? 2.0f / totalPrims : 0f;
            for (int pi = 0; pi < totalPrims; pi++) {
                AndroidScene3D.Prim p = prims.get(pi);
                float zOffset = 1.0f - pi * zStep;

                if (p.kind == AndroidScene3D.POLY && p.texture == null) {
                    drawSingleSolidPrim(p, pi, totalPrims, snap, mvp, IDENTITY4, cvx, cvy, cvz, zOffset);
                    if (p.border != 0 && p.border != p.fill) {
                        int nv = p.v.length / 3;
                        float[] borderBuf = new float[nv * 2 * 7];
                        int li = 0;
                        float br = ((p.border >> 16) & 0xFF) / 255f;
                        float bg = ((p.border >> 8) & 0xFF) / 255f;
                        float bb = (p.border & 0xFF) / 255f;
                        float ba = ((p.border >> 24) & 0xFF) / 255f;
                        for (int i = 0; i < nv; i++) {
                            int ni = (i + 1) % nv;
                            borderBuf[li++] = p.v[i * 3]; borderBuf[li++] = p.v[i * 3 + 1]; borderBuf[li++] = p.v[i * 3 + 2];
                            borderBuf[li++] = br; borderBuf[li++] = bg; borderBuf[li++] = bb; borderBuf[li++] = ba;
                            borderBuf[li++] = p.v[ni * 3]; borderBuf[li++] = p.v[ni * 3 + 1]; borderBuf[li++] = p.v[ni * 3 + 2];
                            borderBuf[li++] = br; borderBuf[li++] = bg; borderBuf[li++] = bb; borderBuf[li++] = ba;
                        }
                        drawLineBatch(borderBuf, nv * 2, mvp);
                    }
                } else if (p.kind == AndroidScene3D.POLY && p.texture != null) {
                    drawTexturedPrim(p, snap, IDENTITY4, NORMAL_ID, true, zOffset);
                } else if (p.kind == AndroidScene3D.BILLBOARD) {
                    drawBillboard(p, snap, IDENTITY4, NORMAL_ID, zOffset);
                } else if (p.kind == AndroidScene3D.LINE) {
                    int lineVertCount = 2;
                    float[] lineBuf = new float[lineVertCount * 7];
                    int li = 0;
                    float r = ((p.border >> 16) & 0xFF) / 255f;
                    float g = ((p.border >> 8) & 0xFF) / 255f;
                    float b = (p.border & 0xFF) / 255f;
                    float a = ((p.border >> 24) & 0xFF) / 255f;
                    lineBuf[li++] = p.v[0]; lineBuf[li++] = p.v[1]; lineBuf[li++] = p.v[2];
                    lineBuf[li++] = r; lineBuf[li++] = g; lineBuf[li++] = b; lineBuf[li++] = a;
                    lineBuf[li++] = p.v[3]; lineBuf[li++] = p.v[4]; lineBuf[li++] = p.v[5];
                    lineBuf[li++] = r; lineBuf[li++] = g; lineBuf[li++] = b; lineBuf[li++] = a;
                    drawLineBatch(lineBuf, lineVertCount, mvp);
                }
            }

            // (Borders are drawn per-prim inside the sorted loop below)

            // ── GL error check ──────────────────────────────────────
            int glErr = GLES20.glGetError();
            if (glErr != GLES20.GL_NO_ERROR) Log.e(TAG, "GL error: 0x" + Integer.toHexString(glErr));

            // ── Readback → Bitmap ───────────────────────────────────
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                    IntBuffer.wrap(intBuf));
            // Convert RGBA → ARGB (Android Bitmap format) + flip Y
            // glReadPixels returns rows bottom-to-top; Bitmap expects top-to-bottom.
            // On little-endian, IntBuffer reads bytes as: int = A<<24 | B<<16 | G<<8 | R
            int[] row = new int[w];
            for (int y = 0; y < h / 2; y++) {
                int topOff = y * w, botOff = (h - 1 - y) * w;
                // Convert and swap top row
                for (int x = 0; x < w; x++) {
                    int rgba = intBuf[topOff + x];
                    row[x] = ((rgba & 0xFF) << 16) | (((rgba >> 8) & 0xFF) << 8)
                            | ((rgba >> 16) & 0xFF) | (rgba & 0xFF000000);
                }
                // Convert bottom row
                for (int x = 0; x < w; x++) {
                    int rgba = intBuf[botOff + x];
                    intBuf[topOff + x] = ((rgba & 0xFF) << 16) | (((rgba >> 8) & 0xFF) << 8)
                            | ((rgba >> 16) & 0xFF) | (rgba & 0xFF000000);
                }
                // Put converted top row into bottom position
                for (int x = 0; x < w; x++) {
                    intBuf[botOff + x] = row[x];
                }
            }
            // If h is odd, convert the middle row (no swap needed)
            if ((h & 1) == 1) {
                int mid = (h / 2) * w;
                for (int x = 0; x < w; x++) {
                    int rgba = intBuf[mid + x];
                    intBuf[mid + x] = ((rgba & 0xFF) << 16) | (((rgba >> 8) & 0xFF) << 8)
                            | ((rgba >> 16) & 0xFF) | (rgba & 0xFF000000);
                }
            }
            targetBitmap.eraseColor(0);
            targetBitmap.setPixels(intBuf, 0, w, 0, 0, w, h);

            // ── CPU text pass ───────────────────────────────────────
            drawTextPrims(snap, targetBitmap);

        } catch (Throwable t) {
            Log.e(TAG, "renderFrame failed", t);
        }
    }

    // ── Ear-clipping triangulation ──────────────────────────────────────

    /** Compute signed area (2D, XY plane). Positive = CCW winding. */
    private static float signedArea2D(float[] v, int n) {
        float area = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            area += v[i * 3] * v[j * 3 + 1] - v[j * 3] * v[i * 3 + 1];
        }
        return area * 0.5f;
    }

    /** Check if point p is inside triangle (a,b,c) on XY plane. */
    private static boolean pointInTriangle(float px, float py,
                                           float ax, float ay, float bx, float by, float cx, float cy) {
        float d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by);
        float d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy);
        float d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay);
        boolean hasNeg = (d1 < 0) || (d2 < 0) || (d3 < 0);
        boolean hasPos = (d1 > 0) || (d2 > 0) || (d3 > 0);
        return !(hasNeg && hasPos);
    }

    /** Check if vertex at index curr is a convex ear among the live vertices. */
    private static boolean isEar(float[] v, ArrayList<Integer> live, int pi, int ci, int ni, boolean ccw) {
        int prev = live.get(pi), curr = live.get(ci), next = live.get(ni);
        float ax = v[curr * 3], ay = v[curr * 3 + 1];
        float bx = v[prev * 3], by = v[prev * 3 + 1];
        float cx = v[next * 3], cy = v[next * 3 + 1];
        float cross = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
        boolean convex = ccw ? cross > 0 : cross < 0;
        if (!convex) return false;
        for (int k = 0; k < live.size(); k++) {
            int idx = live.get(k);
            if (idx == prev || idx == curr || idx == next) continue;
            if (pointInTriangle(v[idx * 3], v[idx * 3 + 1], ax, ay, bx, by, cx, cy)) return false;
        }
        return true;
    }

    /** Triangulate a simple polygon using ear clipping. Returns triangle indices (triplets). */
    static int[] triangulate(float[] v, int n) {
        if (n < 3) return new int[0];
        ArrayList<Integer> live = new ArrayList<>(n);
        for (int i = 0; i < n; i++) live.add(i);
        boolean ccw = signedArea2D(v, n) >= 0;
        ArrayList<Integer> result = new ArrayList<>(n);
        int safety = n * 3;
        while (live.size() > 2 && safety-- > 0) {
            boolean earFound = false;
            int sz = live.size();
            for (int i = 0; i < sz; i++) {
                int prev = (i + sz - 1) % sz;
                int curr = i;
                int next = (i + 1) % sz;
                if (isEar(v, live, prev, curr, next, ccw)) {
                    result.add(live.get(prev));
                    result.add(live.get(curr));
                    result.add(live.get(next));
                    live.remove(curr);
                    earFound = true;
                    break;
                }
            }
            if (!earFound) break;
        }
        int[] triIdx = new int[result.size()];
        for (int i = 0; i < result.size(); i++) triIdx[i] = result.get(i);
        return triIdx;
    }

    // ── Single-prim solid draw ──────────────────────────────────────

    /**
     * Draw a single solid POLY prim. Projects vertices to 2D screen space on the
     * CPU first, then fan-triangulates the 2D polygon, matching the CPU renderer's
     * Canvas.drawPath approach. This ensures correct fills for concave polygons
     * under perspective projection.
     */
    private void drawSingleSolidPrim(AndroidScene3D.Prim p, int sortIdx, int totalPrims,
                                     GpuSnapshot snap, float[] mvp, float[] modelMatrix,
                                     float cvx, float cvy, float cvz, float zOffset) {
        int nv = p.v.length / 3;
        if (nv < 3) return;

        int fill = p.fill;
        if (fill == 0) fill = p.border != 0 ? p.border : 0xFFFFFFFF;
        int litFill = cpuLitColor(fill, p.lnx, p.lny, p.lnz, snap.lights, snap.ambR, snap.ambG, snap.ambB, cvx, cvy, cvz);
        float r = ((litFill >> 16) & 0xFF) / 255f;
        float g = ((litFill >> 8) & 0xFF) / 255f;
        float b = (litFill & 0xFF) / 255f;
        float a = ((litFill >>> 24) & 0xFF) / 255f;
        if (a < 0.004f) return;

        // ── Project all vertices to 2D NDC on the CPU (matching CPU renderer) ──
        float[] ndcX = new float[nv];
        float[] ndcY = new float[nv];
        float[] viewZArr = new float[nv];
        boolean behindCamera = false;
        for (int i = 0; i < nv; i++) {
            float wx = p.v[i * 3], wy = p.v[i * 3 + 1], wz = p.v[i * 3 + 2];
            float[] vv = snap.view;
            float vx = vv[0]*wx + vv[4]*wy + vv[8]*wz + vv[12];
            float vy = vv[1]*wx + vv[5]*wy + vv[9]*wz + vv[13];
            float vz = vv[2]*wx + vv[6]*wy + vv[10]*wz + vv[14];
            float vw = vv[3]*wx + vv[7]*wy + vv[11]*wz + vv[15];
            float[] pp = snap.proj;
            float px = pp[0]*vx + pp[4]*vy + pp[8]*vz + pp[12]*vw;
            float py = pp[1]*vx + pp[5]*vy + pp[9]*vz + pp[13]*vw;
            float pw = pp[3]*vx + pp[7]*vy + pp[11]*vz + pp[15]*vw;
            if (pw <= 0.001f) { behindCamera = true; break; }
            ndcX[i] = px / pw;
            ndcY[i] = py / pw;
            viewZArr[i] = vz;
        }
        if (behindCamera) return;

        // ── Fan-triangulate the 2D NDC polygon ──
        int triCount = Math.max(0, (nv - 2) * 3);
        if (triCount == 0) return;

        int pid = basicShader.getProgramID();
        basicShader.start();

        int loc;
        loc = GLES20.glGetUniformLocation(pid, "model");
        if (loc >= 0) GLES20.glUniformMatrix4fv(loc, 1, false, IDENTITY4, 0);
        loc = GLES20.glGetUniformLocation(pid, "view");
        if (loc >= 0) GLES20.glUniformMatrix4fv(loc, 1, false, IDENTITY4, 0);
        loc = GLES20.glGetUniformLocation(pid, "projection");
        if (loc >= 0) GLES20.glUniformMatrix4fv(loc, 1, false, IDENTITY4, 0);
        loc = GLES20.glGetUniformLocation(pid, "normalMatrix");
        if (loc >= 0) GLES20.glUniformMatrix3fv(loc, 1, false, new float[]{1,0,0, 0,1,0, 0,0,1}, 0);
        basicShader.loadUseTexture(false);
        basicShader.loadUseLighting(false);
        basicShader.loadAmbientColor(1f, 1f, 1f);
        loc = GLES20.glGetUniformLocation(pid, "texture1");
        if (loc >= 0) GLES20.glUniform1i(loc, 0);

        float[] buf = new float[triCount * 12];
        int si = 0;
        for (int ti = 1; ti + 1 < nv; ti++) {
            int i0 = 0, i1 = ti, i2 = ti + 1;
            int[] idx = {i0, i1, i2};
            for (int k = 0; k < 3; k++) {
                int vi = idx[k];
                buf[si++] = ndcX[vi];
                buf[si++] = ndcY[vi];
                buf[si++] = zOffset;
                buf[si++] = r; buf[si++] = g; buf[si++] = b; buf[si++] = a;
                buf[si++] = 0; buf[si++] = 0;
                buf[si++] = 0; buf[si++] = 0; buf[si++] = 1;
            }
        }

        FloatBuffer fb = ByteBuffer.allocateDirect(buf.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(buf).flip();
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, solidVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, buf.length * 4, fb, GLES20.GL_STREAM_DRAW);

        int stride = 12 * 4;
        int aPos = GLES20.glGetAttribLocation(pid, "aPos");
        int aCol = GLES20.glGetAttribLocation(pid, "aColor");
        int aTex = GLES20.glGetAttribLocation(pid, "aTexCoord");
        int aNorm = GLES20.glGetAttribLocation(pid, "aNormal");

        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, 0);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, stride, 3 * 4);
        if (aTex >= 0) {
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, stride, 7 * 4);
        }
        GLES20.glEnableVertexAttribArray(aNorm);
        GLES20.glVertexAttribPointer(aNorm, 3, GLES20.GL_FLOAT, false, stride, 9 * 4);

        // ── Stencil-based even-odd fill (matches Canvas.drawPath) ──
        GLES20.glEnable(GLES20.GL_STENCIL_TEST);
        GLES20.glStencilMask(0xFF);
        GLES20.glClearStencil(0);
        GLES20.glClear(GLES20.GL_STENCIL_BUFFER_BIT);

        // Pass 1: Increment stencil for each fragment coverage (count overlaps)
        GLES20.glColorMask(false, false, false, false);
        GLES20.glStencilFunc(GLES20.GL_ALWAYS, 0, 0xFF);
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_INCR_WRAP);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triCount);

        // Pass 2: Draw color only where coverage count is odd (even-odd rule)
        GLES20.glColorMask(true, true, true, true);
        GLES20.glStencilFunc(GLES20.GL_EQUAL, 1, 1);
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_KEEP);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triCount);

        GLES20.glDisable(GLES20.GL_STENCIL_TEST);

        GLES20.glDisableVertexAttribArray(0);
        GLES20.glDisableVertexAttribArray(1);
        if (aTex >= 0) GLES20.glDisableVertexAttribArray(2);
        GLES20.glDisableVertexAttribArray(3);
    }

    private void drawTexturedPrim(AndroidScene3D.Prim p, GpuSnapshot snap, float[] modelMatrix, float[] normalMatrix, boolean fanTriangulate, float zOffset) {
        int nv = p.v.length / 3;
        if (nv == 0) return;
        int triCount = fanTriangulate ? Math.max(0, (nv - 2) * 3) : nv;
        if (triCount == 0) return;

        int pid = basicShader.getProgramID();
        basicShader.start();

        // Pass matrices as raw column-major floats (bypass JOML transpose issue)
        int loc;
        loc = GLES20.glGetUniformLocation(pid, "model");
        if (loc >= 0) GLES20.glUniformMatrix4fv(loc, 1, false, modelMatrix, 0);
        loc = GLES20.glGetUniformLocation(pid, "view");
        if (loc >= 0) GLES20.glUniformMatrix4fv(loc, 1, false, snap.view, 0);
        loc = GLES20.glGetUniformLocation(pid, "projection");
        if (loc >= 0) GLES20.glUniformMatrix4fv(loc, 1, false, snap.proj, 0);
        loc = GLES20.glGetUniformLocation(pid, "normalMatrix");
        if (loc >= 0) GLES20.glUniformMatrix3fv(loc, 1, false, normalMatrix, 0);

        basicShader.loadUseTexture(true);
        basicShader.loadUseLighting(snap.lights.length > 0);
        basicShader.loadAmbientColor(snap.ambR, snap.ambG, snap.ambB);
        if (snap.lights.length > 0) {
            AndroidScene3D.GamaLight l = snap.lights[0];
            if (l.type == 1) {
                float far = 10000f;
                basicShader.loadLightPosition(l.ldx * far, l.ldy * far, l.ldz * far);
            } else {
                basicShader.loadLightPosition(l.px, l.py, l.pz);
            }
            basicShader.loadLightColor(l.r, l.g, l.b);
        } else {
            basicShader.loadLightPosition(0, 0, 1);
            basicShader.loadLightColor(1, 1, 1);
        }
        // Extract camera position from view matrix (column-major)
        float[] v = snap.view;
        float camX = -(v[0]*v[12] + v[1]*v[13] + v[2]*v[14]);
        float camY = -(v[4]*v[12] + v[5]*v[13] + v[6]*v[14]);
        float camZ = -(v[8]*v[12] + v[9]*v[13] + v[10]*v[14]);
        basicShader.loadViewPos(camX, camY, camZ);
        basicShader.loadShininess(32f);

        // Bind texture sampler to unit 0
        loc = GLES20.glGetUniformLocation(pid, "texture1");
        if (loc >= 0) GLES20.glUniform1i(loc, 0);

        // Tint is baked into vertex color
        int tint = p.tint != 0 ? p.tint : 0xFFFFFFFF;
        float tr = ((tint >> 16) & 0xFF) / 255f;
        float tg = ((tint >> 8) & 0xFF) / 255f;
        float tb = (tint & 0xFF) / 255f;
        float ta = ((tint >> 24) & 0xFF) / 255f;

        // triCount already computed above for fan case
        // Build buffer: pos3 + col4 + uv2 + norm3 = 12 floats per vertex
        // Avoid zero normals (normalize(0,0,0) = NaN → white on Adreno GPUs)
        float pnx = p.lnx, pny = p.lny, pnz = p.lnz;
        if (pnx == 0 && pny == 0 && pnz == 0) { pnx = 0; pny = 0; pnz = 1; }
        float[] buf = new float[triCount * 12];
        int bi = 0;
        if (fanTriangulate) {
            for (int ti = 1; ti + 1 < nv; ti++) {
                int[] idx = {0, ti, ti + 1};
                for (int vi : idx) {
                    buf[bi++] = p.v[vi * 3]; buf[bi++] = p.v[vi * 3 + 1]; buf[bi++] = p.v[vi * 3 + 2];
                    buf[bi++] = tr; buf[bi++] = tg; buf[bi++] = tb; buf[bi++] = ta;
                    if (p.uv != null && vi * 2 + 1 < p.uv.length) {
                        buf[bi++] = p.uv[vi * 2]; buf[bi++] = p.uv[vi * 2 + 1];
                    } else { buf[bi++] = 0; buf[bi++] = 0; }
                    buf[bi++] = pnx; buf[bi++] = pny; buf[bi++] = pnz;
                }
            }
        } else {
            for (int vi = 0; vi < nv; vi++) {
                buf[bi++] = p.v[vi * 3]; buf[bi++] = p.v[vi * 3 + 1]; buf[bi++] = p.v[vi * 3 + 2];
                buf[bi++] = tr; buf[bi++] = tg; buf[bi++] = tb; buf[bi++] = ta;
                if (p.uv != null && vi * 2 + 1 < p.uv.length) {
                    buf[bi++] = p.uv[vi * 2]; buf[bi++] = p.uv[vi * 2 + 1];
                } else { buf[bi++] = 0; buf[bi++] = 0; }
                buf[bi++] = pnx; buf[bi++] = pny; buf[bi++] = pnz;
            }
        }
        FloatBuffer fb = ByteBuffer.allocateDirect(buf.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(buf).flip();
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, texVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, buf.length * 4, fb, GLES20.GL_STREAM_DRAW);

        int stride = 12 * 4;
        int aPos = GLES20.glGetAttribLocation(basicShader.getProgramID(), "aPos");
        int aCol = GLES20.glGetAttribLocation(basicShader.getProgramID(), "aColor");
        int aTex = GLES20.glGetAttribLocation(basicShader.getProgramID(), "aTexCoord");
        int aNorm = GLES20.glGetAttribLocation(basicShader.getProgramID(), "aNormal");

        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, 0);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, stride, 3 * 4);
        if (aTex >= 0) {
            GLES20.glEnableVertexAttribArray(aTex);
            GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, stride, 7 * 4);
        }
        GLES20.glEnableVertexAttribArray(aNorm);
        GLES20.glVertexAttribPointer(aNorm, 3, GLES20.GL_FLOAT, false, stride, 9 * 4);

        // Bind texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        int texId = getOrCreateTexture(p.texture);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triCount);
    }

    private int getOrCreateTexture(Object texObj) {
        Bitmap bmp = null;
        if (texObj instanceof Bitmap b) {
            bmp = b;
        } else if (texObj instanceof AndroidScene3D.AnimatedTexture at) {
            bmp = at.currentFrame();
        }
        if (bmp == null) return 0;
        Integer cached = texCache.get(bmp);
        if (cached != null) return cached;
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        int texId = tex[0];
        if (texId == 0) {
            Log.e(TAG, "glGenTextures returned 0");
            return 0;
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        // Samsung Adreno GPUs reject HARDWARE bitmaps — copy to ARGB_8888
        if (bmp.getConfig() != Bitmap.Config.ARGB_8888) {
            Bitmap safe = bmp.copy(Bitmap.Config.ARGB_8888, false);
            if (safe != null) {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, safe, 0);
                safe.recycle();
            } else {
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            }
        } else {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        }
        int texErr = GLES20.glGetError();
        if (texErr != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "texImage2D failed: 0x" + Integer.toHexString(texErr)
                    + " bmp=" + bmp.getWidth() + "x" + bmp.getHeight() + " config=" + bmp.getConfig());
            GLES20.glDeleteTextures(1, new int[]{texId}, 0);
            return 0;
        }
        texCache.put(bmp, texId);
        return texId;
    }

    private void drawBillboard(AndroidScene3D.Prim p, GpuSnapshot snap, float[] modelMatrix, float[] normalMatrix, float zOffset) {
        // Expand billboard into a textured quad using camera right/up from the view matrix
        float cx = p.v[0], cy = p.v[1], cz = p.v[2];
        // Camera right = view matrix row 0 (mvp columns are transposed; view[0,4,8] are right x,y,z)
        float rx = snap.view[0], ry = snap.view[4], rz = snap.view[8];
        float ux = snap.view[1], uy = snap.view[5], uz = snap.view[9];
        float hw = p.bbW / 2f, hh = p.bbH / 2f;
        // If bbRot != 0, rotate right/up by bbRot degrees in screen plane
        if (p.bbRot != 0) {
            float rad = (float) Math.toRadians(p.bbRot);
            float cos = (float) Math.cos(rad), sin = (float) Math.sin(rad);
            float nrx = rx * cos - ux * sin, nry = ry * cos - uy * sin, nrz = rz * cos - uz * sin;
            rx = nrx; ry = nry; rz = nrz;
            ux = ux * cos + rx * sin; uy = uy * cos + ry * sin; uz = uz * cos + rz * sin;
        }
        float[] v = {
            cx - rx * hw - ux * hh, cy - ry * hw - uy * hh, cz - rz * hw - uz * hh,
            cx + rx * hw - ux * hh, cy + ry * hw - uy * hh, cz + rz * hw - uz * hh,
            cx + rx * hw + ux * hh, cy + ry * hw + uy * hh, cz + rz * hw + uz * hh,
            cx - rx * hw - ux * hh, cy - ry * hw - uy * hh, cz - rz * hw - uz * hh,
            cx + rx * hw + ux * hh, cy + ry * hw + uy * hh, cz + rz * hw + uz * hh,
            cx - rx * hw + ux * hh, cy - ry * hw + uy * hh, cz - rz * hw + uz * hh,
        };
        float[] uv = { 0, 1, 1, 1, 1, 0, 0, 1, 1, 0, 0, 0 };
        AndroidScene3D.Prim quad = new AndroidScene3D.Prim();
        quad.kind = AndroidScene3D.POLY;
        quad.v = v;
        quad.uv = uv;
        quad.texture = p.texture;
        quad.tint = p.tint;
        quad.lnx = -snap.view[8]; quad.lny = -snap.view[9]; quad.lnz = -snap.view[10]; // camera forward as normal
        drawTexturedPrim(quad, snap, modelMatrix, normalMatrix, false, zOffset);
    }

    // ── Line batch draw ────────────────────────────────────────────────

    private void drawLineBatch(float[] buf, int vertCount, float[] mvp) {
        GLES20.glUseProgram(lineProgram);
        int uMVP = GLES20.glGetUniformLocation(lineProgram, "uMVP");
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);

        FloatBuffer fb = ByteBuffer.allocateDirect(buf.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(buf).flip();
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, lineVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, buf.length * 4, fb, GLES20.GL_STREAM_DRAW);

        int stride = 7 * 4;
        int aPos = GLES20.glGetAttribLocation(lineProgram, "aPos");
        int aCol = GLES20.glGetAttribLocation(lineProgram, "aColor");
        // Disable attributes that may be enabled from previous draw calls
        GLES20.glDisableVertexAttribArray(2);
        GLES20.glDisableVertexAttribArray(3);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, 0);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, stride, 3 * 4);

        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertCount);
    }

    // ── CPU text pass (after readback, on GL thread) ───────────────────

    private void drawTextPrims(GpuSnapshot snap, Bitmap targetBitmap) {
        Canvas c = new Canvas(targetBitmap);
        for (AndroidScene3D.Prim p : snap.prims) {
            if (p.kind != AndroidScene3D.TEXT || p.text == null) continue;
            float[] screen = project3Dto2D(p.v[0], p.v[1], p.v[2], snap);
            if (screen == null) continue;
            textPaint.setColor(p.fill);
            textPaint.setTextSize(Math.max(1f, p.textSize));
            textPaint.setAntiAlias(true);
            // Anchor: ax 0=left, 0.5=center, 1=right; ay 0=bottom, 0.5=center, 1=top
            Paint.Align align = p.ax < 0.25f ? Paint.Align.LEFT : p.ax > 0.75f ? Paint.Align.RIGHT : Paint.Align.CENTER;
            textPaint.setTextAlign(align);
            float x = screen[0];
            if (align == Paint.Align.CENTER) x += (0.5f - p.ax) * textPaint.measureText(p.text);
            else if (align == Paint.Align.RIGHT) x += (1f - p.ax) * textPaint.measureText(p.text);
            float y = screen[1] + (0.5f - p.ay) * textPaint.getTextSize();
            c.drawText(p.text, x, y, textPaint);
        }
    }

    private static float[] project3Dto2D(float wx, float wy, float wz, GpuSnapshot snap) {
        float[] v = snap.view;
        float vx = v[0] * wx + v[4] * wy + v[8] * wz + v[12];
        float vy = v[1] * wx + v[5] * wy + v[9] * wz + v[13];
        float vz = v[2] * wx + v[6] * wy + v[10] * wz + v[14];
        float vw = v[3] * wx + v[7] * wy + v[11] * wz + v[15];
        float[] p = snap.proj;
        float px = p[0] * vx + p[4] * vy + p[8] * vz + p[12] * vw;
        float py = p[1] * vx + p[5] * vy + p[9] * vz + p[13] * vw;
        float pw = p[3] * vx + p[7] * vy + p[11] * vz + p[15] * vw;
        if (Math.abs(pw) < 1e-6f) return null;
        float sx = (px / pw + 1f) / 2f * snap.viewW;
        float sy = (1f - py / pw) / 2f * snap.viewH;
        return new float[]{ sx, sy };
    }

    // ── CPU-side lighting (matches AndroidScene3D.litColor) ───────────

    private static final int LT_DIR = 1, LT_SPOT = 3;
    private static final float SPEC_SHINE = 14f;
    private static final float SPEC_INTENSITY = 1.0f;

    /** Bake lighting into a vertex color, matching AndroidScene3D.litColor(). */
    private static int cpuLitColor(int argb, float nx, float ny, float nz,
            AndroidScene3D.GamaLight[] lights, float ambR, float ambG, float ambB,
            float camX, float camY, float camZ) {
        if (nx == 0 && ny == 0 && nz == 0) return argb;
        float fr = ambR, fg = ambG, fb = ambB;
        int a = (argb >>> 24) & 0xFF;
        int ri = (argb >>> 16) & 0xFF, gi = (argb >>> 8) & 0xFF, bi = argb & 0xFF;
        for (AndroidScene3D.GamaLight l : lights) {
            if (!l.active) continue;
            float lx, ly, lz, atten = 1f;
            if (l.type == LT_DIR) {
                lx = l.ldx; ly = l.ldy; lz = l.ldz;
            } else {
                float ovx = l.px, ovy = l.py, ovz = l.pz;
                float d = (float) Math.sqrt(ovx * ovx + ovy * ovy + ovz * ovz);
                if (d < 1e-6f) { lx = 0; ly = 0; lz = 1; }
                else { lx = ovx / d; ly = ovy / d; lz = ovz / d; }
                if (l.type == LT_SPOT) {
                    float dot = -(lx * l.ldx + ly * l.ldy + lz * l.ldz);
                    if (dot < l.cosSpot) continue;
                }
                atten = Math.min(1f, 1f / (l.ca + l.la * d + l.qa * d * d));
            }
            float nd = nx * lx + ny * ly + nz * lz;
            if (nd < 0) {
                if (l.type == LT_SPOT) continue;
                nd = -nd;
            }
            float dl = Math.min(1f, nd * atten);
            fr += l.r * dl;
            fg += l.g * dl;
            fb += l.b * dl;
            float hx = lx + camX, hy = ly + camY, hz = lz + camZ;
            float hl = (float) Math.sqrt(hx * hx + hy * hy + hz * hz);
            if (hl > 1e-6f) {
                float dh = (nx * hx + ny * hy + nz * hz) / hl;
                if (dh > 0) {
                    float spec = (float) Math.pow(dh, SPEC_SHINE) * SPEC_INTENSITY;
                    fr += l.r * spec;
                    fg += l.g * spec;
                    fb += l.b * spec;
                }
            }
        }
        if (fr > 1f) fr = 1f;
        if (fg > 1f) fg = 1f;
        if (fb > 1f) fb = 1f;
        int rr = Math.round(ri * fr); if (rr > 255) rr = 255;
        int gg = Math.round(gi * fg); if (gg > 255) gg = 255;
        int bb = Math.round(bi * fb); if (bb > 255) bb = 255;
        return (a << 24) | (rr << 16) | (gg << 8) | bb;
    }

    // ── Matrix utility ─────────────────────────────────────────────────

    /** Column-major mat4 multiply: out = a × b */
    private static void mat4Mul(float[] out, float[] a, float[] b) {
        for (int c = 0; c < 4; c++) {
            for (int r = 0; r < 4; r++) {
                float sum = 0;
                for (int k = 0; k < 4; k++) {
                    sum += a[k * 4 + r] * b[c * 4 + k];
                }
                out[c * 4 + r] = sum;
            }
        }
    }

    // ── GL thread ──────────────────────────────────────────────────────

    private class GlThread extends Thread {
        volatile boolean running = true;

        @Override
        public void run() {
            try {
                initEgl();
                initShaders();
                initialized = true;
                Log.i(TAG, "GL thread initialized: " + renderW + "x" + renderH);
            } catch (Throwable t) {
                Log.e(TAG, "GL init failed", t);
                initLatch.countDown();
                return;
            }
            initLatch.countDown();

            while (running) {
                GpuSnapshot snap;
                Bitmap target;
                synchronized (GpuDisplayRenderer.this) {
                    while (pendingSnap == null && running) {
                        try { GpuDisplayRenderer.this.wait(); } catch (InterruptedException ignored) {}
                    }
                    if (!running) break;
                    snap = pendingSnap;
                    target = pendingTarget;
                    pendingSnap = null;
                    pendingTarget = null;
                }
                if (snap != null && target != null) {
                    int polyCount = 0, lineCount = 0, totalVerts = 0;
                    for (AndroidScene3D.Prim pp : snap.prims) {
                        if (pp.kind == AndroidScene3D.POLY) { polyCount++; totalVerts += pp.v.length / 3; }
                        else if (pp.kind == AndroidScene3D.LINE) lineCount++;
                    }
                    Log.i(TAG, "GPU render: prims=" + snap.prims.size() + " poly=" + polyCount + " line=" + lineCount + " verts=" + totalVerts
                            + " bg=0x" + Integer.toHexString(snap.bgColor) + " near=" + snap.nearPlane + " far=" + snap.farPlane
                            + " viewW=" + snap.viewW + " viewH=" + snap.viewH);
                    renderFrame(snap, target);
                }
                if (pendingLatch != null) pendingLatch.countDown();
            }
            destroyEgl();
        }
    }
}
