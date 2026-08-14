package com.gama.nativeapp.display;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Software perspective renderer that mimics GAMA desktop OpenGL 3D output.
 *
 * The scene is described by primitives stored in model coordinates with the Y
 * ordinate negated, so that the scene lives in the same display space as the
 * camera data returned by LayeredDisplayData (whose Y is already negated by
 * CameraDefinition.update()). An up vector of (0,0,1) is used, matching GAMA's
 * Z-up convention.
 *
 * Textured polygons carry a per-vertex (u,v) pair and are rasterized
 * per-pixel with perspective-correct interpolation and bilinear filtering into
 * a compositing bitmap (frameBmp). Non-textured primitives are drawn onto the
 * same bitmap through the canvas API, preserving the painter's-order sort.
 */
public class AndroidScene3D {

    private static final String TAG = "AndroidScene3D";

    // Ambient light color (ARGB) for textured polygons - default white (no change)
    private int ambientLight = 0xFFFFFFFF;

    // Directional ("default") light: unit direction (original model coords) + RGB intensity.
    private float sunX = 0, sunY = 0, sunZ = 1;
    private int sunColor = 0xFFFFFFFF;

    // The 3D frame is rasterized at this fraction of the view resolution and then
    // upscaled to the canvas. A software rasterizer's cost is fill-rate bound, so
    // rendering at ~0.6x resolution cuts pixel work ~2.8x with only a mild blur.
    private float renderScale = 0.6f;

    public void setRenderScale(float scale) {
        renderScale = Math.max(0.25f, Math.min(1f, scale));
    }

    public void setAmbientLight(int argb) {
        this.ambientLight = argb;
    }

