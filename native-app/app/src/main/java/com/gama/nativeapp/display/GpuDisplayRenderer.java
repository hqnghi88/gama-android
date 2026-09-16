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
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.SparseIntArray;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.opengles.GL10;

import android.opengl.GLES20;

/**
 * GPU-accelerated 3D renderer using OpenGL ES 2.0.
 * <p>
 * Renders the {@link AndroidScene3D.Prim} scene graph on a dedicated GL thread
 * with Phong lighting (ambient + directional/point/spot), texture mapping,
 * depth testing, and backface culling. Text prims are drawn on the CPU after
 * readback so the existing Canvas overlay system stays unchanged.
 * <p>
 * Thread model: the simulation thread calls {@link #renderSync} which blocks
 * until the GL thread finishes rendering into the target bitmap (triple-buffer
 * integration).
 */
public final class GpuDisplayRenderer {

    private static final String TAG = "GpuDisplayRenderer";
    private static final int MAX_LIGHTS = 8;

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
    private int solidProgram, lineProgram;
    private int solidVbo, lineVbo;
    private int[] intBuf;
    private int renderW, renderH;
    private boolean initialized = false;

    // Texture cache: Bitmap identity → GL texture name
    private final Map<Bitmap, Integer> texCache = new HashMap<>();
    private int texVbo;

    // Reusable line VBO attribute buffer
    private FloatBuffer lineFloatBuf;

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
        // ── Solid/textured shader (Phong lighting) ───────────────────
        String solidVert =
            "attribute vec3 aPos;\n" +
            "attribute vec3 aNormal;\n" +
            "attribute vec4 aColor;\n" +
            "attribute vec2 aUV;\n" +
            "uniform mat4 uMVP;\n" +
            "varying vec3 vW;\n" +
            "varying vec3 vN;\n" +
            "varying vec4 vCol;\n" +
            "varying vec2 vUV;\n" +
            "void main(){\n" +
            "  vW = aPos; vN = aNormal; vCol = aColor; vUV = aUV;\n" +
            "  gl_Position = uMVP * vec4(aPos, 1.0);\n" +
            "}\n";

        String solidFrag =
            "precision mediump float;\n" +
            "varying vec3 vW;\n" +
            "varying vec3 vN;\n" +
            "varying vec4 vCol;\n" +
            "varying vec2 vUV;\n" +
            "uniform int uTextured;\n" +
            "uniform sampler2D uTex;\n" +
            "uniform vec4 uTint;\n" +
            "uniform vec3 uAmbient;\n" +
            "uniform int uNumLights;\n" +
            "uniform int uLType[8];\n" +
            "uniform vec3 uLCol[8];\n" +
            "uniform vec3 uLPos[8];\n" +
            "uniform vec3 uLDir[8];\n" +
            "uniform float uLCa[8], uLLa[8], uLQa[8], uLCos[8];\n" +
            "void main(){\n" +
            "  vec3 N = normalize(vN);\n" +
            "  vec4 base;\n" +
            "  if(uTextured==1){\n" +
            "    base = texture2D(uTex, vUV) * uTint;\n" +
            "  } else {\n" +
            "    base = vCol;\n" +
            "  }\n" +
            "  vec3 col = base.rgb * uAmbient;\n" +
            "  for(int i=0;i<uNumLights && i<8;i++){\n" +
            "    int t = uLType[i];\n" +
            "    if(t==1){\n" +
            "      float d = abs(dot(N, uLDir[i]));\n" +
            "      col += base.rgb * uLCol[i] * d;\n" +
            "    } else if(t==2){\n" +
            "      vec3 L = uLPos[i] - vW;\n" +
            "      float dist = length(L);\n" +
            "      L = L / dist;\n" +
            "      float att = uLCa[i] + uLLa[i]*dist + uLQa[i]*dist*dist;\n" +
            "      float d = abs(dot(N, L));\n" +
            "      col += base.rgb * uLCol[i] * d / max(att, 0.001);\n" +
            "    } else if(t==3){\n" +
            "      vec3 L = uLPos[i] - vW;\n" +
            "      float dist = length(L);\n" +
            "      L = L / dist;\n" +
            "      if(dot(-L, uLDir[i]) >= uLCos[i]){\n" +
            "        float att = uLCa[i] + uLLa[i]*dist + uLQa[i]*dist*dist;\n" +
            "        float d = max(dot(N, L), 0.0);\n" +
            "        col += base.rgb * uLCol[i] * d / max(att, 0.001);\n" +
            "      }\n" +
            "    }\n" +
            "  }\n" +
            "  gl_FragColor = vec4(col, base.a);\n" +
            "}\n";

