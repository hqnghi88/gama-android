package com.gama.nativeapp.display;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLUtils;
import android.util.Log;
import android.view.Surface;

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
 * Two rendering modes:
 * <ul>
 *   <li><b>Window mode</b> (TextureView): renders directly to a window surface
 *       via eglSwapBuffers. No readback, maximum performance.</li>
 *   <li><b>Pbuffer mode</b> (fallback): renders to an offscreen pbuffer,
 *       reads back via glReadPixels, writes to a Bitmap. Used when no window
 *       surface is available.</li>
 * </ul>
 * Thread model: the simulation thread calls {@link #renderSync} which blocks
 * until the GL thread finishes rendering.
 */
public final class GpuDisplayRenderer {

    private static final String TAG = "GpuDisplayRenderer";

    // ── GPU capability detection ────────────────────────────────────

    public static final class GpuCapability {
        public final boolean supported;
        public final String glVersion;
        public final String glRenderer;
        public final String glVendor;
        public final String reason;

        private GpuCapability(boolean supported, String glVersion, String glRenderer,
                              String glVendor, String reason) {
            this.supported = supported;
            this.glVersion = glVersion;
            this.glRenderer = glRenderer;
            this.glVendor = glVendor;
            this.reason = reason;
        }
    }

    public static GpuCapability probeGpu() {
        EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
        EGLContext ctx = EGL14.EGL_NO_CONTEXT;
        EGLSurface surf = EGL14.EGL_NO_SURFACE;
        try {
            dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            if (dpy == EGL14.EGL_NO_DISPLAY)
                return new GpuCapability(false, "?", "?", "?", "eglGetDisplay returned NO_DISPLAY");
            int[] vers = new int[2];
            if (!EGL14.eglInitialize(dpy, vers, 0, vers, 1))
                return new GpuCapability(false, "?", "?", "?", "eglInitialize failed");
            int[] cfgAttribs = {
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_STENCIL_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_NONE
            };
            EGLConfig[] cfg = new EGLConfig[1];
            int[] numCfg = new int[1];
            if (!EGL14.eglChooseConfig(dpy, cfgAttribs, 0, cfg, 0, 1, numCfg, 0) || numCfg[0] == 0)
                return new GpuCapability(false, "?", "?", "?", "eglChooseConfig failed");
            int[] pbAttribs = { EGL14.EGL_WIDTH, 64, EGL14.EGL_HEIGHT, 64, EGL14.EGL_NONE };
            surf = EGL14.eglCreatePbufferSurface(dpy, cfg[0], pbAttribs, 0);
            if (surf == EGL14.EGL_NO_SURFACE)
                return new GpuCapability(false, "?", "?", "?", "eglCreatePbufferSurface failed");
            int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
            ctx = EGL14.eglCreateContext(dpy, cfg[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
            if (ctx == EGL14.EGL_NO_CONTEXT)
                return new GpuCapability(false, "?", "?", "?", "eglCreateContext(GLES 2.0) failed");
            if (!EGL14.eglMakeCurrent(dpy, surf, surf, ctx))
                return new GpuCapability(false, "?", "?", "?", "eglMakeCurrent failed");
            String ver = GLES20.glGetString(GLES20.GL_VERSION);
            String rend = GLES20.glGetString(GLES20.GL_RENDERER);
            String vend = GLES20.glGetString(GLES20.GL_VENDOR);
            return new GpuCapability(true, ver != null ? ver : "?", rend != null ? rend : "?",
                    vend != null ? vend : "?", null);
        } catch (Throwable t) {
            return new GpuCapability(false, "?", "?", "?", "exception: " + t.getMessage());
        } finally {
            if (ctx != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(dpy, ctx);
            if (surf != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(dpy, surf);
            if (dpy != EGL14.EGL_NO_DISPLAY) EGL14.eglTerminate(dpy);
        }
    }

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

    // Window surface mode (TextureView) — no readback
    private volatile Surface windowSurface;
    private boolean useWindowMode = false;
    /** Lock for thread-safe surface handoff from UI thread to GL thread. */
    public static final Object surfaceLock = new Object();
    /** Pending surface from TextureView, picked up by GL thread. */
    static volatile Surface pendingWindowSurface;

    // Texture cache
    private final Map<Bitmap, Integer> texCache = new HashMap<>();
    private int texVbo;

    // CPU text paint
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Text overlay bitmap for window mode (text drawn here, composited by caller)
    private Bitmap textOverlayBitmap;

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
     * Sets a window surface for direct rendering (TextureView mode).
     * Must be called before the first render, or to switch surfaces.
     * Pass null to revert to pbuffer fallback.
     */
    public void setWindowSurface(Surface surface) {
        this.windowSurface = surface;
        this.useWindowMode = (surface != null);
    }

    /** Whether rendering to a window surface (no readback). */
    public boolean isWindowMode() { return useWindowMode && windowSurface != null; }

    public boolean isInitialized() { return initialized; }

    /**
     * Renders the given snapshot synchronously.
     * In window mode: renders directly to the TextureView surface, no bitmap needed.
     * In pbuffer mode: renders to pbuffer, reads back to targetBitmap.
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

    /** Get the text overlay bitmap (window mode only). Caller must draw this on screen. */
    public Bitmap getTextOverlayBitmap() { return textOverlayBitmap; }

    // ── EGL init ────────────────────────────────────────────────────

    private void initEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("eglGetDisplay failed");
        int[] vers = new int[2];
        EGL14.eglInitialize(eglDisplay, vers, 0, vers, 1);

        // Config: support both window and pbuffer surfaces
        int[] cfgAttribs = {
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_STENCIL_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        EGL14.eglChooseConfig(eglDisplay, cfgAttribs, 0, configs, 0, 1, numConfigs, 0);
        if (numConfigs[0] == 0) throw new RuntimeException("eglChooseConfig failed");

        int[] ctxAttribs = { EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE };
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) throw new RuntimeException("eglCreateContext failed");

        // Create surface — window or pbuffer
        Surface ws = windowSurface;
        if (ws != null) {
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], ws, null, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE)
                throw new RuntimeException("eglCreateWindowSurface failed");
            useWindowMode = true;
            Log.i(TAG, "EGL: window surface created");
        } else {
            int eglW = Math.max(1, renderW);
            int eglH = Math.max(1, renderH);
            int[] pbAttribs = { EGL14.EGL_WIDTH, eglW, EGL14.EGL_HEIGHT, eglH, EGL14.EGL_NONE };
            eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbAttribs, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE)
                throw new RuntimeException("eglCreatePbufferSurface failed");
            useWindowMode = false;
            intBuf = new int[eglW * eglH];
            Log.i(TAG, "EGL: pbuffer surface created " + eglW + "x" + eglH);
        }

        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext))
            throw new RuntimeException("eglMakeCurrent failed");
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

    // ── Shader compilation ─────────────────────────────────────────

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
        basicShader = new BasicShader(new Gles2GLWrapper());
        int bsErr = GLES20.glGetError();
        Log.i(TAG, "BasicShader created: programID=" + basicShader.getProgramID() + " glErr=0x" + Integer.toHexString(bsErr));

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

    // ── Per-frame render ───────────────────────────────────────────

    private void renderFrame(GpuSnapshot snap, Bitmap targetBitmap) {
        try {
            Surface ws = windowSurface;
            if (useWindowMode && ws != null) {
                // Re-create EGL surface if window surface changed
                if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
                    // Surface may have been destroyed/recreated by TextureView
                    try {
                        EGLConfig[] configs = new EGLConfig[1];
                        int[] numConfigs = new int[1];
                        int[] cfgAttribs = {
                            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8,
                            EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                            EGL14.EGL_DEPTH_SIZE, 16, EGL14.EGL_STENCIL_SIZE, 8,
                            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                            EGL14.EGL_NONE
                        };
                        EGL14.eglChooseConfig(eglDisplay, cfgAttribs, 0, configs, 0, 1, numConfigs, 0);
                        if (eglSurface != EGL14.EGL_NO_SURFACE)
                            EGL14.eglDestroySurface(eglDisplay, eglSurface);
                        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], ws, null, 0);
                        if (eglSurface == EGL14.EGL_NO_SURFACE) return;
                        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return;
                    } catch (Throwable t) {
                        Log.e(TAG, "Failed to recreate window surface", t);
                        return;
                    }
                }
                renderToWindow(snap);
                return;
            }

            // Pbuffer fallback path
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return;
            if (targetBitmap == null) return;
            renderToBitmap(snap, targetBitmap);

        } catch (Throwable t) {
            Log.e(TAG, "renderFrame failed", t);
        }
    }

    // ── Window mode: render directly to TextureView (no readback) ──

    private void renderToWindow(GpuSnapshot snap) {
        List<AndroidScene3D.Prim> prims = snap.prims;
        int w = snap.viewW, h = snap.viewH;
        GLES20.glViewport(0, 0, w, h);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);

        float bgR = ((snap.bgColor >> 16) & 0xFF) / 255f;
        float bgG = ((snap.bgColor >> 8) & 0xFF) / 255f;
        float bgB = (snap.bgColor & 0xFF) / 255f;
        GLES20.glClearColor(bgR, bgG, bgB, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        drawAllPrims(snap, prims);

        int glErr = GLES20.glGetError();
        if (glErr != GLES20.GL_NO_ERROR) Log.e(TAG, "GL error: 0x" + Integer.toHexString(glErr));

        EGL14.eglSwapBuffers(eglDisplay, eglSurface);

        // Draw text prims to overlay bitmap
        drawTextPrimsToOverlay(snap);
    }

    // ── Pbuffer mode: render + readback to Bitmap ──────────────────

    private void renderToBitmap(GpuSnapshot snap, Bitmap targetBitmap) {
        List<AndroidScene3D.Prim> prims = snap.prims;
        int w = snap.viewW, h = snap.viewH;
        int gw = Math.max(1, renderW), gh = Math.max(1, renderH);
        GLES20.glViewport(0, 0, gw, gh);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDepthFunc(GLES20.GL_LEQUAL);

        float bgR = ((snap.bgColor >> 16) & 0xFF) / 255f;
        float bgG = ((snap.bgColor >> 8) & 0xFF) / 255f;
        float bgB = (snap.bgColor & 0xFF) / 255f;
        GLES20.glClearColor(bgR, bgG, bgB, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        drawAllPrims(snap, prims);

        int glErr = GLES20.glGetError();
        if (glErr != GLES20.GL_NO_ERROR) Log.e(TAG, "GL error: 0x" + Integer.toHexString(glErr));

        // ── Readback ──
        GLES20.glReadPixels(0, 0, gw, gh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                IntBuffer.wrap(intBuf));

        int[] smallBuf = new int[gw * gh];
        int[] row = new int[gw];
        for (int y = 0; y < gh / 2; y++) {
            int topOff = y * gw, botOff = (gh - 1 - y) * gw;
            for (int x = 0; x < gw; x++) {
                int rgba = intBuf[topOff + x];
                row[x] = ((rgba & 0xFF) << 16) | (((rgba >> 8) & 0xFF) << 8)
                        | ((rgba >> 16) & 0xFF) | (rgba & 0xFF000000);
            }
            for (int x = 0; x < gw; x++) {
                int rgba = intBuf[botOff + x];
                intBuf[topOff + x] = ((rgba & 0xFF) << 16) | (((rgba >> 8) & 0xFF) << 8)
                        | ((rgba >> 16) & 0xFF) | (rgba & 0xFF000000);
            }
            for (int x = 0; x < gw; x++) {
                intBuf[botOff + x] = row[x];
            }
        }
        if ((gh & 1) == 1) {
            int mid = (gh / 2) * gw;
            for (int x = 0; x < gw; x++) {
                int rgba = intBuf[mid + x];
                intBuf[mid + x] = ((rgba & 0xFF) << 16) | (((rgba >> 8) & 0xFF) << 8)
                        | ((rgba >> 16) & 0xFF) | (rgba & 0xFF000000);
            }
        }
        System.arraycopy(intBuf, 0, smallBuf, 0, gw * gh);

        if (gw == w && gh == h) {
            targetBitmap.eraseColor(0);
            targetBitmap.setPixels(smallBuf, 0, gw, 0, 0, gw, gh);
        } else {
            Bitmap smallBmp = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888);
            smallBmp.setPixels(smallBuf, 0, gw, 0, 0, gw, gh);
            Canvas c = new Canvas(targetBitmap);
            c.drawBitmap(smallBmp, null, new android.graphics.Rect(0, 0, w, h), null);
            smallBmp.recycle();
        }

        drawTextPrims(snap, targetBitmap);
    }

    // ── Shared: draw all prims ─────────────────────────────────────

    private void drawAllPrims(GpuSnapshot snap, List<AndroidScene3D.Prim> prims) {
        int totalPrims = prims.size();

        int INIT_CAP = Math.max(4096, totalPrims * 6 * 12);
        float[] solidBuf = new float[INIT_CAP];
        int solidVerts = 0;
        int lineBufCap = Math.max(2048, totalPrims * 2 * 7);
        float[] lineBuf = new float[lineBufCap];
        int lineVerts = 0;

        for (int pi = 0; pi < totalPrims; pi++) {
            AndroidScene3D.Prim p = prims.get(pi);

            if (p.kind == AndroidScene3D.POLY && p.texture == null) {
                int nv = p.v.length / 3;
                if (nv < 3) {
                    lineVerts = appendBorderLines(lineBuf, lineVerts, p);
                    continue;
                }

                int fill = p.fill;
                if (fill == 0) fill = p.border != 0 ? p.border : 0xFFFFFFFF;
                float r = ((fill >> 16) & 0xFF) / 255f;
                float g = ((fill >> 8) & 0xFF) / 255f;
                float b = (fill & 0xFF) / 255f;
                float a = ((fill >>> 24) & 0xFF) / 255f;
                if (a < 0.004f) {
                    lineVerts = appendBorderLines(lineBuf, lineVerts, p);
                    continue;
                }

                float pnx = p.lnx, pny = p.lny, pnz = p.lnz;
                if (pnx == 0 && pny == 0 && pnz == 0) { pnx = 0; pny = 0; pnz = 1; }

                if (nv <= 4) {
                    int triCount = nv - 2;
                    int needed = solidVerts + triCount * 3;
                    if (needed * 12 > solidBuf.length) {
                        float[] nb = new float[Math.max(solidBuf.length * 2, needed * 12)];
                        System.arraycopy(solidBuf, 0, nb, 0, solidVerts * 12);
                        solidBuf = nb;
                    }
                    for (int ti = 1; ti + 1 < nv; ti++) {
                        int[] idx = {0, ti, ti + 1};
                        for (int k = 0; k < 3; k++) {
                            int vi = idx[k];
                            int si = solidVerts * 12;
                            solidBuf[si] = p.v[vi * 3]; solidBuf[si + 1] = p.v[vi * 3 + 1]; solidBuf[si + 2] = p.v[vi * 3 + 2];
                            solidBuf[si + 3] = r; solidBuf[si + 4] = g; solidBuf[si + 5] = b; solidBuf[si + 6] = a;
                            solidBuf[si + 7] = 0; solidBuf[si + 8] = 0;
                            solidBuf[si + 9] = pnx; solidBuf[si + 10] = pny; solidBuf[si + 11] = pnz;
                            solidVerts++;
                        }
                    }
                } else {
                    drawSingleSolidPrim(p, pi, totalPrims, snap);
                }

                lineVerts = appendBorderLines(lineBuf, lineVerts, p);

            } else if (p.kind == AndroidScene3D.POLY && p.texture != null) {
                drawTexturedPrim(p, snap, IDENTITY4, new float[]{1,0,0, 0,1,0, 0,0,1}, true);
            } else if (p.kind == AndroidScene3D.BILLBOARD) {
                drawBillboard(p, snap, IDENTITY4, new float[]{1,0,0, 0,1,0, 0,0,1});
            } else if (p.kind == AndroidScene3D.LINE) {
                lineVerts = appendLineVerts(lineBuf, lineVerts, p);
            }
        }

        if (solidVerts > 0) {
            drawSolidBatch(solidBuf, solidVerts, snap);
        }

        if (lineVerts > 0) {
            float[] mvp = new float[16];
            mat4Mul(mvp, snap.proj, snap.view);
            drawLineBatch(lineBuf, lineVerts, mvp);
        }
    }

    // ── Single-prim concave solid draw (stencil) ──────────────────

    private void drawSingleSolidPrim(AndroidScene3D.Prim p, int sortIdx, int totalPrims,
                                     GpuSnapshot snap) {
        int nv = p.v.length / 3;
        if (nv < 3) return;

        int fill = p.fill;
        if (fill == 0) fill = p.border != 0 ? p.border : 0xFFFFFFFF;
        float r = ((fill >> 16) & 0xFF) / 255f;
        float g = ((fill >> 8) & 0xFF) / 255f;
        float b = (fill & 0xFF) / 255f;
        float a = ((fill >>> 24) & 0xFF) / 255f;
        if (a < 0.004f) return;

        float pnx = p.lnx, pny = p.lny, pnz = p.lnz;
        if (pnx == 0 && pny == 0 && pnz == 0) { pnx = 0; pny = 0; pnz = 1; }

        int pid = basicShader.getProgramID();
        basicShader.start();

        int loc;
        loc = GLES20.glGetUniformLocation(pid, "model");
        if (loc >= 0) GLES20.glUniformMatrix4fv(loc, 1, false, IDENTITY4, 0);
        loc = GLES20.glGetUniformLocation(pid, "view");
        if (loc >= 0) GLES20.glUniformMatrix4fv(loc, 1, false, snap.view, 0);
        loc = GLES20.glGetUniformLocation(pid, "projection");
        if (loc >= 0) GLES20.glUniformMatrix4fv(loc, 1, false, snap.proj, 0);
        loc = GLES20.glGetUniformLocation(pid, "normalMatrix");
        if (loc >= 0) GLES20.glUniformMatrix3fv(loc, 1, false, new float[]{1,0,0, 0,1,0, 0,0,1}, 0);
        basicShader.loadUseTexture(false);
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
        float[] vv = snap.view;
        float camX = -(vv[0]*vv[12] + vv[1]*vv[13] + vv[2]*vv[14]);
        float camY = -(vv[4]*vv[12] + vv[5]*vv[13] + vv[6]*vv[14]);
        float camZ = -(vv[8]*vv[12] + vv[9]*vv[13] + vv[10]*vv[14]);
        basicShader.loadViewPos(camX, camY, camZ);
        basicShader.loadShininess(32f);
        loc = GLES20.glGetUniformLocation(pid, "texture1");
        if (loc >= 0) GLES20.glUniform1i(loc, 0);

        int triCount = Math.max(0, (nv - 2) * 3);
        float[] buf = new float[triCount * 12];
        int si = 0;
        for (int ti = 1; ti + 1 < nv; ti++) {
            int[] idx = {0, ti, ti + 1};
            for (int k = 0; k < 3; k++) {
                int vi = idx[k];
                buf[si++] = p.v[vi * 3]; buf[si++] = p.v[vi * 3 + 1]; buf[si++] = p.v[vi * 3 + 2];
                buf[si++] = r; buf[si++] = g; buf[si++] = b; buf[si++] = a;
                buf[si++] = 0; buf[si++] = 0;
                buf[si++] = pnx; buf[si++] = pny; buf[si++] = pnz;
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

        GLES20.glEnable(GLES20.GL_STENCIL_TEST);
        GLES20.glStencilMask(0xFF);
        GLES20.glClearStencil(0);
        GLES20.glClear(GLES20.GL_STENCIL_BUFFER_BIT);

        GLES20.glColorMask(false, false, false, false);
        GLES20.glStencilFunc(GLES20.GL_ALWAYS, 0, 0xFF);
        GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_INCR_WRAP);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triCount);

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

    // ── Textured prim ──────────────────────────────────────────────

    private void drawTexturedPrim(AndroidScene3D.Prim p, GpuSnapshot snap, float[] modelMatrix, float[] normalMatrix, boolean fanTriangulate) {
        int nv = p.v.length / 3;
        if (nv == 0) return;
        int triCount = fanTriangulate ? Math.max(0, (nv - 2) * 3) : nv;
        if (triCount == 0) return;

        int pid = basicShader.getProgramID();
        basicShader.start();

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
        float[] v = snap.view;
        float camX = -(v[0]*v[12] + v[1]*v[13] + v[2]*v[14]);
        float camY = -(v[4]*v[12] + v[5]*v[13] + v[6]*v[14]);
        float camZ = -(v[8]*v[12] + v[9]*v[13] + v[10]*v[14]);
        basicShader.loadViewPos(camX, camY, camZ);
        basicShader.loadShininess(32f);

        loc = GLES20.glGetUniformLocation(pid, "texture1");
        if (loc >= 0) GLES20.glUniform1i(loc, 0);

        int tint = p.tint != 0 ? p.tint : 0xFFFFFFFF;
        float tr = ((tint >> 16) & 0xFF) / 255f;
        float tg = ((tint >> 8) & 0xFF) / 255f;
        float tb = (tint & 0xFF) / 255f;
        float ta = ((tint >> 24) & 0xFF) / 255f;

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

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        int texId = getOrCreateTexture(p.texture);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, triCount);

        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aCol);
        if (aTex >= 0) GLES20.glDisableVertexAttribArray(aTex);
        GLES20.glDisableVertexAttribArray(aNorm);
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
        if (texId == 0) return 0;
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
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
            Log.e(TAG, "texImage2D failed: 0x" + Integer.toHexString(texErr));
            GLES20.glDeleteTextures(1, new int[]{texId}, 0);
            return 0;
        }
        texCache.put(bmp, texId);
        return texId;
    }

    // ── Billboard ──────────────────────────────────────────────────

    private void drawBillboard(AndroidScene3D.Prim p, GpuSnapshot snap, float[] modelMatrix, float[] normalMatrix) {
        float cx = p.v[0], cy = p.v[1], cz = p.v[2];
        float rx = snap.view[0], ry = snap.view[4], rz = snap.view[8];
        float ux = snap.view[1], uy = snap.view[5], uz = snap.view[9];
        float hw = p.bbW / 2f, hh = p.bbH / 2f;
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
        quad.lnx = -snap.view[8]; quad.lny = -snap.view[9]; quad.lnz = -snap.view[10];
        drawTexturedPrim(quad, snap, modelMatrix, normalMatrix, false);
    }

    // ── Line batch draw ────────────────────────────────────────────

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
        GLES20.glDisableVertexAttribArray(2);
        GLES20.glDisableVertexAttribArray(3);
        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, 0);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, stride, 3 * 4);

        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertCount);

        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aCol);
    }

    // ── Batched solid draw (GPU MVP + lighting) ───────────────────

    private void drawSolidBatch(float[] buf, int vertCount, GpuSnapshot snap) {
        int pid = basicShader.getProgramID();
        basicShader.start();

        int loc;
        loc = GLES20.glGetUniformLocation(pid, "model");
        if (loc >= 0) GLES20.glUniformMatrix4fv(loc, 1, false, IDENTITY4, 0);
        loc = GLES20.glGetUniformLocation(pid, "view");
        if (loc >= 0) GLES20.glUniformMatrix4fv(loc, 1, false, snap.view, 0);
        loc = GLES20.glGetUniformLocation(pid, "projection");
        if (loc >= 0) GLES20.glUniformMatrix4fv(loc, 1, false, snap.proj, 0);
        loc = GLES20.glGetUniformLocation(pid, "normalMatrix");
        if (loc >= 0) GLES20.glUniformMatrix3fv(loc, 1, false, new float[]{1,0,0, 0,1,0, 0,0,1}, 0);
        basicShader.loadUseTexture(false);
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
        float[] v = snap.view;
        float camX = -(v[0]*v[12] + v[1]*v[13] + v[2]*v[14]);
        float camY = -(v[4]*v[12] + v[5]*v[13] + v[6]*v[14]);
        float camZ = -(v[8]*v[12] + v[9]*v[13] + v[10]*v[14]);
        basicShader.loadViewPos(camX, camY, camZ);
        basicShader.loadShininess(32f);
        loc = GLES20.glGetUniformLocation(pid, "texture1");
        if (loc >= 0) GLES20.glUniform1i(loc, 0);

        FloatBuffer fb = ByteBuffer.allocateDirect(vertCount * 12 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(buf, 0, vertCount * 12).flip();
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, solidVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, vertCount * 12 * 4, fb, GLES20.GL_STREAM_DRAW);

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

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertCount);

        GLES20.glDisableVertexAttribArray(aPos);
        GLES20.glDisableVertexAttribArray(aCol);
        if (aTex >= 0) GLES20.glDisableVertexAttribArray(aTex);
        GLES20.glDisableVertexAttribArray(aNorm);
    }

    // ── Append helpers ─────────────────────────────────────────────

    private int appendLineVerts(float[] buf, int pos, AndroidScene3D.Prim p) {
        int needed = pos + 2;
        if (needed * 7 > buf.length) return pos;
        float r = ((p.border >> 16) & 0xFF) / 255f;
        float g = ((p.border >> 8) & 0xFF) / 255f;
        float b = (p.border & 0xFF) / 255f;
        float a = ((p.border >> 24) & 0xFF) / 255f;
        buf[pos * 7] = p.v[0]; buf[pos * 7 + 1] = p.v[1]; buf[pos * 7 + 2] = p.v[2];
        buf[pos * 7 + 3] = r; buf[pos * 7 + 4] = g; buf[pos * 7 + 5] = b; buf[pos * 7 + 6] = a;
        buf[(pos + 1) * 7] = p.v[3]; buf[(pos + 1) * 7 + 1] = p.v[4]; buf[(pos + 1) * 7 + 2] = p.v[5];
        buf[(pos + 1) * 7 + 3] = r; buf[(pos + 1) * 7 + 4] = g; buf[(pos + 1) * 7 + 5] = b; buf[(pos + 1) * 7 + 6] = a;
        return pos + 2;
    }

    private int appendBorderLines(float[] buf, int pos, AndroidScene3D.Prim p) {
        if (p.border == 0 || p.border == p.fill) return pos;
        int nv = p.v.length / 3;
        int needed = pos + nv * 2;
        if (needed * 7 > buf.length) return pos;
        float br = ((p.border >> 16) & 0xFF) / 255f;
        float bg = ((p.border >> 8) & 0xFF) / 255f;
        float bb = (p.border & 0xFF) / 255f;
        float ba = ((p.border >> 24) & 0xFF) / 255f;
        for (int i = 0; i < nv; i++) {
            int ni = (i + 1) % nv;
            buf[pos * 7] = p.v[i * 3]; buf[pos * 7 + 1] = p.v[i * 3 + 1]; buf[pos * 7 + 2] = p.v[i * 3 + 2];
            buf[pos * 7 + 3] = br; buf[pos * 7 + 4] = bg; buf[pos * 7 + 5] = bb; buf[pos * 7 + 6] = ba;
            pos++;
            buf[pos * 7] = p.v[ni * 3]; buf[pos * 7 + 1] = p.v[ni * 3 + 1]; buf[pos * 7 + 2] = p.v[ni * 3 + 2];
            buf[pos * 7 + 3] = br; buf[pos * 7 + 4] = bg; buf[pos * 7 + 5] = bb; buf[pos * 7 + 6] = ba;
            pos++;
        }
        return pos;
    }

    // ── Text drawing (pbuffer mode: on target bitmap) ──────────────

    private void drawTextPrims(GpuSnapshot snap, Bitmap targetBitmap) {
        Canvas c = new Canvas(targetBitmap);
        for (AndroidScene3D.Prim p : snap.prims) {
            if (p.kind != AndroidScene3D.TEXT || p.text == null) continue;
            float[] screen = project3Dto2D(p.v[0], p.v[1], p.v[2], snap);
            if (screen == null) continue;
            textPaint.setColor(p.fill);
            textPaint.setTextSize(Math.max(1f, p.textSize));
            textPaint.setAntiAlias(true);
            Paint.Align align = p.ax < 0.25f ? Paint.Align.LEFT : p.ax > 0.75f ? Paint.Align.RIGHT : Paint.Align.CENTER;
            textPaint.setTextAlign(align);
            float x = screen[0];
            if (align == Paint.Align.CENTER) x += (0.5f - p.ax) * textPaint.measureText(p.text);
            else if (align == Paint.Align.RIGHT) x += (1f - p.ax) * textPaint.measureText(p.text);
            float y = screen[1] + (0.5f - p.ay) * textPaint.getTextSize();
            c.drawText(p.text, x, y, textPaint);
        }
    }

    // ── Text drawing (window mode: to overlay bitmap) ──────────────

    private void drawTextPrimsToOverlay(GpuSnapshot snap) {
        boolean hasText = false;
        for (AndroidScene3D.Prim p : snap.prims) {
            if (p.kind == AndroidScene3D.TEXT && p.text != null) { hasText = true; break; }
        }
        if (!hasText) {
            textOverlayBitmap = null;
            return;
        }
        if (textOverlayBitmap == null || textOverlayBitmap.getWidth() != snap.viewW
                || textOverlayBitmap.getHeight() != snap.viewH) {
            if (textOverlayBitmap != null) textOverlayBitmap.recycle();
            textOverlayBitmap = Bitmap.createBitmap(snap.viewW, snap.viewH, Bitmap.Config.ARGB_8888);
        }
        textOverlayBitmap.eraseColor(0);
        Canvas c = new Canvas(textOverlayBitmap);
        for (AndroidScene3D.Prim p : snap.prims) {
            if (p.kind != AndroidScene3D.TEXT || p.text == null) continue;
            float[] screen = project3Dto2D(p.v[0], p.v[1], p.v[2], snap);
            if (screen == null) continue;
            textPaint.setColor(p.fill);
            textPaint.setTextSize(Math.max(1f, p.textSize));
            textPaint.setAntiAlias(true);
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

    // ── Matrix utility ────────────────────────────────────────────

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

    // ── GL thread ──────────────────────────────────────────────────

    private class GlThread extends Thread {
        volatile boolean running = true;

        @Override
        public void run() {
            try {
                initEgl();
                initShaders();
                initialized = true;
                Log.i(TAG, "GL thread initialized: " + renderW + "x" + renderH + " window=" + useWindowMode);
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
                if (snap != null) {
                    Log.i(TAG, "GPU render: prims=" + snap.prims.size()
                            + " viewW=" + snap.viewW + " viewH=" + snap.viewH
                            + " window=" + useWindowMode);
                    renderFrame(snap, target);
                }
                if (pendingLatch != null) pendingLatch.countDown();
            }
            destroyEgl();
        }
    }
}