    public void setDirectionalLight(double dx, double dy, double dz, int rgb) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len > 1e-9) { sunX = (float) (dx / len); sunY = (float) (dy / len); sunZ = (float) (dz / len); }
        sunColor = rgb | 0xFF000000;
    }

    static final int POLY = 0;
    static final int LINE = 1;
    static final int TEXT = 2;
    static final int BILLBOARD = 3;

    static final class Prim {
        int kind;
        float[] v;          // model coords, y negated, x,y,z interleaved
        float[] uv;         // per-vertex u,v interleaved (only when textured)
        Object texture;     // Bitmap or AnimatedTexture
        int tint;           // ARGB tint/alpha multiplier applied to the texture
        int fill;
        int border;
        float stroke;
        boolean cull;       // backface cull (only safe for faces with known outward winding)
        float depth;        // view-space z used for painter's algorithm sorting
        String text;
        float textSize;
        float ax, ay;
        float lnx, lny, lnz; // unit face normal (original model coords, Z-up)
        // Billboard fields
        float bbW, bbH, bbRot;
    }

    static float[] faceNormal(float[] model, int n) {
        float ax = model[3] - model[0], ay = model[4] - model[1], az = model[5] - model[2];
        float bx = model[6] - model[0], by = model[7] - model[1], bz = model[8] - model[2];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len > 1e-6f) { nx /= len; ny /= len; nz /= len; }
        return new float[] { nx, ny, nz };
    }

    /**
     * An animated GIF texture: pre-decoded frames plus per-frame delays in
     * milliseconds. The raster selects the frame matching the wall-clock time,
     * mirroring how desktop GAMA animates GIF textures.
     */
    static final class AnimatedTexture {
        final Bitmap[] frames;
        final int[] cumStart;   // cumulative start time (ms) of each frame
        final int total;        // total loop duration (ms)

        AnimatedTexture(Bitmap[] frames, int[] delaysMs) {
            this.frames = frames;
            this.cumStart = new int[frames.length];
            int t = 0;
            for (int i = 0; i < frames.length; i++) {
                cumStart[i] = t;
                t += Math.max(1, delaysMs[i]);
            }
            this.total = Math.max(1, t);
        }

        Bitmap currentFrame() {
            if (frames.length <= 1) return frames[0];
            long t = System.currentTimeMillis() % total;
            int lo = 0, hi = frames.length - 1;
            while (lo < hi) {
                int mid = (lo + hi + 1) >>> 1;
                if (cumStart[mid] <= t) lo = mid; else hi = mid - 1;
            }
            return frames[lo];
        }
    }

    private final List<Prim> prims = new ArrayList<>();
    private final java.util.LinkedHashMap<gama.api.ui.layers.ILayer, List<Prim>> staticCache =
            new java.util.LinkedHashMap<>();
    private final Paint fillPaint = new Paint();
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path workPath = new Path();
    private final float[] view = new float[16];
    private final float[] proj = new float[16];
    private float[] sx;
    private float[] sy;
    private final float[] p0 = new float[3];
    private final float[] p1 = new float[3];
    private final float[] p2 = new float[3];
    private final float[] p3 = new float[3];
    private float curNx = 0, curNy = 0, curNz = 1;

    // Compositing frame (viewer size): textured prims rasterize into it, all
    // other prims are drawn onto it with the canvas API.
    private Bitmap frameBmp;
    private Canvas frameCanvas;
    private int[] regionBuf;
    private final Map<Bitmap, int[]> texCache = new HashMap<>();
    private final Paint blitPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final float[] scratch3 = new float[3];
    private final List<Prim> visibleBuf = new ArrayList<>();
    private static final Comparator<Prim> depthSorter =
            (a, b) -> Float.compare(a.depth, b.depth);
    private final float[] boundsOut = new float[6];

    public AndroidScene3D() {
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f);
        textPaint.setTypeface(android.graphics.Typeface.create("Helvetica", android.graphics.Typeface.BOLD));
        textPaint.setTextSize(24f);
    }

    public void clear() { prims.clear(); }

    /**
     * Starts a new frame: keeps the prims of non-dynamic layers (refresh:false,
     * drawn once by the core layer logic) and drops the ones emitted by dynamic
     * layers, which are redrawn every frame.
     */
    public void beginFrame() {
        prims.clear();
        for (List<Prim> cached : staticCache.values()) prims.addAll(cached);
    }

    /**
     * Stores the prims emitted since {@code fromIndex} (start of the given
     * layer) so they survive the per-frame flush. Replaces any previous capture
     * for the same layer so a re-draw cannot duplicate prims.
     */
    public void captureStaticPrims(gama.api.ui.layers.ILayer layer, int fromIndex) {
        if (fromIndex < 0 || fromIndex > prims.size()) return;
        staticCache.put(layer, new ArrayList<>(prims.subList(fromIndex, prims.size())));
    }

    public int size() { return prims.size(); }

    /**
     * Adds a box (cube) centered on (cx, cy, cz) with the given extents. All six
     * faces are generated with outward winding so that backface culling is safe.
     */
    public void addBox(double cx, double cy, double cz, double w, double h, double d,
                       int fill, int border, float stroke) {
        double x0 = cx - w / 2, x1 = cx + w / 2;
        double y0 = cy - h / 2, y1 = cy + h / 2;
        double z0 = cz - d / 2, z1 = cz + d / 2;
        addQuad(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, fill, border, stroke, true);  // +z
        addQuad(x0, y1, z0, x1, y1, z0, x1, y0, z0, x0, y0, z0, fill, border, stroke, true);  // -z
        addQuad(x1, y0, z0, x1, y1, z0, x1, y1, z1, x1, y0, z1, fill, border, stroke, true);  // +x
        addQuad(x0, y0, z1, x0, y1, z1, x0, y1, z0, x0, y0, z0, fill, border, stroke, true);  // -x
        addQuad(x1, y1, z0, x0, y1, z0, x0, y1, z1, x1, y1, z1, fill, border, stroke, true);  // +y
        addQuad(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, fill, border, stroke, true);  // -y
    }

    private void addQuad(double ax, double ay, double az, double bx, double by, double bz,
                         double cx, double cy, double cz, double dx, double dy, double dz,
                         int fill, int border, float stroke, boolean cull) {
        Prim p = new Prim();
        p.kind = POLY;
        p.v = new float[]{
                (float) ax, (float) -ay, (float) az,
                (float) bx, (float) -by, (float) bz,
                (float) cx, (float) -cy, (float) cz,
                (float) dx, (float) -dy, (float) dz
        };
        p.fill = fill;
        p.border = border;
        p.stroke = stroke;
        p.cull = cull;
        prims.add(p);
    }

    /** Adds a closed/opened polygon given as raw model coords (n vertices, x,y,z interleaved). */
    public void addPoly(float[] model, int n, int fill, int border, float stroke, boolean cull) {
        Prim p = new Prim();
        p.kind = POLY;
        p.v = new float[n * 3];
        for (int i = 0; i < n; i++) {
            p.v[i * 3] = model[i * 3];
            p.v[i * 3 + 1] = -model[i * 3 + 1];
            p.v[i * 3 + 2] = model[i * 3 + 2];
        }
        float[] nn = faceNormal(model, n);
        p.lnx = nn[0]; p.lny = nn[1]; p.lnz = nn[2];
        p.fill = fill;
        p.border = border;
        p.stroke = stroke;
        p.cull = cull;
        prims.add(p);
    }

    /**
     * Adds a textured polygon given as raw model coords (n vertices, x,y,z
     * interleaved) with per-vertex u,v (n * 2 interleaved) and a tint ARGB
     * multiplier (alpha = overall opacity, rgb = colour multiplier).
     */
    public void addTexturedPoly(float[] model, int n, float[] uv, Object texture, int tint,
                                int border, float stroke) {
        Prim p = new Prim();
        p.kind = POLY;
        p.v = new float[n * 3];
        for (int i = 0; i < n; i++) {
            p.v[i * 3] = model[i * 3];
            p.v[i * 3 + 1] = -model[i * 3 + 1];
            p.v[i * 3 + 2] = model[i * 3 + 2];
        }
        p.uv = uv;
        p.texture = texture;
        p.tint = tint;
        p.border = border;
        p.stroke = stroke;
        p.cull = false;
        float[] nn = faceNormal(model, n);
        p.lnx = nn[0]; p.lny = nn[1]; p.lnz = nn[2];
        prims.add(p);
    }

    /** Adds a segment given as two raw model points (x,y,z interleaved). */
    public void addLine(float[] model, int color, float stroke) {
        Prim p = new Prim();
        p.kind = LINE;
        p.v = new float[6];
        p.v[0] = model[0];
        p.v[1] = -model[1];
        p.v[2] = model[2];
        p.v[3] = model[3];
        p.v[4] = -model[4];
        p.v[5] = model[5];
        p.border = color;
        p.stroke = stroke;
        prims.add(p);
    }

    /** Adds a billboarded textured quad anchored at a raw model point. */
    public void addBillboard(double x, double y, double z, float w, float h, float rotDeg,
                             Object texture, int tint) {
        Prim p = new Prim();
        p.kind = BILLBOARD;
        p.v = new float[]{(float) x, (float) -y, (float) z};
        p.texture = texture;
        p.tint = tint;
        p.bbW = w;
        p.bbH = h;
        p.bbRot = rotDeg;
        prims.add(p);
    }

    /** Adds a billboarded text anchored at a raw model point. */
    public void addText(double x, double y, double z, String text, int color, float size, float ax, float ay) {
        Prim p = new Prim();
        p.kind = TEXT;
        p.v = new float[]{(float) x, (float) -y, (float) z};
        p.text = text;
        p.border = color;
        p.textSize = size;
        p.ax = ax;
        p.ay = ay;
        prims.add(p);
    }

    private static void multiplyMM(float[] r, float[] a, float[] b) {
        for (int col = 0; col < 4; col++) {
            float b0 = b[col * 4], b1 = b[col * 4 + 1], b2 = b[col * 4 + 2], b3 = b[col * 4 + 3];
            for (int row = 0; row < 4; row++) {
                r[col * 4 + row] = a[row] * b0 + a[4 + row] * b1 + a[8 + row] * b2 + a[12 + row] * b3;
            }
        }
    }

    private static void lookAt(float[] m, double eyeX, double eyeY, double eyeZ,
                               double cX, double cY, double cZ, double uX, double uY, double uZ) {
        double fx = cX - eyeX, fy = cY - eyeY, fz = cZ - eyeZ;
        double fl = Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1e-12) { fx = 0; fy = 0; fz = -1; } else { fx /= fl; fy /= fl; fz /= fl; }
        double sx = fy * uZ - fz * uY, sy = fz * uX - fx * uZ, sz = fx * uY - fy * uX;
        double sl = Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1e-12) { sx = 1; sy = 0; sz = 0; } else { sx /= sl; sy /= sl; sz /= sl; }
        double ux = sy * fz - sz * fy, uy = sz * fx - sx * fz, uz = sx * fy - sy * fx;
        m[0] = (float) sx; m[4] = (float) sy; m[8] = (float) sz;  m[12] = (float) -(sx * eyeX + sy * eyeY + sz * eyeZ);
        m[1] = (float) ux; m[5] = (float) uy; m[9] = (float) uz;  m[13] = (float) -(ux * eyeX + uy * eyeY + uz * eyeZ);
        m[2] = (float) -fx; m[6] = (float) -fy; m[10] = (float) -fz; m[14] = (float) (fx * eyeX + fy * eyeY + fz * eyeZ);
        m[3] = 0; m[7] = 0; m[11] = 0; m[15] = 1;
    }

    private static void perspective(float[] m, double fovyRad, double aspect, double near, double far) {
        double f = 1.0 / Math.tan(fovyRad / 2);
        m[0] = (float) (f / aspect); m[4] = 0; m[8] = 0; m[12] = 0;
        m[1] = 0; m[5] = (float) f; m[9] = 0; m[13] = 0;
        m[2] = 0; m[6] = 0; m[10] = (float) ((far + near) / (near - far)); m[14] = (float) ((2 * far * near) / (near - far));
        m[3] = 0; m[7] = 0; m[11] = -1; m[15] = 0;
    }

    private boolean fitLocked = false;
    private int diagCount = 0;
    private int diagBoundsCount = 0;
    private float fitNeed = -1f;
    private double fitDist = -1;
    private double fitCamX, fitCamY, fitCamZ;
    private long fitStartMs = -1;

    // When true the auto-fit "covers" the viewport (zooms in so the scene fills
    // the whole screen, cropping the excess) instead of "containing" it (fits
    // the whole scene, leaving empty bands on the mismatched axis). Used by the
    // fullscreen mode so wide maps fill tall phone screens edge to edge.
    private boolean coverFit = false;

    public void setCoverFit(boolean cover) {
        coverFit = cover;
    }

    /** Re-runs the auto-fit on the next render with the current cover-fit mode. */
    public void resetFit() {
        fitLocked = false;
        fitNeed = -1f;
        fitDist = -1;
        fitStartMs = -1;
    }

    // Frozen camera frame: when set, the camera always frames exactly this world
    // rectangle (in model units) instead of the live union of all prims. Agent
    // prims that leave the world (e.g. ants foraging past the border) no longer
    // shift the bounds centre, so the ground stops panning/re-scaling every step.
    private boolean frameBoundsSet = false;
    private float frameMinX, frameMinY, frameMaxX, frameMaxY;

    /** Locks the camera framing to the given world rectangle. Pass NaN to unlock. */
    public void setFrameBounds(float minX, float minY, float maxX, float maxY) {
        if (Float.isNaN(minX) || Float.isNaN(minY) || Float.isNaN(maxX) || Float.isNaN(maxY)
                || !(maxX > minX) || !(maxY > minY)) {
            frameBoundsSet = false;
            return;
        }
        frameMinX = minX;
        frameMinY = minY;
        frameMaxX = maxX;
        frameMaxY = maxY;
        frameBoundsSet = true;
    }

    private int viewW, viewH;

    private float rotYawDeg = 0f;
    private float rotPitchDeg = 0f;

    public void rotateBy(float dyawDeg, float dpitchDeg) {
        rotYawDeg += dyawDeg;
        // rotPitchDeg is added to the scene's base camera elevation (which is
        // ~55 deg for auto-elevated flat scenes), so it must range wider than
        // +/-90 to let the effective elevation span the full [-89.5, 89.5]
        // range and reach the bottom of the scene. The effective elevation is
        // clamped to +/-89.5 in render().
        rotPitchDeg = Math.max(-179f, Math.min(179f, rotPitchDeg + dpitchDeg));
    }

    public void resetRotation() {
        rotYawDeg = 0f;
        rotPitchDeg = 0f;
    }

    public void render(Canvas canvas, double camX, double camY, double camZ,
                       double tarX, double tarY, double tarZ, double fovDeg,
                       int viewW, int viewH) {
        if (canvas == null || prims.isEmpty() || viewW <= 0 || viewH <= 0) return;

        // Rasterize at reduced resolution, then upscale when blitting.
        int rw = Math.max(2, Math.round(viewW * renderScale));
        int rh = Math.max(2, Math.round(viewH * renderScale));

        float[] b = sceneBounds();
        if (b == null) return;
        if (diagBoundsCount < 3) {
            diagBoundsCount++;
            android.util.Log.i("SHAPE3D", "SCENEBOUNDS [x:" + b[0] + ".." + b[3] + " y:" + b[1] + ".." + b[4] + " z:" + b[2] + ".." + b[5] + "] prims=" + prims.size());
            logPrimHistogram();
        }
        float minX = b[0], minY = b[1], minZ = b[2];
        float maxX = b[3], maxY = b[4], maxZ = b[5];
        // Freeze the camera framing to the first-render world rectangle (the live
        // union of all prims) instead of re-centering every frame on the moving
        // agent prims. Agent prims that leave the world (e.g. ants foraging past
        // the border) then no longer shift the bounds centre, so the ground stops
        // panning/re-scaling every step. The frame is captured from the prims
        // themselves (not the env envelope) so it matches the drawn content's
        // coordinate space exactly. It is re-captured when a new simulation
        // presents a substantially different world box.
        if (!frameBoundsSet) {
            frameMinX = minX;
            frameMinY = minY;
            frameMaxX = maxX;
            frameMaxY = maxY;
            frameBoundsSet = true;
        } else {
            float overlapX = Math.max(0f, Math.min(maxX, frameMaxX) - Math.max(minX, frameMinX));
            float overlapY = Math.max(0f, Math.min(maxY, frameMaxY) - Math.max(minY, frameMinY));
            float frameW = frameMaxX - frameMinX, frameH = frameMaxY - frameMinY;
            if (overlapX < frameW * 0.3f || overlapY < frameH * 0.3f) {
                frameMinX = minX;
                frameMinY = minY;
                frameMaxX = maxX;
                frameMaxY = maxY;
            }
        }
        minX = frameMinX;
        minY = frameMinY;
        maxX = frameMaxX;
        maxY = frameMaxY;
        float cx = (minX + maxX) / 2f, cy = (minY + maxY) / 2f, cz = (minZ + maxZ) / 2f;
        double r2 = 0;
        if (frameBoundsSet) {
            float[] xs = {minX, maxX}, ys = {minY, maxY}, zs = {minZ, maxZ};
            for (float fx : xs) {
                for (float fy : ys) {
                    for (float fz : zs) {
                        double dx = fx - cx, dy = fy - cy, dz = fz - cz;
                        double d = dx * dx + dy * dy + dz * dz;
                        if (d > r2) r2 = d;
                    }
                }
            }
        } else {
            for (Prim p : prims) {
                for (int i = 0; i < p.v.length; i += 3) {
                    double dx = p.v[i] - cx, dy = p.v[i + 1] - cy, dz = p.v[i + 2] - cz;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d > r2) r2 = d;
                }
            }
        }
        double r = Math.sqrt(r2);
        if (r < 1e-6) return;

        double fovy = fovDeg > 1 && fovDeg < 179 ? fovDeg : 45;
        double dxc = camX - tarX, dyc = camY - tarY, dzc = camZ - tarZ;
        double dist = Math.sqrt(dxc * dxc + dyc * dyc + dzc * dzc);
        if (dist < 1e-6) dist = 2 * r;
        // Auto-fit: pull the camera back along its view axis when the whole
        // scene does not fit inside the field of view (GAMA frames the env).
        // The fit is computed once against the largest scene seen during a short
        // settle period and then locked, so transient per-frame changes of the
        // scene bounds do not make the camera zoom in and out continuously.
        double halfV = Math.toRadians(fovy) / 2;
        double halfH = Math.atan(Math.tan(halfV) * (double) rw / rh);
        // Frame the scene's 2D footprint projected onto the camera's view plane,
        // instead of the 3D bounding radius. Desktop GAMA frames the environment
        // bounds so the world fills the viewport on both axes; the radius-based
        // fit zooms out too far on portrait screens and leaves the terrain
        // centred with large white borders.
        double fvx = dxc / dist, fvy = dyc / dist, fvz = dzc / dist;
        double neededFit = frameDistance(fvx, fvy, fvz, halfV, halfH, cx, cy, cz,
                minX, minY, minZ, maxX, maxY, maxZ);
        long nowMs = System.currentTimeMillis();
        if (fitStartMs < 0) fitStartMs = nowMs;
        if (fitLocked) {
            camX = fitCamX;
            camY = fitCamY;
            camZ = fitCamZ;
            dist = fitDist;
            if (neededFit > fitNeed * 1.15f) {
                fitLocked = false;
                fitStartMs = nowMs;
            }
        } else {
            if (neededFit > fitNeed) fitNeed = (float) neededFit;
            if (fitNeed > dist) {
                double ux = fvx, uy = fvy, uz = fvz;
                double back = fitNeed - dist;
                camX += ux * back;
                camY += uy * back;
                camZ += uz * back;
                dxc = camX - tarX;
                dyc = camY - tarY;
                dzc = camZ - tarZ;
                dist = Math.sqrt(dxc * dxc + dyc * dyc + dzc * dzc);
            } else if (coverFit && fitNeed < dist) {
                // Cover: zoom in until the scene fills the viewport. Uses the
                // settle-window maximum so a static frame settles to one value.
                double ux = fvx, uy = fvy, uz = fvz;
                double forward = dist - fitNeed;
                camX -= ux * forward;
                camY -= uy * forward;
                camZ -= uz * forward;
                dxc = camX - tarX;
                dyc = camY - tarY;
                dzc = camZ - tarZ;
                dist = Math.sqrt(dxc * dxc + dyc * dyc + dzc * dzc);
            }
            fitCamX = camX;
            fitCamY = camY;
            fitCamZ = camZ;
            fitDist = dist;
            if (nowMs - fitStartMs > 2000) fitLocked = true;
        }

        // Re-aim the camera so its view axis passes through the scene centre.
        // GAMA's own camera target can end up far from the drawn content when a
        // comodel mixes micro-model coordinate frames (e.g. Flood Evacuation's
        // grid cells live at the micro-model's raw DEM coordinates); without this
        // the whole scene is pushed off-screen and the display looks blank.
        {
            double offx = cx - tarX, offy = cy - tarY, offz = cz - tarZ;
            double fdot = offx * fvx + offy * fvy + offz * fvz;
            double perpX = offx - fdot * fvx;
            double perpY = offy - fdot * fvy;
            double perpZ = offz - fdot * fvz;
            double perpLen = Math.sqrt(perpX * perpX + perpY * perpY + perpZ * perpZ);
            if (perpLen > r * 0.01) {
                tarX += perpX;
                tarY += perpY;
                tarZ += perpZ;
                camX += perpX;
                camY += perpY;
                camZ += perpZ;
            }
        }

        // Elevate flat scenes. A mostly-2D world (a comodel whose micro-model
        // lives in its own coordinate frame, or any display whose GAMA camera
        // sits at ground level) is seen edge-on and renders blank when the
        // camera looks horizontally from the same height as the geometry.
        // Lift the camera above the scene centre and aim it back down at the
        // usual oblique angle, re-framing the whole footprint.
        {
            float zSpan = maxZ - minZ;
            float xySpan = Math.max(maxX - minX, maxY - minY);
            if (zSpan < xySpan * 0.05f && zSpan < (float) r * 0.2f) {
                double vx = camX - tarX, vy = camY - tarY, vz = camZ - tarZ;
                double camDist = Math.sqrt(vx * vx + vy * vy + vz * vz);
                double elevRad = camDist > 1e-9 ? Math.atan2(vz, Math.hypot(vx, vy)) : 0;
                if (elevRad < Math.toRadians(25)) {
                    double az = camDist > 1e-9 ? Math.atan2(vy, vx) : Math.PI;
                    double elev = Math.toRadians(55);
                    double fvx2 = Math.cos(elev) * Math.cos(az);
                    double fvy2 = Math.cos(elev) * Math.sin(az);
                    double fvz2 = Math.sin(elev);
                    double need = frameDistance(fvx2, fvy2, fvz2, halfV, halfH, cx, cy, cz,
                            minX, minY, minZ, maxX, maxY, maxZ);
                    tarX = cx;
                    tarY = cy;
                    tarZ = cz;
                    camX = tarX + fvx2 * need;
                    camY = tarY + fvy2 * need;
                    camZ = tarZ + fvz2 * need;
                    dist = need;
                }
            }
        }

        if (rotYawDeg != 0f || rotPitchDeg != 0f) {
            double vx = camX - tarX, vy = camY - tarY, vz = camZ - tarZ;
            double orbitDist = Math.sqrt(vx * vx + vy * vy + vz * vz);
            if (orbitDist > 1e-9) {
                double az = Math.atan2(vy, vx);
                double elev = Math.atan2(vz, Math.hypot(vx, vy));
                az += Math.toRadians(rotYawDeg);
                elev = Math.max(Math.toRadians(-89.5),
                        Math.min(Math.toRadians(89.5), elev + Math.toRadians(rotPitchDeg)));
                double horiz = orbitDist * Math.cos(elev);
                double nvz = orbitDist * Math.sin(elev);
                double ncx = tarX + horiz * Math.cos(az);
                double ncy = tarY + horiz * Math.sin(az);
                double ncz = tarZ + nvz;
                // Re-frame from the rotated view direction so the whole scene
                // footprint stays visible. The camera was framed for the original
                // view axis only; rotating to a steeper/more oblique angle makes
                // parts of the scene fall outside the viewport.
                double fvx2 = ncx - tarX, fvy2 = ncy - tarY, fvz2 = ncz - tarZ;
                double fl = Math.sqrt(fvx2 * fvx2 + fvy2 * fvy2 + fvz2 * fvz2);
                if (fl > 1e-9) {
                    double need = frameDistance(fvx2 / fl, fvy2 / fl, fvz2 / fl, halfV, halfH,
                            cx, cy, cz, minX, minY, minZ, maxX, maxY, maxZ, false);
                    if (need > orbitDist) {
                        orbitDist = need;
                        horiz = orbitDist * Math.cos(elev);
                        nvz = orbitDist * Math.sin(elev);
                        ncx = tarX + horiz * Math.cos(az);
                        ncy = tarY + horiz * Math.sin(az);
                        ncz = tarZ + nvz;
                    }
                }
                camX = ncx;
                camY = ncy;
                camZ = ncz;
                dist = orbitDist;
            }
        }

        lookAt(view, camX, camY, camZ, tarX, tarY, tarZ, 0, 0, 1);
        double near = Math.max(dist * 0.001, 0.01);
        double far = Math.max(dist + 2 * r, 2 * dist);
        perspective(proj, Math.toRadians(fovy), (double) rw / rh, near, far);
        this.viewW = rw;
        this.viewH = rh;
        this.nearPlane = (float) near;
        this.farPlane = (float) far;

        if (frameBmp == null || frameBmp.getWidth() != rw || frameBmp.getHeight() != rh) {
            frameBmp = Bitmap.createBitmap(rw, rh, Bitmap.Config.ARGB_8888);
            frameCanvas = new Canvas(frameBmp);
        }
        frameCanvas.drawColor(0, PorterDuff.Mode.CLEAR);

        if (sx == null || sx.length < 256) { sx = new float[256]; sy = new float[256]; }

        List<Prim> visible = visibleBuf;
        visible.clear();
        for (Prim p : prims) {
            if (p.cull && !visibleFromOutside(p, (float) camX, (float) camY, (float) camZ)) continue;
            p.depth = viewZ(p, cx, cy, cz);
            visible.add(p);
        }
        Collections.sort(visible, depthSorter);

        for (Prim p : visible) {
            try {
                drawPrim(frameCanvas, p, rw, rh);
            } catch (Throwable t) {
                Log.w(TAG, "drawPrim failed: " + t);
            }
        }

        diagCount++;
        if (diagCount == 2) {
            try {
                java.io.File dir = new java.io.File("/data/data/com.gama.nativeapp/files");
                dir.mkdirs();
                java.io.File f = new java.io.File(dir, "frame3d.png");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                frameBmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos);
                fos.close();
                Log.i(TAG, "WROTE " + f.getAbsolutePath() + " " + viewW + "x" + viewH);
            } catch (Throwable t) {
                Log.w(TAG, "frame dump failed: " + t);
            }
        }
        if ((diagCount & 3) == 1 && diagCount < 40) {
            int opaque = 0, total = 0;
            try {
                int w = frameBmp.getWidth(), h = frameBmp.getHeight();
                int[] px = new int[w * h];
                frameBmp.getPixels(px, 0, w, 0, 0, w, h);
                for (int i = 0; i < px.length; i += 97) {
                    total++;
                    if (((px[i] >>> 24) & 0xFF) > 0) opaque++;
                }
            } catch (Throwable t) {}
            int nPoly = 0, nText = 0, nLine = 0, nBill = 0, fillZero = 0, cullTrue = 0;
            int projOk = 0, onScreen = 0;
            for (Prim p : visible) {
                boolean ok = true, vis = false;
                for (int i = 0; i < p.v.length; i += 3) {
                    float[] out = p0;
                    if (!project(p.v[i], p.v[i + 1], p.v[i + 2], out)) { ok = false; continue; }
                    if (out[0] >= -4 && out[0] <= viewW + 4 && out[1] >= -4 && out[1] <= viewH + 4) vis = true;
                }
                if (ok) projOk++;
                if (vis) onScreen++;
            }
            StringBuilder samples = new StringBuilder();
            int si = 0;
            for (Prim p : prims) {
                switch (p.kind) {
                    case TEXT: nText++; break;
                    case LINE: nLine++; break;
                    case BILLBOARD: nBill++; break;
                    default: nPoly++; if (p.fill == 0) fillZero++; break;
                }
                if (p.cull) cullTrue++;
                if (si < 6 && p.v != null && p.v.length >= 3) {
                    String kn = p.kind == TEXT ? "TEXT" : p.kind == LINE ? "LINE" : p.kind == BILLBOARD ? "BILLBOARD" : "POLY";
                    samples.append("[").append(kn).append(" fill=0x")
                            .append(Integer.toHexString(p.fill)).append(" cull=").append(p.cull)
                            .append(" v=(").append(p.v[0]).append(",").append(p.v[1]).append(",").append(p.v[2])
                            .append(")] ");
                    si++;
                }
            }
            Log.i(TAG, "renderdiag cam=(" + camX + "," + camY + "," + camZ
                    + ") target=(" + tarX + "," + tarY + "," + tarZ
                    + ") visible=" + visible.size() + "/" + prims.size()
                    + " projOk=" + projOk + " onScreen=" + onScreen
                    + " poly=" + nPoly + " text=" + nText + " line=" + nLine + " bill=" + nBill
                    + " fillZero=" + fillZero + " cullTrue=" + cullTrue
                    + " r=" + r + " dist=" + dist + " near=" + nearPlane + " far=" + farPlane
                    + " opaqueSamples=" + opaque + "/" + total
                    + " bounds=[" + minX + "," + minY + "," + minZ + "]-[" + maxX + "," + maxY + "," + maxZ + "]"
                    + " first=" + samples);
        }

        canvas.drawBitmap(frameBmp, null, new android.graphics.RectF(0, 0, viewW, viewH), blitPaint);
    }

    /** Distance needed to frame the scene's 2D footprint from a view direction. */
    private double frameDistance(double fvx, double fvy, double fvz, double halfV, double halfH,
                                 float cx, float cy, float cz,
                                 float minX, float minY, float minZ,
                                 float maxX, float maxY, float maxZ) {
        return frameDistance(fvx, fvy, fvz, halfV, halfH, cx, cy, cz,
                minX, minY, minZ, maxX, maxY, maxZ, coverFit);
    }

    private double frameDistance(double fvx, double fvy, double fvz, double halfV, double halfH,
                                 float cx, float cy, float cz,
                                 float minX, float minY, float minZ,
                                 float maxX, float maxY, float maxZ, boolean cover) {
        double rvx = -fvz, rvy = 0, rvz = fvx; // cross(f, worldUp(0,0,1))
        double rl = Math.sqrt(rvx * rvx + rvy * rvy + rvz * rvz);
        if (rl < 1e-9) { rvx = 1; rvy = 0; rvz = 0; rl = 1; }
        rvx /= rl; rvy /= rl; rvz /= rl;
        double uvx = rvy * fvz - rvz * fvy;
        double uvy = rvz * fvx - rvx * fvz;
        double uvz = rvx * fvy - rvy * fvx;
        double maxRight = 0, maxUp = 0;
        double[] offsX = {minX - cx, maxX - cx};
        double[] offsY = {minY - cy, maxY - cy};
        double[] offsZ = {minZ - cz, maxZ - cz};
        for (double ox : offsX) {
            for (double oy : offsY) {
                for (double oz : offsZ) {
                    double px = ox * rvx + oy * rvy + oz * rvz;
                    double py = ox * uvx + oy * uvy + oz * uvz;
                    maxRight = Math.max(maxRight, Math.abs(px));
                    maxUp = Math.max(maxUp, Math.abs(py));
                }
            }
        }
        // "Contain" pulls back until both axes fit (letterbox). "Cover" zooms in
        // so the scene fills the viewport on the tighter axis, cropping the rest.
        double dRight = maxRight / Math.tan(halfH);
        double dUp = maxUp / Math.tan(halfV);
        double needed = cover ? Math.min(dRight, dUp) : Math.max(dRight, dUp);
        return needed * 1.05;
    }

    /** Returns {minX,minY,minZ,maxX,maxY,maxZ} of all prims, or null when empty. */
    private float[] sceneBounds() {
        if (prims.isEmpty()) return null;
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (Prim p : prims) {
            for (int i = 0; i < p.v.length; i += 3) {
                float x = p.v[i], y = p.v[i + 1], z = p.v[i + 2];
                if (x < minX) minX = x; if (x > maxX) maxX = x;
                if (y < minY) minY = y; if (y > maxY) maxY = y;
                if (z < minZ) minZ = z; if (z > maxZ) maxZ = z;
            }
        }
        if (!Float.isFinite(minX) || !Float.isFinite(minY) || !Float.isFinite(minZ)
                || !Float.isFinite(maxX) || !Float.isFinite(maxY) || !Float.isFinite(maxZ)) return null;
        boundsOut[0] = minX; boundsOut[1] = minY; boundsOut[2] = minZ;
        boundsOut[3] = maxX; boundsOut[4] = maxY; boundsOut[5] = maxZ;
        return boundsOut;
    }

    private void logPrimHistogram() {
        try {
            java.util.TreeMap<String, Integer> counts = new java.util.TreeMap<>();
            for (Prim p : prims) {
                for (int i = 0; i < p.v.length; i += 3) {
                    String key = Math.round(p.v[i] / 1000f) + "k," + Math.round(p.v[i + 1] / 1000f) + "k";
                    Integer c = counts.get(key);
                    counts.put(key, c == null ? 1 : c + 1);
                }
            }
            StringBuilder sb = new StringBuilder("PRIMHIST total=" + prims.size() + " ");
            for (java.util.Map.Entry<String, Integer> e : counts.entrySet()) {
                sb.append(e.getKey()).append("=").append(e.getValue()).append(" ");
            }
            android.util.Log.i("SHAPE3D", sb.toString());
        } catch (Throwable t) {}
    }

    /**
     * Renders a straight-down top view of the scene, used when GAMA never provided
     * a camera for the display (the display data's camera definition is missing).
     * The auto-fit logic inside render() frames the whole scene afterwards.
     */
    public void renderDefaultTopDown(Canvas canvas, double fovDeg, int viewW, int viewH) {
        if (canvas == null || prims.isEmpty() || viewW <= 0 || viewH <= 0) return;
        float[] b = sceneBounds();
        if (b == null) return;
        float cx, cy, cz;
        if (frameBoundsSet) {
            cx = (frameMinX + frameMaxX) / 2f;
            cy = (frameMinY + frameMaxY) / 2f;
            cz = (b[2] + b[5]) / 2f;
        } else {
            cx = (b[0] + b[3]) / 2f;
            cy = (b[1] + b[4]) / 2f;
            cz = (b[2] + b[5]) / 2f;
        }
        render(canvas, cx, cy, cz + 1.0, cx, cy, cz, fovDeg, viewW, viewH);
    }

    /** True when the face normal points towards the camera (no backface cull). */
    private boolean visibleFromOutside(Prim p, float exm, float eym, float ezm) {
        int n = p.v.length / 3;
        if (n < 3) return true;
        float ax = p.v[3] - p.v[0], ay = p.v[4] - p.v[1], az = p.v[5] - p.v[2];
        float bx = p.v[6] - p.v[3], by = p.v[7] - p.v[4], bz = p.v[8] - p.v[5];
        float nx = ay * bz - az * by, ny = az * bx - ax * bz, nz = ax * by - ay * bx;
        float cx = 0, cy = 0, cz = 0;
        for (int i = 0; i < p.v.length; i += 3) { cx += p.v[i]; cy += p.v[i + 1]; cz += p.v[i + 2]; }
        cx /= n; cy /= n; cz /= n;
        float vx = cx - exm, vy = cy - eym, vz = cz - ezm;
        // The Y ordinate negation flips the winding, so normals are inverted;
        // faces are visible when the (inverted) normal points away from the camera.
        return nx * vx + ny * vy + nz * vz >= 0;
    }

    /** View-space z of a primitive's centroid (used for painter's sorting). */
    private float viewZ(Prim p, float cx, float cy, float cz) {
        float wx = 0, wy = 0, wz = 0;
        int n = p.v.length / 3;
        for (int i = 0; i < p.v.length; i += 3) {
            wx += p.v[i]; wy += p.v[i + 1]; wz += p.v[i + 2];
        }
        wx = (wx / n - cx);
        wy = (wy / n - cy);
        wz = (wz / n - cz);
        return view[2] * wx + view[6] * wy + view[10] * wz + view[14];
    }

    /** Projects a world (negated model) vertex to screen space. Returns false if behind the camera. */
    private boolean project(float wx, float wy, float wz, float[] out) {
        float vx = view[0] * wx + view[4] * wy + view[8] * wz + view[12];
        float vy = view[1] * wx + view[5] * wy + view[9] * wz + view[13];
        float vz = view[2] * wx + view[6] * wy + view[10] * wz + view[14];
        float cw = proj[3] * vx + proj[7] * vy + proj[11] * vz + proj[15];
        if (cw <= 0) return false;
        float cx = proj[0] * vx + proj[4] * vy + proj[8] * vz + proj[12];
        float cy = proj[1] * vx + proj[5] * vy + proj[9] * vz + proj[13];
        out[0] = (cx / cw + 1f) / 2f * viewW;
        out[1] = (1f - cy / cw) / 2f * viewH;
        out[2] = vz;
        return true;
    }

    private float nearPlane;
    private float farPlane;
    private float[] vvx = new float[256];
    private float[] vvy = new float[256];
    private float[] vvz = new float[256];
    private float[] vvU = new float[256];
    private float[] vvV = new float[256];
    private float[] cvx = new float[256];
    private float[] cvy = new float[256];
    private float[] cvz = new float[256];
    private float[] cvU = new float[256];
    private float[] cvV = new float[256];
    private float[] dx = new float[256];
    private float[] dy = new float[256];
    private float[] dz = new float[256];
    private float[] dU = new float[256];
    private float[] dV = new float[256];
    private float[] q = new float[256];

    private void drawPrim(Canvas canvas, Prim p, int vw, int vh) {
        viewW = vw;
        viewH = vh;
        int n = p.v.length / 3;
        switch (p.kind) {
            case TEXT:
                if (project(p.v[0], p.v[1], p.v[2], p0)) {
                    textPaint.setColor(p.border);
                    textPaint.setTextSize(p.textSize);
                    float tw = textPaint.measureText(p.text);
                    Paint.FontMetrics fm = textPaint.getFontMetrics();
                    float th = fm.descent - fm.ascent;
                    float x = p0[0] - tw * p.ax;
                    float y = p0[1] + (th - fm.descent) * p.ay;
                    canvas.drawText(p.text, x, y - fm.ascent, textPaint);
                }
                break;
            case LINE:
                drawLineClipped(canvas, p);
                break;
            case BILLBOARD:
                drawBillboard(canvas, p);
                break;
            case POLY:
            default:
                drawPolyClipped(canvas, p);
                break;
        }
    }

    /** Renders a camera-facing billboard quad. */
    private void drawBillboard(Canvas canvas, Prim p) {
        if (p.texture == null) return;
        float cx = p.v[0], cy = p.v[1], cz = p.v[2];
        // Camera right/up in world (stored) space: for a rigid view matrix these
        // are the first two rows of the view matrix (column-major layout).
        float rx = view[0], ry = view[4], rz = view[8];
        float ux = view[1], uy = view[5], uz = view[9];

        // Apply billboard rotation around the camera-facing axis
        float rot = p.bbRot * (float) Math.PI / 180f;
        float cr = (float) Math.cos(rot);
        float sr = (float) Math.sin(rot);
        float rrx = cr * rx - sr * ux;
        float rry = cr * ry - sr * uy;
        float rrz = cr * rz - sr * uz;
        float urx = sr * rx + cr * ux;
        float ury = sr * ry + cr * uy;
        float urz = sr * rz + cr * uz;

        float hw = p.bbW * 0.5f;
        float hh = p.bbH * 0.5f;

        // Build a temporary POLY prim for the quad (stored coords, no Y negation)
        // so the existing clip + perspective-correct textured rasterizer draws it.
        // UV order keeps the bitmap upright: row 0 (v=0) is the top of the image,
        // which sits at center + hh*up (screen up) and center - hw*right (screen left).
        Prim q = new Prim();
        q.kind = POLY;
        q.v = new float[]{
                cx - hw * rrx - hh * urx, cy - hw * rry - hh * ury, cz - hw * rrz - hh * urz,
                cx + hw * rrx - hh * urx, cy + hw * rry - hh * ury, cz + hw * rrz - hh * urz,
                cx + hw * rrx + hh * urx, cy + hw * rry + hh * ury, cz + hw * rrz + hh * urz,
                cx - hw * rrx + hh * urx, cy - hw * rry + hh * ury, cz - hw * rrz + hh * urz
        };
        q.uv = new float[]{0, 1, 1, 1, 1, 0, 0, 0};
        q.texture = p.texture;
        q.tint = p.tint;
        q.cull = false;
        drawPolyClipped(canvas, q);
    }

    /** World vertex to view space. */
    private void toView(float wx, float wy, float wz, float[] out) {
        out[0] = view[0] * wx + view[4] * wy + view[8] * wz + view[12];
        out[1] = view[1] * wx + view[5] * wy + view[9] * wz + view[13];
        out[2] = view[2] * wx + view[6] * wy + view[10] * wz + view[14];
    }

    /** Projects a view-space vertex to screen. Returns false if behind the near plane. */
    private boolean projectView(float vx, float vy, float vz, float[] out) {
        float cw = proj[3] * vx + proj[7] * vy + proj[11] * vz + proj[15];
        if (cw <= 0) return false;
        float cx = proj[0] * vx + proj[4] * vy + proj[8] * vz + proj[12];
        float cy = proj[1] * vx + proj[5] * vy + proj[9] * vz + proj[13];
        out[0] = (cx / cw + 1f) / 2f * viewW;
        out[1] = (1f - cy / cw) / 2f * viewH;
        out[2] = vz;
        return true;
    }

    /**
     * Sutherland-Hodgman clip of view-space polygon vertices against the plane
     * vz = planeVz, keeping vertices where (keepBelow ? vz <= planeVz : vz >= planeVz).
     * When non-null, per-vertex u/v attributes are carried along and interpolated
     * at the newly created clip vertices.
     */
    private int clipPlane(float[] vx, float[] vy, float[] vz, float[] vu, float[] vv,
                          int n, float planeVz, boolean keepBelow,
                          float[] ox, float[] oy, float[] oz, float[] ou, float[] ov) {
        int m = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            float d1 = keepBelow ? vz[i] - planeVz : planeVz - vz[i];
            float d2 = keepBelow ? vz[j] - planeVz : planeVz - vz[j];
            boolean in1 = d1 <= 0;
            boolean in2 = d2 <= 0;
            if (in1) {
                ox[m] = vx[i]; oy[m] = vy[i]; oz[m] = vz[i];
                if (ou != null) { ou[m] = vu[i]; ov[m] = vv[i]; }
                m++;
            }
            if (in1 != in2) {
                float t = d1 / (d1 - d2);
                ox[m] = vx[i] + t * (vx[j] - vx[i]);
                oy[m] = vy[i] + t * (vy[j] - vy[i]);
                oz[m] = vz[i] + t * (vz[j] - vz[i]);
                if (ou != null) {
                    ou[m] = vu[i] + t * (vu[j] - vu[i]);
                    ov[m] = vv[i] + t * (vv[j] - vv[i]);
                }
                m++;
            }
        }
        return m;
    }

    private int polyClipDiag = 0;
    private void drawPolyClipped(Canvas canvas, Prim p) {
        int n = p.v.length / 3;
        if (n < 3) return;
        if (n > vvx.length) growClipBuffers(n);
        boolean textured = p.texture != null && p.uv != null;
        for (int i = 0; i < n; i++) {
            toView(p.v[i * 3], p.v[i * 3 + 1], p.v[i * 3 + 2], scratch3);
            vvx[i] = scratch3[0]; vvy[i] = scratch3[1]; vvz[i] = scratch3[2];
            if (textured) {
                vvU[i] = p.uv[i * 2];
                vvV[i] = p.uv[i * 2 + 1];
            }
        }
        int clipped = clipPlane(vvx, vvy, vvz, textured ? vvU : null, textured ? vvV : null,
                n, -nearPlane, true, cvx, cvy, cvz, textured ? cvU : null, textured ? cvV : null);
        clipped = clipPlane(cvx, cvy, cvz, textured ? cvU : null, textured ? cvV : null,
                clipped, -farPlane, false, dx, dy, dz, textured ? dU : null, textured ? dV : null);
        if (clipped < 3) return;
        if (sx.length < clipped) { sx = new float[clipped]; sy = new float[clipped]; }
        for (int i = 0; i < clipped; i++) {
            if (!projectView(dx[i], dy[i], dz[i], p0)) return;
            sx[i] = p0[0];
            sy[i] = p0[1];
            q[i] = -dz[i];
        }
        if (polyClipDiag < 4 && Math.abs(p.v[0]) > 1e6f) {
            polyClipDiag++;
            StringBuilder sb = new StringBuilder();
            sb.append("clipDiag n=").append(n).append(" clipped=").append(clipped)
                    .append(" firstV=(").append(p.v[0]).append(",").append(p.v[1]).append(",").append(p.v[2]).append(")");
            for (int i = 0; i < clipped && i < 8; i++) {
                sb.append(" sx").append(i).append("=").append(sx[i]).append(" sy").append(i).append("=").append(sy[i])
                        .append(" q").append(i).append("=").append(q[i]);
            }
            sb.append(" camZ=").append(p.v[2]);
            Log.i(TAG, sb.toString());
        }
        if (textured) {
            fillTexturedPoly(canvas, p, clipped);
        } else {
            workPath.reset();
            workPath.moveTo(sx[0], sy[0]);
            for (int i = 1; i < clipped; i++) workPath.lineTo(sx[i], sy[i]);
            workPath.close();
            if (p.fill != 0) {
                fillPaint.setColor(litColor(p.fill, p.lnx, p.lny, p.lnz));
                canvas.drawPath(workPath, fillPaint);
            }
        }
        if (p.border != 0 && p.border != p.fill) {
            strokePaint.setColor(p.border);
            strokePaint.setStrokeWidth(p.stroke);
            canvas.drawPath(workPath, strokePaint);
        }
    }

    /** Per-pixel fills a textured clipped polygon into the compositing frame. */
    private void fillTexturedPoly(Canvas canvas, Prim p, int clipped) {
        Bitmap texBmp = currentTextureBitmap(p.texture);
        if (texBmp == null) {
            fillPoly(canvas, p, clipped, p.tint);
            return;
        }
        curNx = p.lnx;
        curNy = p.lny;
        curNz = p.lnz;
        int[] tex = texCache.get(texBmp);
        int tw, th;
        if (tex == null) {
            tw = texBmp.getWidth();
            th = texBmp.getHeight();
            if (tw <= 0 || th <= 0) return;
            tex = new int[tw * th];
            texBmp.getPixels(tex, 0, tw, 0, 0, tw, th);
            if (texCache.size() > 64) texCache.clear();
            texCache.put(texBmp, tex);
        } else {
            tw = texBmp.getWidth();
            th = texBmp.getHeight();
        }

        float minSX = Float.POSITIVE_INFINITY, maxSX = Float.NEGATIVE_INFINITY;
        float minSY = Float.POSITIVE_INFINITY, maxSY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < clipped; i++) {
            if (sx[i] < minSX) minSX = sx[i];
            if (sx[i] > maxSX) maxSX = sx[i];
            if (sy[i] < minSY) minSY = sy[i];
            if (sy[i] > maxSY) maxSY = sy[i];
        }
        int bx0 = (int) Math.floor(minSX);
        int by0 = (int) Math.floor(minSY);
        int bx1 = (int) Math.ceil(maxSX);
        int by1 = (int) Math.ceil(maxSY);
        if (bx0 < 0) bx0 = 0;
        if (by0 < 0) by0 = 0;
        if (bx1 > viewW - 1) bx1 = viewW - 1;
        if (by1 > viewH - 1) by1 = viewH - 1;
        int bw = bx1 - bx0 + 1;
        int bh = by1 - by0 + 1;
        if (bw <= 0 || bh <= 0) return;
        if (regionBuf == null || regionBuf.length < bw * bh) {
            regionBuf = new int[Math.max(bw * bh, 256 * 256)];
        }
        frameBmp.getPixels(regionBuf, 0, bw, bx0, by0, bw, bh);
        for (int i = 1; i < clipped - 1; i++) {
            rasterTriangle(regionBuf, bw, bh, bx0, by0,
                    sx[i], sy[i], dU[i], dV[i], q[i],
                    sx[0], sy[0], dU[0], dV[0], q[0],
                    sx[i + 1], sy[i + 1], dU[i + 1], dV[i + 1], q[i + 1],
                    p.tint, tex, tw, th);
        }
        frameBmp.setPixels(regionBuf, 0, bw, bx0, by0, bw, bh);
    }

    /** Resolves a prim texture to the Bitmap to sample right now (animated textures pick their current frame). */
    private static Bitmap currentTextureBitmap(Object texture) {
        if (texture instanceof Bitmap b) return b;
        if (texture instanceof AnimatedTexture at) return at.currentFrame();
        return null;
    }

    /** Modulates an ARGB fill by the GAMA lighting model (ambient + diffuse * max(N.L,0)), preserving alpha. */
    private int litColor(int argb, float nx, float ny, float nz) {
        if (nx == 0 && ny == 0 && nz == 0) return argb;
        float nd = nx * sunX + ny * sunY + nz * sunZ;
        if (nd < 0) nd = 0;
        int a = (argb >>> 24) & 0xFF;
        int r = (argb >>> 16) & 0xFF, g = (argb >>> 8) & 0xFF, b = argb & 0xFF;
        float ar = (ambientLight >>> 16) & 0xFF, ag = (ambientLight >>> 8) & 0xFF, ab = ambientLight & 0xFF;
        float sr = (sunColor >>> 16) & 0xFF, sg = (sunColor >>> 8) & 0xFF, sb = sunColor & 0xFF;
        float fr = Math.min(1f, ar / 255f + (sr / 255f) * nd);
        float fg = Math.min(1f, ag / 255f + (sg / 255f) * nd);
        float fb = Math.min(1f, ab / 255f + (sb / 255f) * nd);
        int rr = Math.round(r * fr); if (rr > 255) rr = 255;
        int gg = Math.round(g * fg); if (gg > 255) gg = 255;
        int bb = Math.round(b * fb); if (bb > 255) bb = 255;
        return (a << 24) | (rr << 16) | (gg << 8) | bb;
    }

    /** Fills the clipped polygon with a flat color (used when a texture bitmap is unavailable). */
    private void fillPoly(Canvas canvas, Prim p, int clipped, int color) {
        if (sx == null || clipped == 0) return;
        workPath.reset();
        workPath.moveTo(sx[0], sy[0]);
        for (int i = 1; i < clipped; i++) workPath.lineTo(sx[i], sy[i]);
        workPath.close();
        fillPaint.setColor(color);
        canvas.drawPath(workPath, fillPaint);
    }

    private void rasterTriangle(int[] buf, int bw, int bh, int ox, int oy,                                float x0, float y0, float u0, float v0, float q0,
                                float x1, float y1, float u1, float v1, float q1,
                                float x2, float y2, float u2, float v2, float q2,
                                int tint, int[] tex, int tw, int th) {
        int minx = (int) Math.floor(Math.min(x0, Math.min(x1, x2)));
        int maxx = (int) Math.ceil(Math.max(x0, Math.max(x1, x2)));
        int miny = (int) Math.floor(Math.min(y0, Math.min(y1, y2)));
        int maxy = (int) Math.ceil(Math.max(y0, Math.max(y1, y2)));
        if (minx < ox) minx = ox;
        if (miny < oy) miny = oy;
        if (maxx > ox + bw - 1) maxx = ox + bw - 1;
        if (maxy > oy + bh - 1) maxy = oy + bh - 1;
        if (maxx < minx || maxy < miny) return;

        float invq0 = 1f / q0, invq1 = 1f / q1, invq2 = 1f / q2;
        float uq0 = u0 * invq0, uq1 = u1 * invq1, uq2 = u2 * invq2;
        float vq0 = v0 * invq0, vq1 = v1 * invq1, vq2 = v2 * invq2;

        int ta = (tint >>> 24) & 0xFF;
        int tr = (tint >>> 16) & 0xFF, tg = (tint >>> 8) & 0xFF, tb = tint & 0xFF;

        // Lighting factors depend only on the prim's face normal and the light
        // setup, so they are constant for every pixel of this triangle. Computing
        // them here (instead of inside the pixel loop) removes two float divides
        // per pixel from the hottest path.
        float ndotl = curNx * sunX + curNy * sunY + curNz * sunZ;
        float nd = ndotl > 0 ? ndotl : 0f;
        int ar = (ambientLight >>> 16) & 0xFF;
        int ag = (ambientLight >>> 8) & 0xFF;
        int ab = ambientLight & 0xFF;
        int sr255 = (sunColor >>> 16) & 0xFF;
        int sg255 = (sunColor >>> 8) & 0xFF;
        int sb255 = sunColor & 0xFF;
        float fr = Math.min(1f, ar / 255f + (sr255 / 255f) * nd);
        float fg = Math.min(1f, ag / 255f + (sg255 / 255f) * nd);
        float fb = Math.min(1f, ab / 255f + (sb255 / 255f) * nd);

        for (int py = miny; py <= maxy; py++) {
            float fy = py;
            float w0b = (x1 - x0) * (fy - y0) - (y1 - y0) * (minx - x0);
            float w1b = (x2 - x1) * (fy - y1) - (y2 - y1) * (minx - x1);
            float w2b = (x0 - x2) * (fy - y2) - (y0 - y2) * (minx - x2);
            int row = (py - oy) * bw;
            for (int px = minx; px <= maxx; px++) {
                float fx = px;
                float w0 = w0b - (y1 - y0) * (fx - minx);
                float w1 = w1b - (y2 - y1) * (fx - minx);
                float w2 = w2b - (y0 - y2) * (fx - minx);
                boolean pos = w0 >= 0 && w1 >= 0 && w2 >= 0;
                boolean neg = w0 <= 0 && w1 <= 0 && w2 <= 0;
                if (!pos && !neg) continue;

                float wsum = w0 * invq0 + w1 * invq1 + w2 * invq2;
                if (wsum == 0) continue;
                // Barycentric weights: w0 is the edge function of edge 0-1 (weight of vertex 2),
                // w1 of edge 1-2 (weight of vertex 0), w2 of edge 2-0 (weight of vertex 1).
                float uu = (w1 * uq0 + w2 * uq1 + w0 * uq2) / wsum;
                float vv = (w1 * vq0 + w2 * vq1 + w0 * vq2) / wsum;

                float fxr = uu * tw - 0.5f;
                int x0i = (int) fxr;
                float tx = fxr - x0i;
                int x1i = x0i + 1;
                if (x0i < 0) x0i = 0; else if (x0i >= tw) x0i = tw - 1;
                if (x1i < 0) x1i = 0; else if (x1i >= tw) x1i = tw - 1;
                float fyv = vv * th - 0.5f;
                int y0i = (int) fyv;
                float ty = fyv - y0i;
                int y1i = y0i + 1;
                if (y0i < 0) y0i = 0; else if (y0i >= th) y0i = th - 1;
                if (y1i < 0) y1i = 0; else if (y1i >= th) y1i = th - 1;

                int c00 = tex[y0i * tw + x0i];
                int c10 = tex[y0i * tw + x1i];
                int c01 = tex[y1i * tw + x0i];
                int c11 = tex[y1i * tw + x1i];
                float invTx = 1f - tx, invTy = 1f - ty;
                int sr = (int) (((c00 >>> 16 & 0xFF) * invTx + (c10 >>> 16 & 0xFF) * tx) * invTy
                        + ((c01 >>> 16 & 0xFF) * invTx + (c11 >>> 16 & 0xFF) * tx) * ty);
                int sg = (int) (((c00 >>> 8 & 0xFF) * invTx + (c10 >>> 8 & 0xFF) * tx) * invTy
                        + ((c01 >>> 8 & 0xFF) * invTx + (c11 >>> 8 & 0xFF) * tx) * ty);
                int sb = (int) (((c00 & 0xFF) * invTx + (c10 & 0xFF) * tx) * invTy
                        + ((c01 & 0xFF) * invTx + (c11 & 0xFF) * tx) * ty);
                int sa = (int) (((c00 >>> 24 & 0xFF) * invTx + (c10 >>> 24 & 0xFF) * tx) * invTy
                        + ((c01 >>> 24 & 0xFF) * invTx + (c11 >>> 24 & 0xFF) * tx) * ty);

                int a = (sa * ta) >> 8;
                if (a <= 0) continue;
                float r = (sr * tr >> 8) * fr;
                float g = (sg * tg >> 8) * fg;
                float b = (sb * tb >> 8) * fb;
                int ri = r > 255 ? 255 : r < 0 ? 0 : (int) r;
                int gi = g > 255 ? 255 : g < 0 ? 0 : (int) g;
                int bi = b > 255 ? 255 : b < 0 ? 0 : (int) b;
                if (a >= 255) {
                    buf[row + (px - ox)] = (0xFF << 24) | (ri << 16) | (gi << 8) | bi;
                } else {
                    int dst = buf[row + (px - ox)];
                    int da = (dst >>> 24) & 0xFF;
                    int outA = a + (da * (255 - a)) / 255;
                    int outR = (ri * a + (dst >>> 16 & 0xFF) * (255 - a)) / 255;
                    int outG = (gi * a + (dst >>> 8 & 0xFF) * (255 - a)) / 255;
                    int outB = (bi * a + (dst & 0xFF) * (255 - a)) / 255;
                    buf[row + (px - ox)] = (outA << 24) | (outR << 16) | (outG << 8) | outB;
                }
            }
        }
    }

    private void drawLineClipped(Canvas canvas, Prim p) {
        toView(p.v[0], p.v[1], p.v[2], scratch3);
        float ax = scratch3[0], ay = scratch3[1], az = scratch3[2];
        toView(p.v[3], p.v[4], p.v[5], scratch3);
        float bx = scratch3[0], by = scratch3[1], bz = scratch3[2];
        float d1 = az - (-nearPlane), d2 = bz - (-nearPlane);
        if (d1 > 0 && d2 > 0) return;
        if (d1 > 0 != d2 > 0) {
            float t = d1 / (d1 - d2);
            if (d1 > 0) { ax += t * (bx - ax); ay += t * (by - ay); az += t * (bz - az); }
            else { bx += t * (ax - bx); by += t * (ay - by); bz += t * (az - bz); }
        }
        if (!projectView(ax, ay, az, p0)) return;
        if (!projectView(bx, by, bz, p1)) return;
        strokePaint.setColor(p.border);
        strokePaint.setStrokeWidth(p.stroke);
        canvas.drawLine(p0[0], p0[1], p1[0], p1[1], strokePaint);
    }

    private void growClipBuffers(int n) {
        int size = Math.max(n, vvx.length * 2);
        vvx = new float[size]; vvy = new float[size]; vvz = new float[size];
        vvU = new float[size]; vvV = new float[size];
        cvx = new float[size]; cvy = new float[size]; cvz = new float[size];
        cvU = new float[size]; cvV = new float[size];
        dx = new float[size]; dy = new float[size]; dz = new float[size];
        dU = new float[size]; dV = new float[size];
        q = new float[size];
    }
}