        int sv = compileShader(GLES20.GL_VERTEX_SHADER, solidVert);
        int sf = compileShader(GLES20.GL_FRAGMENT_SHADER, solidFrag);
        solidProgram = linkProgram(sv, sf);
        GLES20.glDeleteShader(sv);
        GLES20.glDeleteShader(sf);

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

        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_CULL_FACE);
        GLES20.glLineWidth(1.0f);
    }

    // ── Per-frame render ───────────────────────────────────────────────

    private void renderFrame(GpuSnapshot snap, Bitmap targetBitmap) {
        try {
            if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return;

            int w = snap.viewW, h = snap.viewH;
            GLES20.glViewport(0, 0, w, h);

            float bgR = ((snap.bgColor >> 16) & 0xFF) / 255f;
            float bgG = ((snap.bgColor >> 8) & 0xFF) / 255f;
            float bgB = (snap.bgColor & 0xFF) / 255f;
            GLES20.glClearColor(bgR, bgG, bgB, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

            // MVP = proj × view (model is identity)
            float[] mvp = new float[16];
            mat4Mul(mvp, snap.proj, snap.view);

            // ── Solid batch ──────────────────────────────────────────
            int solidVertCount = 0;
            for (AndroidScene3D.Prim p : snap.prims) {
                if (p.kind == AndroidScene3D.POLY && p.texture == null) {
                    int nv = p.v.length / 3;
                    solidVertCount += Math.max(0, (nv - 2) * 3);
                }
            }
            if (solidVertCount > 0) {
                float[] solidBuf = new float[solidVertCount * 10]; // pos3+norm3+col4
                int si = 0;
                for (AndroidScene3D.Prim p : snap.prims) {
                    if (p.kind == AndroidScene3D.POLY && p.texture == null) {
                        int nv = p.v.length / 3;
                        float r = ((p.fill >> 16) & 0xFF) / 255f;
                        float g = ((p.fill >> 8) & 0xFF) / 255f;
                        float b = (p.fill & 0xFF) / 255f;
                        float a = ((p.fill >>> 24) & 0xFF) / 255f;
                        for (int ti = 1; ti + 1 < nv; ti++) {
                            int[] idx = {0, ti, ti + 1};
                            for (int vi : idx) {
                                solidBuf[si++] = p.v[vi * 3];
                                solidBuf[si++] = p.v[vi * 3 + 1];
                                solidBuf[si++] = p.v[vi * 3 + 2];
                                solidBuf[si++] = p.lnx;
                                solidBuf[si++] = p.lny;
                                solidBuf[si++] = p.lnz;
                                solidBuf[si++] = r;
                                solidBuf[si++] = g;
                                solidBuf[si++] = b;
                                solidBuf[si++] = a;
                            }
                        }
                    }
                }
                drawSolidBatch(solidBuf, solidVertCount, mvp, snap);
            }

            // ── Lines ───────────────────────────────────────────────
            int lineVertCount = 0;
            for (AndroidScene3D.Prim p : snap.prims) {
                if (p.kind == AndroidScene3D.LINE) lineVertCount += 2;
            }
            if (lineVertCount > 0) {
                float[] lineBuf = new float[lineVertCount * 7]; // pos3+col4
                int li = 0;
                for (AndroidScene3D.Prim p : snap.prims) {
                    if (p.kind == AndroidScene3D.LINE) {
                        float r = ((p.border >> 16) & 0xFF) / 255f;
                        float g = ((p.border >> 8) & 0xFF) / 255f;
                        float b = (p.border & 0xFF) / 255f;
                        float a = ((p.border >> 24) & 0xFF) / 255f;
                        lineBuf[li++] = p.v[0]; lineBuf[li++] = p.v[1]; lineBuf[li++] = p.v[2];
                        lineBuf[li++] = r; lineBuf[li++] = g; lineBuf[li++] = b; lineBuf[li++] = a;
                        lineBuf[li++] = p.v[3]; lineBuf[li++] = p.v[4]; lineBuf[li++] = p.v[5];
                        lineBuf[li++] = r; lineBuf[li++] = g; lineBuf[li++] = b; lineBuf[li++] = a;
                    }
                }
                drawLineBatch(lineBuf, lineVertCount, mvp);
            }

            // ── Textured prims (per-draw, small vertex counts) ─────
            for (AndroidScene3D.Prim p : snap.prims) {
                if (p.kind == AndroidScene3D.POLY && p.texture != null) {
                    drawTexturedPrim(p, mvp, snap);
                } else if (p.kind == AndroidScene3D.BILLBOARD) {
                    drawBillboard(p, mvp, snap);
                }
            }

            // ── Readback → Bitmap ───────────────────────────────────
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE,
                    IntBuffer.wrap(intBuf));
            // Convert RGBA → ARGB (Android Bitmap format)
            // glReadPixels(GL_RGBA, GL_UNSIGNED_BYTE) on little-endian yields int = A|B<<16|G<<8|R
            for (int i = 0; i < intBuf.length; i++) {
                int rgba = intBuf[i];
                int r = rgba & 0xFF;
                int g = (rgba >> 8) & 0xFF;
                int b = (rgba >> 16) & 0xFF;
                intBuf[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            targetBitmap.eraseColor(0);
            targetBitmap.setPixels(intBuf, 0, w, 0, 0, w, h);

            // ── CPU text pass ───────────────────────────────────────
            drawTextPrims(snap, targetBitmap);

        } catch (Throwable t) {
            Log.e(TAG, "renderFrame failed", t);
        }
    }

    // ── Solid/textured batch draw ──────────────────────────────────────

    private void drawSolidBatch(float[] buf, int vertCount, float[] mvp, GpuSnapshot snap) {
        GLES20.glUseProgram(solidProgram);
        int uMVP = GLES20.glGetUniformLocation(solidProgram, "uMVP");
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        int uTextured = GLES20.glGetUniformLocation(solidProgram, "uTextured");
        GLES20.glUniform1i(uTextured, 0);
        setLightUniforms(solidProgram, snap);

        FloatBuffer fb = ByteBuffer.allocateDirect(buf.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(buf).flip();
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, solidVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, buf.length * 4, fb, GLES20.GL_STREAM_DRAW);

        int stride = 10 * 4;
        int aPos = GLES20.glGetAttribLocation(solidProgram, "aPos");
        int aNorm = GLES20.glGetAttribLocation(solidProgram, "aNormal");
        int aCol = GLES20.glGetAttribLocation(solidProgram, "aColor");
        int aUV = GLES20.glGetAttribLocation(solidProgram, "aUV");

        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, 0);
        GLES20.glEnableVertexAttribArray(aNorm);
        GLES20.glVertexAttribPointer(aNorm, 3, GLES20.GL_FLOAT, false, stride, 3 * 4);
        GLES20.glEnableVertexAttribArray(aCol);
        GLES20.glVertexAttribPointer(aCol, 4, GLES20.GL_FLOAT, false, stride, 6 * 4);
        if (aUV >= 0) GLES20.glDisableVertexAttribArray(aUV);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertCount);
    }

    private void drawTexturedPrim(AndroidScene3D.Prim p, float[] mvp, GpuSnapshot snap) {
        int vertCount = p.v.length / 3;
        if (vertCount == 0) return;

        GLES20.glUseProgram(solidProgram);
        int uMVP = GLES20.glGetUniformLocation(solidProgram, "uMVP");
        GLES20.glUniformMatrix4fv(uMVP, 1, false, mvp, 0);
        int uTextured = GLES20.glGetUniformLocation(solidProgram, "uTextured");
        GLES20.glUniform1i(uTextured, 1);
        setLightUniforms(solidProgram, snap);

        // Tint (ARGB → vec4)
        int tint = p.tint != 0 ? p.tint : 0xFFFFFFFF;
        int uTint = GLES20.glGetUniformLocation(solidProgram, "uTint");
        GLES20.glUniform4f(uTint,
                ((tint >> 16) & 0xFF) / 255f,
                ((tint >> 8) & 0xFF) / 255f,
                (tint & 0xFF) / 255f,
                ((tint >> 24) & 0xFF) / 255f);

        // Build buffer: pos3 + norm3 + uv2 = 8 floats per vertex
        float[] buf = new float[vertCount * 8];
        int bi = 0;
        for (int i = 0; i < p.v.length; i += 3) {
            buf[bi++] = p.v[i];
            buf[bi++] = p.v[i + 1];
            buf[bi++] = p.v[i + 2];
            buf[bi++] = p.lnx;
            buf[bi++] = p.lny;
            buf[bi++] = p.lnz;
            int vi = i / 3;
            if (p.uv != null && vi * 2 + 1 < p.uv.length) {
                buf[bi++] = p.uv[vi * 2];
                buf[bi++] = p.uv[vi * 2 + 1];
            } else {
                buf[bi++] = 0; buf[bi++] = 0;
            }
        }
        FloatBuffer fb = ByteBuffer.allocateDirect(buf.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        fb.put(buf).flip();
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, texVbo);
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, buf.length * 4, fb, GLES20.GL_STREAM_DRAW);

        int stride = 8 * 4;
        int aPos = GLES20.glGetAttribLocation(solidProgram, "aPos");
        int aNorm = GLES20.glGetAttribLocation(solidProgram, "aNormal");
        int aUV = GLES20.glGetAttribLocation(solidProgram, "aUV");

        GLES20.glEnableVertexAttribArray(aPos);
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, 0);
        GLES20.glEnableVertexAttribArray(aNorm);
        GLES20.glVertexAttribPointer(aNorm, 3, GLES20.GL_FLOAT, false, stride, 3 * 4);
        if (aUV >= 0) {
            GLES20.glEnableVertexAttribArray(aUV);
            GLES20.glVertexAttribPointer(aUV, 2, GLES20.GL_FLOAT, false, stride, 6 * 4);
        }
        int aCol = GLES20.glGetAttribLocation(solidProgram, "aColor");
        if (aCol >= 0) GLES20.glDisableVertexAttribArray(aCol);

        // Bind texture
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        int texId = getOrCreateTexture(p.texture);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texId);
        int uTex = GLES20.glGetUniformLocation(solidProgram, "uTex");
        GLES20.glUniform1i(uTex, 0);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, vertCount);
    }

    private int getOrCreateTexture(Object texObj) {
        if (texObj instanceof Bitmap bmp) {
            Integer cached = texCache.get(bmp);
            if (cached != null) return cached;
            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
            texCache.put(bmp, tex[0]);
            return tex[0];
        }
        return 0;
    }

    private void drawBillboard(AndroidScene3D.Prim p, float[] mvp, GpuSnapshot snap) {
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
        drawTexturedPrim(quad, mvp, snap);
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

    // ── Lighting uniforms ──────────────────────────────────────────────

    private void setLightUniforms(int program, GpuSnapshot snap) {
        int uAmb = GLES20.glGetUniformLocation(program, "uAmbient");
        GLES20.glUniform3f(uAmb, snap.ambR, snap.ambG, snap.ambB);
        int count = Math.min(snap.lights.length, MAX_LIGHTS);
        int uNum = GLES20.glGetUniformLocation(program, "uNumLights");
        GLES20.glUniform1i(uNum, count);
        for (int i = 0; i < count; i++) {
            AndroidScene3D.GamaLight l = snap.lights[i];
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uLType[" + i + "]"), l.type);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uLCol[" + i + "]"), l.r, l.g, l.b);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uLPos[" + i + "]"), l.px, l.py, l.pz);
            GLES20.glUniform3f(GLES20.glGetUniformLocation(program, "uLDir[" + i + "]"), l.ldx, l.ldy, l.ldz);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLCa[" + i + "]"), l.ca);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLLa[" + i + "]"), l.la);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLQa[" + i + "]"), l.qa);
            GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uLCos[" + i + "]"), l.cosSpot);
        }
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
