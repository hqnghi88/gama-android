package com.gama.nativeapp.display;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryCollection;
import org.locationtech.jts.geom.Lineal;
import org.locationtech.jts.geom.LinearRing;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.MultiLineString;
import org.locationtech.jts.geom.MultiPolygon;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.geom.Puntal;

import gama.api.ui.displays.IAsset;
import gama.api.ui.layers.IDrawingAttributes;
import gama.api.ui.layers.ILayer;
import gama.api.utils.interfaces.IImageProvider;
import gama.api.kernel.agent.IAgent;
import gama.api.types.geometry.GamaPointFactory;
import gama.api.types.geometry.IPoint;
import gama.api.types.geometry.IShape;
import gama.core.outputs.display.AbstractDisplayGraphics;
import gama.core.outputs.layers.MeshLayerData;
import gama.core.outputs.layers.OverlayLayer;
import gama.api.runtime.scope.IScope;
import gama.api.types.color.IColor;
import gama.api.utils.geometry.AxisAngle;
import gama.api.utils.geometry.IEnvelope;
import gama.api.utils.geometry.Scaling3D;
import gama.api.types.file.IGamaFile;
import gama.core.util.file.GamaGeometryFile;
import gama.core.util.file.GamaObjFile;
import gama.core.util.matrix.GamaField;
import gama.extension.image.GamaImageFile;
import gama.api.types.matrix.IField;
import gama.gaml.operators.Maths;
import gama.gaml.statements.draw.DrawingAttributes;
import gama.api.ui.layers.IMeshColorProvider;
import gama.gaml.statements.draw.MeshDrawingAttributes;
import gama.gaml.statements.draw.ShapeDrawingAttributes;
import gama.gaml.statements.draw.TextDrawingAttributes;
import gama.core.outputs.LayeredDisplayOutput;

public class AndroidDisplayGraphics extends AbstractDisplayGraphics {

    private Canvas canvas;
    private Canvas mainCanvas;
    private Bitmap overlayBitmap;
    private Canvas overlayCanvas;
    private boolean overlayActive = false;
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bgPaint = new Paint();
    private final Path workPath = new Path();
    private final RectF workRect = new RectF();

    private float currentAlpha = 1f;
    private Rectangle2D.Double rect = new Rectangle2D.Double();
    private final android.graphics.RectF fastRect = new android.graphics.RectF();
    private int drawnShapesCount = 0;
    private static double worldSurfaceZ = Double.NaN;

    private final AndroidScene3D scene3d = new AndroidScene3D();

    private GamaField elevationField;
    private double elevationEnvW, elevationEnvH, elevationScale;

    void beginFrame() {
        elevationField = null;
    }

    /**
     * Height of the display's elevation grid at world coordinates (x, y), computed the same
     * way the triangulated field mesh is drawn: bilinear interpolation of the field values
     * over the environment bounds, scaled by the mesh z scale. Returns 0 when no elevation
     * grid has been drawn this frame (agents then stay at their declared location).
     */
    private double terrainLift(double x, double y) {
        if (elevationField == null || !(elevationEnvW > 0) || !(elevationEnvH > 0)) return 0;
        int cols = elevationField.numCols, rows = elevationField.numRows;
        double[] data = elevationField.getMatrix();
        if (data == null || cols < 2 || rows < 2) return 0;
        double fx = x / elevationEnvW * (cols - 1);
        double fy = y / elevationEnvH * (rows - 1);
        if (fx < 0) fx = 0; else if (fx > cols - 1) fx = cols - 1;
        if (fy < 0) fy = 0; else if (fy > rows - 1) fy = rows - 1;
        int i0 = (int) fx, j0 = (int) fy;
        int i1 = Math.min(i0 + 1, cols - 1);
        int j1 = Math.min(j0 + 1, rows - 1);
        double ti = fx - i0, tj = fy - j0;
        double v00 = data[j0 * cols + i0];
        double v10 = data[j0 * cols + i1];
        double v01 = data[j1 * cols + i0];
        double v11 = data[j1 * cols + i1];
        if (Double.isNaN(v00) || Double.isNaN(v10) || Double.isNaN(v01) || Double.isNaN(v11)) return 0;
        return ((v00 * (1 - ti) + v10 * ti) * (1 - tj) + (v01 * (1 - ti) + v11 * ti) * tj) * elevationScale;
    }

    boolean is3dMode() {
        return data != null && data.is3D();
    }

    void rotateCamera3D(float dyawDeg, float dpitchDeg) {
        scene3d.rotateBy(dyawDeg, dpitchDeg);
    }

    void panCamera3D(float dxPx, float dyPx) {
        scene3d.panBy(dxPx, dyPx);
    }

    void resetCamera3D() {
        scene3d.resetRotation();
    }

    /** Zooms the 3D camera in/out (dolly). factor > 1 zooms in. */
    void zoomCamera3D(float factor) {
        scene3d.zoomBy(factor);
    }

    /** Re-runs the 3D auto-fit and resets the dolly zoom on the next render. */
    void resetCameraFit3D() {
        scene3d.resetFit();
        scene3d.resetZoom();
    }

    private static class CachedLayerImage {
        final Bitmap bitmap;
        final float x, y;
        final int w, h;
        final String layerName;
        CachedLayerImage(Bitmap b, float x, float y, int w, int h, String name) {
            this.bitmap = b; this.x = x; this.y = y; this.w = w; this.h = h; this.layerName = name;
        }
    }
    private final Map<String, CachedLayerImage> cachedImages = new HashMap<>();

    private final Map<String, Object> textureCache = new HashMap<>();

    public int getDrawnShapesCount() { return drawnShapesCount; }
    public int getScene3dSize() { return scene3d.size(); }
    public void resetDrawnShapesCount() { drawnShapesCount = 0; layerCount = 0; }

    public AndroidDisplayGraphics() {
        fillPaint.setStyle(Paint.Style.FILL);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(1f);
        textPaint.setTypeface(android.graphics.Typeface.create("Helvetica", android.graphics.Typeface.BOLD));
        textPaint.setTextSize(24f);
    }

    // Override ratio methods to use surface's getEnvWidth/getEnvHeight which read
    // the real simulation envelope, instead of data.getEnvWidth which may be a stale fallback.
    @Override
    public double getxRatioBetweenPixelsAndModelUnits() {
        double envW = getSurface() != null ? getSurface().getEnvWidth() : data.getEnvWidth();
        if (envW <= 0) return 1.0;
        if (currentLayer != null) {
            double layerW = currentLayer.getData().getSizeInPixels().x;
            if (layerW > 0) return layerW / envW;
        }
        return getDisplayWidth() / envW;
    }

    @Override
    public double getyRatioBetweenPixelsAndModelUnits() {
        double envH = getSurface() != null ? getSurface().getEnvHeight() : data.getEnvHeight();
        if (envH <= 0) return 1.0;
        if (currentLayer instanceof OverlayLayer) return getxRatioBetweenPixelsAndModelUnits();
        if (currentLayer != null) {
            double layerH = currentLayer.getData().getSizeInPixels().y;
            if (layerH > 0) return layerH / envH;
        }
        return getDisplayHeight() / envH;
    }

    public void setCanvas(Canvas c) { mainCanvas = c; this.canvas = c; }
    public Canvas getCanvas() { return canvas; }

    private int gamaColorToArgb(IColor c) {
        if (c == null) return 0xFF000000;
        int argb = IColor.toAWTColor(c).getRGB();
        if ((argb >>> 24) == 0) return 0xFF000000 | (argb & 0xFFFFFF);
        return argb;
    }

    private int awtColorToArgb(java.awt.Color c) {
        if (c == null) return 0xFF000000;
        return c.getRGB();
    }

    private int colorWithAlpha(IColor c, double alpha) {
        int argb = gamaColorToArgb(c);
        int colorA = (argb >>> 24) & 0xFF;
        int a = (int) (alpha * colorA);
        return (argb & 0x00FFFFFF) | (Math.min(255, Math.max(0, a)) << 24);
    }

    private float toPixelX(double modelX) { return (float) xFromModelUnitsToPixels(modelX); }
    private float toPixelY(double modelY) { return (float) yFromModelUnitsToPixels(modelY); }
    private float toPixelW(double modelW) { return (float) wFromModelUnitsToPixels(modelW); }
    private float toPixelH(double modelH) { return (float) hFromModelUnitsToPixels(modelH); }

    @Override
    public Rectangle2D drawShape(Geometry gg, IDrawingAttributes attributes) {
        if (gg == null || canvas == null) return null;
        if (is3dMode() && !(currentLayer instanceof OverlayLayer)) {
            drawnShapesCount++;
            drawShape3D(gg, attributes);
            return rect;
        }
        drawnShapesCount++;

        Geometry geometry = gg;

        if (geometry instanceof GeometryCollection && !(geometry instanceof MultiPolygon) && !(geometry instanceof MultiLineString)) {
            Rectangle2D.Double result = new Rectangle2D.Double();
            for (int i = 0; i < geometry.getNumGeometries(); i++) {
                Rectangle2D r = drawShape(geometry.getGeometryN(i), attributes);
                if (r != null) result.add(r);
            }
            return result;
        }

        boolean isLine = geometry instanceof Lineal || geometry instanceof Puntal;

        IColor border = isLine ? attributes.getColor() : attributes.getBorder();
        if (border == null && attributes.isEmpty()) border = attributes.getColor();
        if (highlight) {
            if (border != null) border = attributes.getColor();
        }

        IPoint loc = attributes.getLocation();
        double locDx = 0, locDy = 0;
        if (loc != null) {
            locDx = toPixelX(loc.getX()) - getXOffsetInPixels();
            locDy = toPixelY(loc.getY()) - getYOffsetInPixels();
        }

        workPath.reset();

        // Fast path for axis-aligned rectangles (grid cells): drawRect avoids
        // building a Path and is far cheaper than drawPath per cell.
        if (!isLine && geometry instanceof org.locationtech.jts.geom.Polygon
                && ((org.locationtech.jts.geom.Polygon) geometry).isRectangle()) {
            float left = toPixelX(geometry.getEnvelopeInternal().getMinX());
            float top = toPixelY(geometry.getEnvelopeInternal().getMaxY());
            float right = toPixelX(geometry.getEnvelopeInternal().getMaxX());
            float bottom = toPixelY(geometry.getEnvelopeInternal().getMinY());
            float fy = Math.min(top, bottom);
            rect.setRect(left + locDx, fy + locDy,
                    right - left, Math.abs(bottom - top));
            fastRect.set((float) (left + locDx), (float) (fy + locDy),
                    (float) (right + locDx),
                    (float) (fy + Math.abs(bottom - top) + locDy));
            if (!isLine && !attributes.isEmpty()) {
                fillPaint.setColor(colorWithAlpha(attributes.getColor(), currentAlpha));
                canvas.drawRect(fastRect, fillPaint);
            }
            if (border != null || attributes.isEmpty()) {
                strokePaint.setColor(colorWithAlpha(
                        border != null ? border : attributes.getColor(), currentAlpha));
                canvas.drawRect(fastRect, strokePaint);
            }
            return rect;
        }

        try {
            geometryToPath(geometry, workPath, locDx, locDy);

            float left = toPixelX(geometry.getEnvelopeInternal().getMinX());
            float top = toPixelY(geometry.getEnvelopeInternal().getMaxY());
            float right = toPixelX(geometry.getEnvelopeInternal().getMaxX());
            float bottom = toPixelY(geometry.getEnvelopeInternal().getMinY());
            float rw = right - left;
            float rh = Math.abs(bottom - top);
            rect.setRect(left + locDx, Math.min(top, bottom) + locDy, rw, rh);

            if (!isLine && !attributes.isEmpty()) {
                fillPaint.setColor(colorWithAlpha(attributes.getColor(), currentAlpha));
                canvas.drawPath(workPath, fillPaint);
            }
            if (isLine || border != null || attributes.isEmpty()) {
                strokePaint.setColor(colorWithAlpha(border != null ? border : attributes.getColor(), currentAlpha));
                canvas.drawPath(workPath, strokePaint);
            }
            return rect;
        } catch (Exception e) {
            return null;
        }
    }

    private void geometryToPath(Geometry geom, Path path, double locDx, double locDy) {
        if (geom instanceof LinearRing || "Polygon".equals(geom.getGeometryType())) {
            LinearRing shell = (geom instanceof LinearRing) ? (LinearRing) geom :
                    ((org.locationtech.jts.geom.Polygon) geom).getExteriorRing();
            coordsToPath(shell.getCoordinates(), path, true, locDx, locDy);
            if (geom instanceof org.locationtech.jts.geom.Polygon) {
                org.locationtech.jts.geom.Polygon poly = (org.locationtech.jts.geom.Polygon) geom;
                for (int i = 0; i < poly.getNumInteriorRing(); i++) {
                    coordsToPath(poly.getInteriorRingN(i).getCoordinates(), path, true, locDx, locDy);
                }
            }
        } else if ("MultiPolygon".equals(geom.getGeometryType())) {
            for (int i = 0; i < geom.getNumGeometries(); i++) {
                geometryToPath(geom.getGeometryN(i), path, locDx, locDy);
            }
        } else if ("MultiLineString".equals(geom.getGeometryType())) {
            for (int i = 0; i < geom.getNumGeometries(); i++) {
                geometryToPath(geom.getGeometryN(i), path, locDx, locDy);
            }
        } else if ("LineString".equals(geom.getGeometryType()) || "LinearRing".equals(geom.getGeometryType())) {
            coordsToPath(geom.getCoordinates(), path, false, locDx, locDy);
        } else if ("Point".equals(geom.getGeometryType())) {
            Coordinate c = geom.getCoordinate();
            path.addCircle(toPixelX(c.x) + (float) locDx, toPixelY(c.y) + (float) locDy, 3f, Path.Direction.CW);
        } else if (geom instanceof GeometryCollection) {
            for (int i = 0; i < geom.getNumGeometries(); i++) {
                geometryToPath(geom.getGeometryN(i), path, locDx, locDy);
            }
        } else {
            Coordinate[] coords = geom.getCoordinates();
            if (coords != null && coords.length > 0) {
                coordsToPath(coords, path, false, locDx, locDy);
            }
        }
    }

    private void coordsToPath(Coordinate[] coords, Path path, boolean close, double locDx, double locDy) {
        if (coords == null || coords.length == 0) return;
        path.moveTo(toPixelX(coords[0].x) + (float) locDx, toPixelY(coords[0].y) + (float) locDy);
        for (int i = 1; i < coords.length; i++) {
            path.lineTo(toPixelX(coords[i].x) + (float) locDx, toPixelY(coords[i].y) + (float) locDy);
        }
        if (close) path.close();
    }

    // ------------------------------------------------------------------
    // 3D rendering support. In 3D mode the geometry reaches this class
    // untransformed (ShapeDrawer leaves it alone when is2D() is false), so the
    // location/depth facets have to be applied here, mimicking the desktop
    // OpenGL GeometryDrawer.
    // ------------------------------------------------------------------

    private void drawShape3D(Geometry geometry, IDrawingAttributes attributes) {
        if (geometry == null) return;
        double[] center = bboxCenter3D(geometry);
        double k = modelScale(geometry, attributes.getSize());
        drawShape3DRec(geometry, attributes, center, k);
    }

    /** 3D bounding-box center of a geometry (z = 0 when a vertex has no z). */
    private double[] bboxCenter3D(Geometry geometry) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (Coordinate c : geometry.getCoordinates()) {
            double z = Double.isNaN(c.z) ? 0 : c.z;
            if (c.x < minX) minX = c.x;
            if (c.x > maxX) maxX = c.x;
            if (c.y < minY) minY = c.y;
            if (c.y > maxY) maxY = c.y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        if (minX > maxX) return new double[]{0, 0, 0};
        return new double[]{(minX + maxX) / 2, (minY + maxY) / 2, (minZ + maxZ) / 2};
    }

    /** Uniform model scale factor so the model's max bounding dimension matches the target size. */
    private double modelScale(Geometry geometry, Scaling3D size) {
        double k = 1;
        if (size == null) return k;
        double target = size.getX();
        if (size.getY() > target) target = size.getY();
        if (size.getZ() > target) target = size.getZ();
        if (target <= 0) return k;
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (Coordinate c : geometry.getCoordinates()) {
            double z = Double.isNaN(c.z) ? 0 : c.z;
            if (c.x < minX) minX = c.x;
            if (c.x > maxX) maxX = c.x;
            if (c.y < minY) minY = c.y;
            if (c.y > maxY) maxY = c.y;
            if (z < minZ) minZ = z;
            if (z > maxZ) maxZ = z;
        }
        if (minX > maxX) return k;
        double nat = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        if (nat > 0) k = target / nat;
        return k;
    }

    private void drawShape3DRec(Geometry geometry, IDrawingAttributes attributes, double[] center, double k) {
        if (geometry instanceof GeometryCollection gc && !(gc instanceof MultiLineString)) {
            for (int i = 0; i < gc.getNumGeometries(); i++) {
                drawShape3DRec(gc.getGeometryN(i), attributes, center, k);
            }
            return;
        }
        try {
            IPoint loc = attributes.getLocation();
            boolean isLine = geometry instanceof org.locationtech.jts.geom.Lineal
                    || geometry instanceof org.locationtech.jts.geom.Puntal;
            IColor color = attributes.getColor();
            // Line-like geometries carry their color in getColor() (fill); the border (stroke)
            // is what actually renders them. Mirror the 2D path so lines aren't stroked with
            // transparent black (which made tree trunk/branch lines invisible).
            IColor borderColor = isLine ? color : attributes.getBorder();
            if (borderColor == null && attributes.isEmpty()) borderColor = color;
            if (highlight && borderColor != null) borderColor = color;
            int fill = colorWithAlpha(color, currentAlpha);
            int border = borderColor != null ? colorWithAlpha(borderColor, currentAlpha) : 0;
            // Mirror the 2D path: an "empty" drawing (wireframe) has no fill, only its
            // border renders. Ignoring this filled the wireframe circle in the boids
            // goal aspect with the species color (black), which darkened the red goal
            // circle drawn underneath it.
            boolean wireframe = attributes.isEmpty();
            if (wireframe) fill = 0;
            Double depthD = attributes.getDepth();
            double depth = depthD != null ? depthD : 0.0;
            IShape.Type type = attributes.getType();

            List<?> texAttrs = attributes.getTextures();
            Object tex = texAttrs != null && !texAttrs.isEmpty() ? loadTexture(texAttrs, getSurface().getScope()) : null;
            int tint = ((int) (currentAlpha * 255) & 0xFF) << 24 | 0xFFFFFF;

            if (geometry instanceof Polygon poly) {
                Coordinate[] shell = poly.getExteriorRing().getCoordinates();
                if (shell == null || shell.length < 3) return;
                double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY;
                double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY;
                double minZ = Double.POSITIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
                for (Coordinate c : shell) {
                    if (c.x < minX) minX = c.x;
                    if (c.x > maxX) maxX = c.x;
                    if (c.y < minY) minY = c.y;
                    if (c.y > maxY) maxY = c.y;
                    double cz = Double.isNaN(c.z) ? 0 : c.z;
                    if (cz < minZ) minZ = cz;
                    if (cz > maxZ) maxZ = cz;
                }
                double w = maxX - minX;
                double h = maxY - minY;
                double cx = loc != null ? loc.getX() : (minX + maxX) / 2;
                double cy = loc != null ? loc.getY() : (minY + maxY) / 2;

                if (type == IShape.Type.CUBE || type == IShape.Type.BOX) {
                    double shellZ0 = minZ > maxZ ? 0 : minZ;
                    double lift = loc != null ? terrainLift(loc.getX(), loc.getY()) : terrainLift(cx, cy);
                    double cz;
                    if (loc != null) {
                        cz = (Double.isNaN(loc.getZ()) ? 0 : loc.getZ()) + lift;
                    } else {
                        // The geometry's own ring already carries the base z (the cell height).
                        // Use it instead of dropping the layer to z=0.
                        double d = depth > 0 ? depth : Math.max(w, h);
                        cz = shellZ0 + d / 2 + lift;
                    }
                    double d = depth > 0 ? depth : Math.max(w, h);
                    if (tex != null) {
                        Object wallTex = resolveTexture(texAttrs, 1, getSurface().getScope());
                        if (wallTex == null) wallTex = tex;
                        double bz0 = cz - d / 2, bz1 = cz + d / 2;
                        Coordinate[] boxShell = new Coordinate[] {
                            new Coordinate(cx - w / 2, cy - h / 2), new Coordinate(cx + w / 2, cy - h / 2),
                            new Coordinate(cx + w / 2, cy + h / 2), new Coordinate(cx - w / 2, cy + h / 2) };
                        addPrism3D(boxShell, bz0, bz1, 0, 0, bz0, fill, border, tex, wallTex, tint);
                    } else {
                        AxisAngle rot = attributes.getRotation();
                        if (rot != null) {
                            addRotatedBox(cx, cy, cz, w, h, d, rot, fill, border, 1f);
                        } else {
                            scene3d.addBox(cx, cy, cz, w, h, d, fill, border, 1f);
                        }
                    }
                } else if (depth > 0 && (type == IShape.Type.SPHERE || type == IShape.Type.CONE
                        || type == IShape.Type.PYRAMID)) {
                    double ox = 0, oy = 0;
                    if (loc != null) {
                        double lift = terrainLift(loc.getX(), loc.getY());
                        ox = loc.getX() - center[0];
                        oy = loc.getY() - center[1];
                    }
                    double shellZ0 = minZ > maxZ ? 0 : minZ;
                    double z0 = loc != null ? (Double.isNaN(loc.getZ()) ? 0 : loc.getZ()) : shellZ0;
                    z0 += terrainLift(cx, cy);
                    AxisAngle rot = attributes.getRotation();
                    Coordinate[] ts = transformShell(shell, center, k, rot);
                    if (type == IShape.Type.SPHERE) {
                        double tcx = 0, tcy = 0, fpR = 0;
                        for (Coordinate c : ts) { tcx += c.x; tcy += c.y; }
                        tcx /= ts.length; tcy /= ts.length;
                        for (Coordinate c : ts) {
                            double r = Math.hypot(c.x - tcx, c.y - tcy);
                            if (r > fpR) fpR = r;
                        }
                        double r = Math.max(fpR, depth / 2);
                        addSphereMesh(tcx + ox, tcy + oy, z0 + r, r, fill, border, tex, tint);
                    } else {
                        addTaperedMesh(ts, z0, z0 + depth, ox, oy, fill, border, tex, tint);
                    }
                } else {
                    // Compute the ring centroid for horizontal positioning. The bbox
                    // center drifts when GAML's rotated_by changes the shape, causing
                    // XY misalignment, while the centroid is invariant under
                    // rotation-about-centroid (which is what rotated_by uses).
                    int nRing = shell.length;
                    double centX = 0, centY = 0;
                    for (int ri = 0; ri < nRing; ri++) {
                        centX += shell[ri].x;
                        centY += shell[ri].y;
                    }
                    centX /= nRing; centY /= nRing;

                    double ox = 0, oy = 0, oz = 0;
                    if (loc != null) {
                        double lift = terrainLift(loc.getX(), loc.getY());
                        ox = loc.getX() - centX;
                        oy = loc.getY() - centY;
                        // Vertical: anchor the model's bounding-box center (the pivot
                        // transformVertex scales/rotates about) at the location's Z.
                        // Using the vertex average here launches asymmetric 3D models
                        // (e.g. an OBJ boat) skyward, since bbox center Z != mean Z.
                        oz = (Double.isNaN(loc.getZ()) ? 0 : loc.getZ()) + lift - center[2];
                    }
                    AxisAngle rot = attributes.getRotation();
                    if (depth > 0) {
                        double shellZ0 = minZ > maxZ ? 0 : minZ;
                        double z0 = loc != null ? (Double.isNaN(loc.getZ()) ? 0 : loc.getZ()) : shellZ0;
                        z0 += terrainLift(cx, cy);
                        Coordinate[] ts = transformShell(shell, center, k, rot);
                        addPrism3D(ts, z0, z0 + depth, ox, oy, oz, fill, border, tex, tex, tint);
                        double surf = z0 + depth;
                        if (Double.isNaN(worldSurfaceZ) || surf > worldSurfaceZ) worldSurfaceZ = surf;
                    } else {
                        float[] model = new float[shell.length * 3];
                        for (int i = 0; i < shell.length; i++) {
                            double vz = Double.isNaN(shell[i].z) ? 0 : shell[i].z;
                            double[] p = transformVertex(shell[i].x, shell[i].y, vz, center, k, rot);
                            model[i * 3] = (float) (p[0] + ox);
                            model[i * 3 + 1] = (float) (p[1] + oy);
                            model[i * 3 + 2] = (float) (p[2] + oz);
                        }
                        if (tex != null) {
                            scene3d.addTexturedPoly(model, shell.length, envelopeUvs(model, shell.length), tex, tint, 0, 0);
                        } else {
                            scene3d.addPoly(model, shell.length, fill, border, 1f, false);
                        }
                    }
                }
            } else if (geometry instanceof LineString ls) {
                addLine3D(ls, border, 1f);
            } else if (geometry instanceof MultiLineString mls) {
                for (int i = 0; i < mls.getNumGeometries(); i++) {
                    addLine3D((LineString) mls.getGeometryN(i), border, 1f);
                }
            } else if (geometry instanceof LinearRing ring) {
                addLine3D(ring, border, 1f);
            } else if (geometry instanceof Point pt) {
                Coordinate c = pt.getCoordinate();
                double z = loc != null ? (Double.isNaN(loc.getZ()) ? 0 : loc.getZ())
                        : (Double.isNaN(c.z) ? 0 : c.z);
                double cxPt = loc != null ? loc.getX() : c.x;
                double cyPt = loc != null ? loc.getY() : c.y;
                scene3d.addBox(c.x, c.y, z + terrainLift(cxPt, cyPt), 1, 1, 1, fill, border, 1f);
            }
        } catch (Exception e) {
            android.util.Log.w("ANDROID_3D", "drawShape3D: " + e);
        }
    }

    /** Scale a vertex about the geometry center, then rotate it about the center. */
    private double[] transformVertex(double x, double y, double z, double[] center, double k,
            AxisAngle rot) {
        double rx = (x - center[0]) * k;
        double ry = (y - center[1]) * k;
        double rz = (z - center[2]) * k;
        if (rot != null) {
            double[] p = rotatePoint(rx, ry, rz, 0, 0, 0, rot);
            rx = p[0];
            ry = p[1];
            rz = p[2];
        }
        return new double[] { center[0] + rx, center[1] + ry, center[2] + rz };
    }

    /**
     * Rotate a point about an origin using the same quaternion GAMA builds in
     * Rotation3D(AxisAngle). The half-angle is positive, so the effective rotation
     * is +angle around the (normalized) axis. This matches desktop GAMA's net
     * world rotation for sprite/geometry headings: desktop applies
     * gl.rotateBy(-angle) on Y-negated vertices, whose combined effect is R(+angle)
     * in world coordinates. Verified empirically: `draw img rotate: heading` must
     * orient the sprite along (cos heading, sin heading) so it points at the model's
     * own arrow line. (Previously the negated half-angle mirrored the rotation and
     * made sprites turn the opposite way to their heading arrows.)
     */
    private static double[] rotatePoint(double px, double py, double pz,
            double cx, double cy, double cz, AxisAngle rot) {
        double rx = px - cx, ry = py - cy, rz = pz - cz;
        IPoint axis = rot.getAxis();
        double ax = axis.getX(), ay = axis.getY(), az = axis.getZ();
        double norm = Math.sqrt(ax * ax + ay * ay + az * az);
        if (norm == 0) {
            ax = 0;
            ay = 0;
            az = 1;
            norm = 1;
        }
        double a = 0.5 * Math.toRadians(rot.getAngle());
        double q0 = Math.cos(a);
        double s = Math.sin(a) / norm;
        double q1 = s * ax, q2 = s * ay, q3 = s * az;
        double t = q1 * rx + q2 * ry + q3 * rz;
        double nx = 2 * (q0 * (q0 * rx + q2 * rz - q3 * ry) + q1 * t) - rx;
        double ny = 2 * (q0 * (q0 * ry + q3 * rx - q1 * rz) + q2 * t) - ry;
        double nz = 2 * (q0 * (q0 * rz + q1 * ry - q2 * rx) + q3 * t) - rz;
        return new double[] { nx + cx, ny + cy, nz + cz };
    }

    /** Axis-aligned box rotated about its center by the given AxisAngle (degrees). */
    private void addRotatedBox(double cx, double cy, double cz, double w, double h, double d,
                               AxisAngle rot, int fill, int border, float stroke) {
        double hw = w / 2, hh = h / 2, hd = d / 2;
        double[] corners = new double[8 * 3];
        int idx = 0;
        for (double sx : new double[]{-hw, hw}) {
            for (double sy : new double[]{-hh, hh}) {
                for (double sz : new double[]{-hd, hd}) {
                    double[] p = rotatePoint(sx, sy, sz, 0, 0, 0, rot);
                    corners[idx++] = cx + p[0];
                    corners[idx++] = cy + p[1];
                    corners[idx++] = cz + p[2];
                }
            }
        }
        int i000 = 0, i100 = 1, i110 = 3, i010 = 2, i001 = 4, i101 = 5, i111 = 7, i011 = 6;
        addBoxFace(corners, i001, i101, i111, i011, fill, border, stroke, true);   // +z
        addBoxFace(corners, i010, i110, i100, i000, fill, border, stroke, true);   // -z
        addBoxFace(corners, i100, i110, i111, i101, fill, border, stroke, true);   // +x
        addBoxFace(corners, i001, i011, i010, i000, fill, border, stroke, true);   // -x
        addBoxFace(corners, i110, i010, i011, i111, fill, border, stroke, true);   // +y
        addBoxFace(corners, i000, i100, i101, i001, fill, border, stroke, true);   // -y
    }

    private void addBoxFace(double[] c, int a, int b, int cc, int d,
                            int fill, int border, float stroke, boolean cull) {
        float[] model = new float[]{
                (float) c[a * 3], (float) c[a * 3 + 1], (float) c[a * 3 + 2],
                (float) c[b * 3], (float) c[b * 3 + 1], (float) c[b * 3 + 2],
                (float) c[cc * 3], (float) c[cc * 3 + 1], (float) c[cc * 3 + 2],
                (float) c[d * 3], (float) c[d * 3 + 1], (float) c[d * 3 + 2]
        };
        scene3d.addPoly(model, 4, fill, border, stroke, cull);
    }

    private Coordinate[] transformShell(Coordinate[] shell, double[] center, double k,
            AxisAngle rot) {
        Coordinate[] out = new Coordinate[shell.length];
        for (int i = 0; i < shell.length; i++) {
            double vz = Double.isNaN(shell[i].z) ? 0 : shell[i].z;
            double[] p = transformVertex(shell[i].x, shell[i].y, vz, center, k, rot);
            out[i] = new Coordinate(p[0], p[1], p[2]);
        }
        return out;
    }

    private void addPrism3D(Coordinate[] shell, double z0, double z1, double ox, double oy, int fill, int border) {
        addPrism3D(shell, z0, z1, ox, oy, 0, fill, border, null, null, 0);
    }

    /** Draws an extruded polygon prism. `topTex` is applied to the top/bottom faces,
     *  `wallTex` to the sides (desktop GAMA: primary vs alternate texture). Either may
     *  be  null, in which case the corresponding face(s) fall back to the fill color.
     *  For rotated shapes the extrusion direction follows the polygon's local normal
     *  (computed from the first three vertices) instead of the global Z axis. */
    private void addPrism3D(Coordinate[] shell, double z0, double z1, double ox, double oy, double oz,
                            int fill, int border, Object topTex, Object wallTex, int tint) {
        int n = shell.length;
        float depth = (float) (z1 - z0);

        // Sanitize Z values (2D Coordinate shells may have NaN z) and build
        // bottom vertices, simultaneously collecting the first three for normal
        // computation.
        float[] bottom = new float[n * 3];
        float[] top = new float[n * 3];
        for (int i = 0; i < n; i++) {
            float sz = Double.isNaN(shell[i].z) ? 0 : (float) shell[i].z;
            bottom[i * 3] = (float) (shell[i].x + ox);
            bottom[i * 3 + 1] = (float) (shell[i].y + oy);
            bottom[i * 3 + 2] = sz + (float) oz;
        }

        // Compute the polygon normal from the first three bottom vertices.
        // For non-rotated shapes this is (0,0,1) so extrusion is along global Z;
        // for rotated shapes it follows the local face direction.
        float nx = 0, ny = 0, nz = 1;
        if (n >= 3) {
            float ax = bottom[3] - bottom[0];
            float ay = bottom[4] - bottom[1];
            float az = bottom[5] - bottom[2];
            float bx = bottom[6] - bottom[0];
            float by = bottom[7] - bottom[1];
            float bz = bottom[8] - bottom[2];
            nx = ay * bz - az * by;
            ny = az * bx - ax * bz;
            nz = ax * by - ay * bx;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len > 1e-6f) { nx /= len; ny /= len; nz /= len; }
            else { nx = 0; ny = 0; nz = 1; }
        }

        for (int i = 0; i < n; i++) {
            top[i * 3] = bottom[i * 3] + nx * depth;
            top[i * 3 + 1] = bottom[i * 3 + 1] + ny * depth;
            top[i * 3 + 2] = bottom[i * 3 + 2] + nz * depth;
        }
        if (topTex != null) {
            scene3d.addTexturedPoly(top, n, envelopeUvs(top, n), topTex, tint, 0, 0);
        } else {
            scene3d.addPoly(top, n, fill, border, 1f, false);
        }
        if (wallTex != null) {
            scene3d.addTexturedPoly(bottom, n, envelopeUvs(bottom, n), wallTex, tint, 0, 0);
        } else {
            scene3d.addPoly(bottom, n, fill, border, 1f, false);
        }
        for (int i = 0; i < n - 1; i++) {
            float[] w = wall(bottom, top, i);
            if (wallTex != null) {
                scene3d.addTexturedPoly(w, 4, wallUvs(bottom, top, i), wallTex, tint, 0, 0);
            } else {
                scene3d.addPoly(w, 4, fill, border, 1f, false);
            }
        }
    }

    /**
     * UVs for a vertical side wall of a prism. Instead of mapping both axes over
     * the wall's XY footprint (which collapses the vertical axis for a near-vertical
     * face, garbling the texture when seen from the side), U runs across the wall's
     * horizontal run and V runs up its actual vertical extent (bottom->top).
     */
    private float[] wallUvs(float[] bottom, float[] top, int i) {
        float[] uv = new float[8];
        float bx = bottom[i * 3] - bottom[(i + 1) * 3];
        float by = bottom[i * 3 + 1] - bottom[(i + 1) * 3 + 1];
        float run = (float) Math.sqrt(bx * bx + by * by);
        if (run <= 0) run = 1f;
        uv[0] = 0f; uv[1] = 0f;   // bottom i
        uv[2] = 1f; uv[3] = 0f;   // bottom i+1
        uv[4] = 1f; uv[5] = 1f;   // top i+1
        uv[6] = 0f; uv[7] = 1f;   // top i
        return uv;
    }

    private float[] wall(float[] bottom, float[] top, int i) {
        float[] wall = new float[12];
        wall[0] = bottom[i * 3]; wall[1] = bottom[i * 3 + 1]; wall[2] = bottom[i * 3 + 2];
        wall[3] = bottom[(i + 1) * 3]; wall[4] = bottom[(i + 1) * 3 + 1]; wall[5] = bottom[(i + 1) * 3 + 2];
        wall[6] = top[(i + 1) * 3]; wall[7] = top[(i + 1) * 3 + 1]; wall[8] = top[(i + 1) * 3 + 2];
        wall[9] = top[i * 3]; wall[10] = top[i * 3 + 1]; wall[11] = top[i * 3 + 2];
        return wall;
    }

    /** Per-vertex u,v over a polygon's own envelope, mirroring the desktop OpenGL texture mapping. */
    private float[] envelopeUvs(float[] model, int n) {
        float minX = Float.POSITIVE_INFINITY, maxX = Float.NEGATIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY;
        for (int i = 0; i < n; i++) {
            float x = model[i * 3], y = model[i * 3 + 1];
            if (x < minX) minX = x;
            if (x > maxX) maxX = x;
            if (y < minY) minY = y;
            if (y > maxY) maxY = y;
        }
        float w = maxX - minX, h = maxY - minY;
        float[] uv = new float[n * 2];
        for (int i = 0; i < n; i++) {
            float x = model[i * 3], y = model[i * 3 + 1];
            uv[i * 2] = w > 0 ? 1f - (x - minX) / w : 0f;
            uv[i * 2 + 1] = h > 0 ? (y - minY) / h : 0f;
        }
        return uv;
    }

    private static void setV(float[] m, int i, double x, double y, double z) {
        m[i * 3] = (float) x;
        m[i * 3 + 1] = (float) y;
        m[i * 3 + 2] = (float) z;
    }

    /** Lat/long UV-sphere centered at (cx, cy, cz) with radius r. */
    private void addSphereMesh(double cx, double cy, double cz, double r, int fill, int border, Object tex, int tint) {
        int rings = 16, segs = 24;
        float[] model = new float[12];
        float[] uv = new float[8];
        for (int i = 0; i < rings; i++) {
            double p0 = Math.PI * i / rings, p1 = Math.PI * (i + 1) / rings;
            double s0 = Math.sin(p0), c0 = Math.cos(p0), s1 = Math.sin(p1), c1 = Math.cos(p1);
            for (int j = 0; j < segs; j++) {
                double t0 = 2 * Math.PI * j / segs, t1 = 2 * Math.PI * (j + 1) / segs;
                double ct0 = Math.cos(t0), st0 = Math.sin(t0), ct1 = Math.cos(t1), st1 = Math.sin(t1);
                setV(model, 0, cx + r * s0 * ct0, cy + r * s0 * st0, cz + r * c0);
                setV(model, 1, cx + r * s0 * ct1, cy + r * s0 * st1, cz + r * c0);
                setV(model, 2, cx + r * s1 * ct1, cy + r * s1 * st1, cz + r * c1);
                setV(model, 3, cx + r * s1 * ct0, cy + r * s1 * st0, cz + r * c1);
                if (tex != null) {
                    float u0 = (float) j / segs, u1 = (float) (j + 1) / segs;
                    float v0 = (float) i / rings, v1 = (float) (i + 1) / rings;
                    uv[0] = u0; uv[1] = 1f - v1;
                    uv[2] = u1; uv[3] = 1f - v1;
                    uv[4] = u1; uv[5] = 1f - v0;
                    uv[6] = u0; uv[7] = 1f - v0;
                    scene3d.addTexturedPoly(model, 4, uv, tex, tint, 0, 0);
                } else {
                    scene3d.addPoly(model, 4, fill, border, 1f, false);
                }
            }
        }
    }

    /** Cone or square pyramid: base polygon (from shell) at z0, apex at the shell centroid at z1. */
    private void addTaperedMesh(Coordinate[] base, double z0, double z1, double ox, double oy,
                                int fill, int border, Object tex, int tint) {
        int n = base.length;
        float[] bm = new float[n * 3];
        double acx = 0, acy = 0;
        for (int i = 0; i < n; i++) {
            bm[i * 3] = (float) (base[i].x + ox);
            bm[i * 3 + 1] = (float) (base[i].y + oy);
            bm[i * 3 + 2] = (float) z0;
            acx += base[i].x;
            acy += base[i].y;
        }
        acx = acx / n + ox;
        acy = acy / n + oy;
        if (tex != null) {
            scene3d.addTexturedPoly(bm, n, envelopeUvs(bm, n), tex, tint, 0, 0);
        } else {
            scene3d.addPoly(bm, n, fill, border, 1f, false);
        }
        float[] tri = new float[9];
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            tri[0] = bm[i * 3]; tri[1] = bm[i * 3 + 1]; tri[2] = bm[i * 3 + 2];
            tri[3] = bm[j * 3]; tri[4] = bm[j * 3 + 1]; tri[5] = bm[j * 3 + 2];
            tri[6] = (float) acx; tri[7] = (float) acy; tri[8] = (float) z1;
            if (tex != null) {
                scene3d.addTexturedPoly(tri, 3, envelopeUvs(tri, 3), tex, tint, 0, 0);
            } else {
                scene3d.addPoly(tri, 3, fill, border, 1f, false);
            }
        }
    }

    private void addLine3D(LineString ls, int color, float stroke) {
        Coordinate[] cs = ls.getCoordinates();
        if (cs.length < 2) return;
        float zBias = 0.1f;
        for (int i = 0; i < cs.length - 1; i++) {
            float[] m = new float[6];
            m[0] = (float) cs[i].x;
            m[1] = (float) cs[i].y;
            m[2] = (float) ((Double.isNaN(cs[i].z) ? 0 : cs[i].z) + terrainLift(cs[i].x, cs[i].y)) + zBias;
            m[3] = (float) cs[i + 1].x;
            m[4] = (float) cs[i + 1].y;
            m[5] = (float) ((Double.isNaN(cs[i + 1].z) ? 0 : cs[i + 1].z) + terrainLift(cs[i + 1].x, cs[i + 1].y)) + zBias;
            scene3d.addLine(m, color, stroke);
        }
    }

    @Override
    public Rectangle2D drawImage(BufferedImage img, IDrawingAttributes attributes) {
        if (img == null || canvas == null) {
            return null;
        }
        if (is3dMode() && !(currentLayer instanceof OverlayLayer)) {
            return drawImage3D(img, attributes);
        }

        float curX, curY;
        if (attributes.getLocation() == null) {
            curX = (float) getXOffsetInPixels();
            curY = (float) getYOffsetInPixels();
        } else {
            curX = toPixelX(attributes.getLocation().getX());
            curY = toPixelY(attributes.getLocation().getY());
        }

        int curWidth, curHeight;
        if (attributes.getSize() == null) {
            curWidth = getLayerWidth();
            curHeight = getLayerHeight();
        } else {
            curWidth = (int) toPixelW(attributes.getSize().getX());
            curHeight = (int) toPixelH(attributes.getSize().getY());
        }

        boolean fullWorld = attributes.getSize() == null && attributes.getLocation() == null;
        Bitmap bitmap = bufferedImageToBitmap(img, !fullWorld);
        if (bitmap == null) return null;

        if (currentLayer != null) {
            String layerName = currentLayer.getName();
            cachedImages.put(layerName, new CachedLayerImage(bitmap, curX, curY, curWidth, curHeight, layerName));
        }

        canvas.save();
        Double angle = attributes.getAngle();
        if (angle != null) {
            float centerX = curX + curWidth / 2f;
            float centerY = curY + curHeight / 2f;
            canvas.rotate(angle.floatValue(), centerX, centerY);
        }
        canvas.drawBitmap(bitmap, null, new RectF(curX, curY, curX + curWidth, curY + curHeight), bitmapPaint);
        canvas.restore();
        drawnShapesCount++;

        rect.setRect(curX, curY, curWidth, curHeight);
        return rect;
    }

    /** Renders a 2D image as a billboard in the 3D scene. */
    private Rectangle2D drawImage3D(BufferedImage img, IDrawingAttributes attributes) {
        if (img == null) return null;
        boolean dynamicImage = attributes.getSize() == null && attributes.getLocation() == null;
        Bitmap bitmap = bufferedImageToBitmap(img, !dynamicImage);
        if (bitmap == null) return null;

        IScope scope = getSurface().getScope();
        IPoint loc = attributes.getLocation();
        double x = loc != null ? loc.getX() : 0;
        double y = loc != null ? loc.getY() : 0;
        double z = (loc != null && !Double.isNaN(loc.getZ())) ? loc.getZ() : 0.5;
        z += terrainLift(x, y);

        Scaling3D size = attributes.getSize();
        double w = size != null ? size.getX() : 1.0;
        double h = size != null ? size.getY() : 1.0;
        if (w <= 0) w = 1.0;
        if (h <= 0) h = 1.0;

        // A 3D image with no location/size (e.g. the GridLayer's cell image) covers
        // the whole environment, like ImageLayer in desktop GAMA.
        // Use the FROZEN world envelope — the live sim.getEnvelope() grows every step as agents
        // forage outward, which panned & re-zoomed the grid/ground continuously.
        if (size == null && loc == null) {
            try {
                // Use the FROZEN world envelope — the live sim.getEnvelope() grows every step as
                // agents forage outward, which panned & re-zoomed the grid/ground continuously.
                com.gama.nativeapp.display.AndroidDisplaySurface _s =
                        getSurface() instanceof com.gama.nativeapp.display.AndroidDisplaySurface
                                ? (com.gama.nativeapp.display.AndroidDisplaySurface) getSurface() : null;
                IEnvelope fenv = _s != null ? _s.getFrozenEnvelope() : null;
                if (fenv != null && fenv.getWidth() > 0 && fenv.getHeight() > 0) {
                    x = fenv.getMinX() + fenv.getWidth() / 2;
                    y = fenv.getMinY() + fenv.getHeight() / 2;
                    w = fenv.getWidth();
                    h = fenv.getHeight();
                }
            } catch (Throwable t) {}
        }

        int tint = ((int) (currentAlpha * 255) & 0xFF) << 24 | 0xFFFFFF;

        boolean fullWorld = false;
        try {
            com.gama.nativeapp.display.AndroidDisplaySurface _s =
                    getSurface() instanceof com.gama.nativeapp.display.AndroidDisplaySurface
                            ? (com.gama.nativeapp.display.AndroidDisplaySurface) getSurface() : null;
            IEnvelope fenv = _s != null ? _s.getFrozenEnvelope() : null;
            if (fenv != null) {
                fullWorld = w >= fenv.getWidth() * 0.9 && h >= fenv.getHeight() * 0.9;
            }
        } catch (Throwable t) {}

        if (fullWorld) {
            scene3d.addTexturedPoly(new float[]{
                    (float) (x - w / 2), (float) (y - h / 2), (float) z,
                    (float) (x + w / 2), (float) (y - h / 2), (float) z,
                    (float) (x + w / 2), (float) (y + h / 2), (float) z,
                    (float) (x - w / 2), (float) (y + h / 2), (float) z
            }, 4, new float[]{0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f}, bitmap, tint, 0, 0f);
            scene3d.markLastPrimBackground();
        } else {
            // Agent sprite: a flat quad lying in the XY (ground) plane at height z,
            // rotated by the agent's heading (DrawingAttributes.getRotation()) around
            // its Z axis. GAMA's default 3D camera looks straight down, so sprites must
            // lie in the ground plane to be visible and to match desktop GAMA's
            // orientation (rotate: heading turns them in the XY plane).
            AxisAngle rot = attributes.getRotation();
            double hw = w / 2, hh = h / 2;
            double[] center = {x, y, z};
            double[][] corners = {
                    {x - hw, y - hh, z}, {x + hw, y - hh, z},
                    {x + hw, y + hh, z}, {x - hw, y + hh, z}
            };
            float[] model = new float[12];
            for (int i = 0; i < 4; i++) {
                double[] p = transformVertex(corners[i][0], corners[i][1], corners[i][2], center, 1.0, rot);
                model[i * 3] = (float) p[0];
                model[i * 3 + 1] = (float) p[1];
                model[i * 3 + 2] = (float) p[2];
            }
            scene3d.addTexturedPoly(model, 4, new float[]{0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f}, bitmap, tint, 0, 0f);
        }

        drawnShapesCount++;
        rect.setRect(0, 0, 0, 0);
        return rect;
    }

    public static Bitmap bufferedImageToBitmap(BufferedImage img) {
        return bufferedImageToBitmap(img, true);
    }

    /** Converts a BufferedImage to a Bitmap... */
    public static Bitmap bufferedImageToBitmap(BufferedImage img, boolean cache) {
        if (img == null) return null;
        if (img.getWidth() <= 0 || img.getHeight() <= 0) return null;
        Bitmap cached = cache ? IMAGE_TO_BITMAP.get(img) : null;
        if (cached != null) return cached;
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) return null;
        if (!cache) {
            if (img.isGraphicsDrawn()) {
                // Image was rendered by a CanvasGraphics2D (e.g. a JFreeChart chart) onto the
                // androidBitmap; the int[] data[] is stale. Mirror bitmap -> data so the bulk
                // getRGB below reads the chart, and never write the stale data[] back over it.
                img.syncBitmapToData();
            } else {
                // Image is data[]-backed (e.g. grid cell buffers written via the raster).
                // Sync data[] -> androidBitmap too, so getRGB() and any other consumers agree.
                syncRasterDataToBitmap(img);
            }
        }
        int[] pixels = new int[w * h];
        if (!cache && !img.isGraphicsDrawn()) {
            // Read the authoritative raster data[] directly. Going through androidBitmap first
            // is lossy: ARGB_8888 premultiplies, so alpha==0 pixels (as produced by our
            // Color.getRGB(), which drops the alpha byte) are flattened to 0x00000000 and their
            // RGB is unrecoverable — which is what made the grid texture fully transparent.
            readRasterData(img, pixels);
        } else {
            img.getRGB(0, 0, w, h, pixels, 0, w);
        }
        int type = img.getType();

        if (type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_ARGB_PRE) {
            // Pixels usually carry real alpha from getRGB(). But images backed by Color.getRGB()
            // (e.g. grid cell buffers) store opaque colors as 0x00RRGGBB with a dropped alpha byte;
            // recover those as opaque so they are not rendered fully transparent.
            for (int i = 0; i < pixels.length; i++) {
                if (((pixels[i] >>> 24) & 0xFF) == 0 && pixels[i] != 0) {
                    pixels[i] = pixels[i] | 0xFF000000;
                }
            }
        } else if (type == BufferedImage.TYPE_INT_RGB || type == BufferedImage.TYPE_3BYTE_BGR) {
            int tl = pixels[0];
            int tr = pixels[w - 1];
            int bl = pixels[(h - 1) * w];
            int br = pixels[(h - 1) * w + w - 1];
            int bg = (tl == tr && tl == bl) ? tl : (tl == tr) ? tl : (bl == br) ? bl : -1;
            if (bg != -1) {
                for (int i = 0; i < pixels.length; i++) {
                    if (pixels[i] == bg) {
                        pixels[i] = 0;
                    } else {
                        pixels[i] = pixels[i] | 0xFF000000;
                    }
                }
            } else {
                for (int i = 0; i < pixels.length; i++) {
                    pixels[i] = pixels[i] | 0xFF000000;
                }
            }
        } else if (img.getColorModel().hasAlpha()) {
            for (int i = 0; i < pixels.length; i++) {
                int a = (pixels[i] >> 24) & 0xFF;
                if (a == 0) {
                    pixels[i] = 0;
                }
            }
        } else {
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = pixels[i] | 0xFF000000;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        if (cache) {
            if (IMAGE_TO_BITMAP.size() > 256) IMAGE_TO_BITMAP.clear();
            IMAGE_TO_BITMAP.put(img, bmp);
        }
        return bmp;
    }

    private static void readRasterData(java.awt.image.BufferedImage img, int[] out) {
        try {
            java.awt.image.DataBuffer db = img.getRaster().getDataBuffer();
            if (db instanceof java.awt.image.DataBufferInt) {
                int[] buf = ((java.awt.image.DataBufferInt) db).getData();
                if (buf != null && buf.length > 0) {
                    System.arraycopy(buf, 0, out, 0, Math.min(buf.length, out.length));
                    return;
                }
            }
        } catch (Exception ignored) {
        }
        img.getRGB(0, 0, img.getWidth(), img.getHeight(), out, 0, img.getWidth());
    }

    private static void syncRasterDataToBitmap(java.awt.image.BufferedImage img) {
        try {
            java.awt.image.DataBuffer db = img.getRaster().getDataBuffer();
            if (db instanceof java.awt.image.DataBufferInt) {
                int[] buf = ((java.awt.image.DataBufferInt) db).getData();
                boolean hasContent = false;
                for (int i = 0; i < buf.length; i++) {
                    if (buf[i] != 0) { hasContent = true; break; }
                }
                if (hasContent) {
                    img.syncDataToBitmap();
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static final java.util.IdentityHashMap<BufferedImage, Bitmap> IMAGE_TO_BITMAP =
            new java.util.IdentityHashMap<>();

    /** Converts a BufferedImage to a texture bitmap without any background keying. */
    private static Bitmap textureToBitmap(BufferedImage img) {
        if (img == null) return null;
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) return null;
        int[] pixels = new int[w * h];
        img.getRGB(0, 0, w, h, pixels, 0, w);
        boolean hasAlpha = img.getColorModel() != null && img.getColorModel().hasAlpha();
        for (int i = 0; i < pixels.length; i++) {
            if (!hasAlpha) {
                pixels[i] = pixels[i] | 0xFF000000;
            } else if (((pixels[i] >>> 24) & 0xFF) == 0 && pixels[i] != 0) {
                pixels[i] = pixels[i] | 0xFF000000;
            }
        }
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        bmp.setPixels(pixels, 0, w, 0, 0, w, h);
        return bmp;
    }

    /** Resolves the first element of a draw attributes texture list to a cached Bitmap or AnimatedTexture. */
    private Object loadTexture(List<?> textures, IScope scope) {
        return resolveTexture(textures, 0, scope);
    }

    /** Resolves the element at `index` of a draw attributes texture list to a cached Bitmap or AnimatedTexture.
     *  Elements can be image providers (e.g. GamaImageFile), BufferedImages or plain String paths
     *  (as produced by `file(...).path` in GAML), mirroring what desktop GAMA accepts. */
    private Object resolveTexture(List<?> textures, int index, IScope scope) {
        if (textures == null || index < 0 || index >= textures.size()) return null;
        try {
            Object first = textures.get(index);
            BufferedImage bi = null;
            String key = null;
            if (first instanceof IImageProvider ip) {
                if (first instanceof GamaImageFile gf) {
                    key = gf.getPath(scope);
                } else {
                    key = ip.getClass().getName();
                }
                if (key != null && key.toLowerCase().endsWith(".gif")) {
                    Object cachedGif = textureCache.get(key);
                    if (cachedGif != null) return cachedGif;
                    AndroidScene3D.AnimatedTexture at = decodeAnimatedGif(key);
                    if (at != null) {
                        textureCache.put(key, at);
                        return at;
                    }
                }
                bi = ip.getImage(scope, true);
            } else if (first instanceof String s) {
                key = s;
                if (key.toLowerCase().endsWith(".gif")) {
                    Object cachedGif = textureCache.get(key);
                    if (cachedGif != null) return cachedGif;
                    AndroidScene3D.AnimatedTexture at = decodeAnimatedGif(key);
                    if (at != null) {
                        textureCache.put(key, at);
                        return at;
                    }
                }
                bi = gama.extension.image.ImageCache.getInstance().getImageFromFile(scope, s, true, null, null);
                if (bi == null) {
                    android.util.Log.w("ANDROID_3D", "loadTexture: no image for path " + s);
                    return null;
                }
            } else if (first instanceof BufferedImage b) {
                bi = b;
                key = "bi@" + System.identityHashCode(b);
            } else {
                return null;
            }
            if (bi == null || key == null) return null;
            Object cached = textureCache.get(key);
            if (cached != null) return cached;
            Bitmap bmp = textureToBitmap(bi);
            if (bmp != null) textureCache.put(key, bmp);
            return bmp;
        } catch (Throwable t) {
            android.util.Log.w("ANDROID_3D", "loadTexture failed: " + t);
            return null;
        }
    }

    /**
     * Decodes an animated GIF into its frames using android.graphics.Movie. The
     * per-frame delays are parsed from the GIF blocks so the animation plays at
     * its native pace. Returns null when the file is not an animated GIF.
     */
    static AndroidScene3D.AnimatedTexture decodeAnimatedGif(String path) {
        try {
            android.graphics.Movie movie;
            try (java.io.FileInputStream fis = new java.io.FileInputStream(path)) {
                movie = android.graphics.Movie.decodeStream(fis);
            }
            if (movie == null) return null;
            int w = movie.width(), h = movie.height();
            if (w <= 0 || h <= 0) return null;

            java.util.List<Integer> delays = new java.util.ArrayList<>();
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(path, "r")) {
                byte[] hdr = new byte[6];
                raf.readFully(hdr);
                String magic = new String(hdr, "US-ASCII");
                if (!"GIF87a".equals(magic) && !"GIF89a".equals(magic)) return null;
                raf.readUnsignedShort();
                raf.readUnsignedShort();
                int packed = raf.readUnsignedByte();
                raf.readUnsignedByte();
                raf.readUnsignedByte();
                if ((packed & 0x80) != 0) {
                    raf.skipBytes(3 * (1 << ((packed & 0x07) + 1)));
                }
                int pendingDelay = 10;
                boolean hasFrame = false;
                while (true) {
                    int block = raf.read();
                    if (block == -1 || block == 0x3B) break;
                    if (block == 0x21) {
                        int label = raf.read();
                        if (label == 0xF9) {
                            raf.readUnsignedByte();
                            raf.readUnsignedByte();
                            int delayCs = raf.readUnsignedByte() | (raf.readUnsignedByte() << 8);
                            raf.readUnsignedByte();
                            raf.readUnsignedByte();
                            pendingDelay = Math.max(1, delayCs) * 10;
                        } else {
                            int sz;
                            while ((sz = raf.read()) > 0) raf.skipBytes(sz);
                        }
                    } else if (block == 0x2C) {
                        raf.readUnsignedShort();
                        raf.readUnsignedShort();
                        raf.readUnsignedShort();
                        raf.readUnsignedShort();
                        int lp = raf.readUnsignedByte();
                        if ((lp & 0x80) != 0) {
                            raf.skipBytes(3 * (1 << ((lp & 0x07) + 1)));
                        }
                        raf.readUnsignedByte();
                        int sz;
                        while ((sz = raf.read()) > 0) raf.skipBytes(sz);
                        delays.add(pendingDelay);
                        hasFrame = true;
                    }
                }
                if (!hasFrame) return null;
            }

            int n = delays.size();
            Bitmap[] frames = new Bitmap[n];
            int t = 0;
            for (int i = 0; i < n; i++) {
                Bitmap f = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas fc = new Canvas(f);
                movie.setTime(t);
                movie.draw(fc, 0, 0);
                frames[i] = f;
                t += Math.max(1, delays.get(i));
            }
            int[] delayArr = new int[n];
            for (int i = 0; i < n; i++) delayArr[i] = delays.get(i);
            return new AndroidScene3D.AnimatedTexture(frames, delayArr);
        } catch (Throwable t) {
            android.util.Log.w("ANDROID_3D", "decodeAnimatedGif failed for " + path + ": " + t);
            return null;
        }
    }

    /** Loads and caches the texture image referenced by an OBJ material map, resolved next to the MTL file. */
    private Bitmap loadObjTexture(IScope scope, GamaObjFile file, String mapName) {
        try {
            String mtl = file.mtlPath;
            if (mtl == null || mapName == null) return null;
            int idx = mtl.lastIndexOf('/');
            String base = idx >= 0 ? mtl.substring(0, idx + 1) : mtl;
            String path = base + mapName;
            Object cached = textureCache.get(path);
            if (cached instanceof Bitmap) return (Bitmap) cached;
            BufferedImage bi = gama.extension.image.ImageCache.getInstance().getImageFromFile(scope, path, true, null, null);
            if (bi == null) return null;
            Bitmap bmp = textureToBitmap(bi);
            if (bmp != null) textureCache.put(path, bmp);
            return bmp;
        } catch (Throwable t) {
            android.util.Log.w("ANDROID_3D", "loadObjTexture failed for " + mapName + ": " + t);
            return null;
        }
    }

    @Override
    public Rectangle2D drawString(String string, IDrawingAttributes attributes) {
        if (string == null || canvas == null) return null;
        if (is3dMode() && !(currentLayer instanceof OverlayLayer)) {
            return drawString3D(string, attributes);
        }

        if (string.contains("\n")) {
            Rectangle2D.Double result = new Rectangle2D.Double();
            for (String s : string.split("\n")) {
                Rectangle2D r = drawString(s, attributes);
                if (r != null) {
                    attributes.getLocation().setY(attributes.getLocation().getY() + r.getHeight());
                    result.add(r);
                }
            }
            return result;
        }

        textPaint.setColor(highlight ? gamaColorToArgb(data.getHighlightColor()) : gamaColorToArgb(attributes.getColor()));

        float curX, curY;
        if (attributes.getLocation() == null) {
            curX = (float) getXOffsetInPixels();
            curY = (float) getYOffsetInPixels();
        } else {
            curX = toPixelX(attributes.getLocation().getX());
            curY = toPixelY(attributes.getLocation().getY());
        }

        if (attributes.getFont() != null) {
            textPaint.setTextSize(attributes.getFont().getSize());
        }

        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textWidth = textPaint.measureText(string);
        float textHeight = fm.descent - fm.ascent;

        curX -= textWidth * attributes.getAnchor().getX();
        curY += (textHeight - fm.descent) * attributes.getAnchor().getY();

        canvas.save();
        if (attributes.getAngle() != null) {
            canvas.rotate(attributes.getAngle().floatValue(),
                    curX + textWidth / 2, curY + textHeight / 2);
        }
        canvas.drawText(string, curX, curY - fm.ascent, textPaint);
        canvas.restore();

        rect.setRect(curX, curY - textHeight, textWidth, textHeight);
        return rect;
    }

    private Rectangle2D drawString3D(String string, IDrawingAttributes attributes) {
        IPoint loc = attributes.getLocation();
        double x = loc != null ? loc.getX() : 0;
        double y = loc != null ? loc.getY() : 0;
        double z = loc != null && !Double.isNaN(loc.getZ()) ? loc.getZ() : 0;
        float size = attributes.getFont() != null ? attributes.getFont().getSize() : 24f;
        int color = highlight ? gamaColorToArgb(data.getHighlightColor()) : gamaColorToArgb(attributes.getColor());
        scene3d.addText(x, y, z, string, color, size,
                attributes.getAnchor() != null ? (float) attributes.getAnchor().getX() : 0.5f,
                attributes.getAnchor() != null ? (float) attributes.getAnchor().getY() : 0.5f);
        rect.setRect(x, y, 0, 0);
        return rect;
    }

    @Override
    public Rectangle2D drawChart(BufferedImage chart) {
        if (chart == null || canvas == null) return null;
        try {
            drawImage(chart, new DrawingAttributes(null, null, null, null, null, null));
        } catch (Throwable t) {
            android.util.Log.e("ANDROID_DRAW", "drawChart error: " + t.getClass().getSimpleName() + ": " + t.getMessage());
            StackTraceElement[] stack = t.getStackTrace();
            int limit = Math.min(stack.length, 15);
            for (int i = 0; i < limit; i++) {
                android.util.Log.e("ANDROID_DRAW", "  at " + stack[i].toString());
            }
        }
        drawnShapesCount++;
        return rect;
    }

    @Override
    public Rectangle2D drawAsset(IAsset file, IDrawingAttributes attributes) {
        IScope scope = getSurface().getScope();
        if (file instanceof IImageProvider im) {
            java.awt.image.BufferedImage bi = im.getImage(scope, attributes.useCache());
            if (bi == null) return null;
            return drawImage(bi, attributes);
        }
        if (file instanceof GamaObjFile obj && is3dMode() && !(currentLayer instanceof OverlayLayer)) {
            return drawObj3D(scope, obj, attributes);
        }
        if (!(file instanceof GamaGeometryFile)) return null;
        gama.api.types.geometry.IShape shape = ((GamaGeometryFile) file).getGeometry(scope);
        if (shape == null) return null;
        IPoint loc = attributes.getLocation() != null ? attributes.getLocation() : shape.getLocation();
        return drawShape(shape.getInnerGeometry(), new ShapeDrawingAttributes(
                attributes.getSize(), attributes.getDepth(), attributes.getRotation(),
                loc, attributes.isEmpty(), attributes.getColor(), attributes.getBorder(),
                null, attributes.getAgentIdentifier(), null, attributes.getLineWidth(), null));
    }

    /**
     * Renders a GamaObjFile face by face in 3D, mirroring the desktop ObjFileDrawer:
     * per-face vertex/UV data from the file, material colours and textures resolved
     * next to the MTL file. The placement/scale/rotation transform is identical to
     * the generic geometry path so the object stays put.
     */
    /** Rotate a vertex in OBJ coordinates (Y-up) by initRotation. */
    private double[] rotateVertexOBJ(double[] v, AxisAngle rot) {
        if (rot == null || rot.getAngle() == 0.0) return v;
        double a = Math.toRadians(rot.getAngle());
        IPoint axis = rot.getAxis();
        double ux = axis.getX(), uy = axis.getY(), uz = axis.getZ();
        double x = v[0], y = v[1], z = v[2];
        double c = Math.cos(a), s = Math.sin(a), t = 1 - c;
        double rx = (c + ux * ux * t) * x + (ux * uy * t - uz * s) * y + (ux * uz * t + uy * s) * z;
        double ry = (uy * ux * t + uz * s) * x + (c + uy * uy * t) * y + (uy * uz * t - ux * s) * z;
        double rz = (uz * ux * t - uy * s) * x + (uz * uy * t + ux * s) * y + (c + uz * uz * t) * z;
        return new double[] { rx, ry, rz };
    }

    private static final java.util.HashMap<String, gama.api.types.geometry.IShape> OBJ_GEOM_CACHE =
            new java.util.HashMap<>();

    private Rectangle2D drawObj3D(IScope scope, GamaObjFile file, IDrawingAttributes attributes) {
        try {
            try {
                file.loadObject(scope, true);
            } catch (Throwable lte) {
                android.util.Log.w("ANDROID_3D", "OBJ3D loadObject threw: " + lte);
            }
            gama.api.types.geometry.IShape shape;
            try {
                String key = file.getFile(scope).getAbsolutePath();
                shape = OBJ_GEOM_CACHE.get(key);
                if (shape == null) {
                    shape = file.getGeometry(scope);
                    if (shape != null) OBJ_GEOM_CACHE.put(key, shape);
                }
            } catch (Throwable ct) {
                android.util.Log.w("ANDROID_3D", "OBJ3D asGeometry threw: " + ct);
                shape = null;
            }
            if (shape == null || file.faces.isEmpty()) return null;
            Geometry geom = shape.getInnerGeometry();
            double[] center = bboxCenter3D(geom);
            double k = modelScale(geom, attributes.getSize());
            IPoint loc = attributes.getLocation() != null ? attributes.getLocation() : shape.getLocation();
            AxisAngle rot = attributes.getRotation();
            AxisAngle fileInitRot = file.getInitRotation();
            int tint = ((int) (currentAlpha * 255) & 0xFF) << 24 | 0xFFFFFF;

            // Pre-pass over the model vertices using the same transform as the per-face
            // loop below, to find the model's vertical extent (minus the at-Z offset).
            // After the file's init-rotation the model's Z centre is NOT bboxCenter3D's z
            // (the rotation shifts it), so anchoring on center[2] leaves the boat hovering
            // above the surface. Anchor on the real transformed Z centre instead.
            double mzMin = Double.POSITIVE_INFINITY, mzMax = Double.NEGATIVE_INFINITY;
            if (file.setOfVertex != null) {
                for (double[] c0 : file.setOfVertex) {
                    double cx = c0[0], cy = c0[1], cz = c0[2];
                    if (fileInitRot != null && fileInitRot.getAngle() != 0.0) {
                        double[] r = rotateVertexOBJ(c0, fileInitRot);
                        cx = r[0]; cy = r[1]; cz = r[2];
                    }
                    double pz = rotatePoint((cx - center[0]) * k, (cy + center[1]) * k, (center[2] - cz) * k, 0, 0, 0, rot)[2];
                    double z = pz;
                    if (z < mzMin) mzMin = z;
                    if (z > mzMax) mzMax = z;
                }
            }
            double zCenter = (mzMin + mzMax) / 2;

            double ox = 0, oy = 0, oz = 0;
            if (loc != null) {
                double lift = terrainLift(loc.getX(), loc.getY());
                ox = loc.getX() - center[0];
                oy = loc.getY() - center[1];
                double az = Double.isNaN(loc.getZ()) ? 0 : loc.getZ();
                if (az == 0 && !Double.isNaN(worldSurfaceZ)) {
                    oz = worldSurfaceZ - zCenter;
                } else {
                    oz = az + lift - zCenter;
                }
            }

            int nmat = file.matTimings.size();
            int matIndex = 0;
            int nextMatStart = -1;
            String matName = null;
            if (file.materials != null && nmat > 0) {
                matName = file.matTimings.get(0)[0];
                nextMatStart = Integer.parseInt(file.matTimings.get(0)[1]);
            }
            Bitmap matTex = null;
            int matColor = 0xFFC0C0C0;
            if (file.materials != null && matName != null) {
                float[] kd = file.materials.getKd(matName);
                if (kd != null) {
                    matColor = 0xFF000000 | ((int) Math.round(kd[0] * 255) << 16)
                            | ((int) Math.round(kd[1] * 255) << 8) | (int) Math.round(kd[2] * 255);
                }
                String map = file.materials.getMapKd(matName);
                if (map == null) map = file.materials.getMapKa(matName);
                if (map == null) map = file.materials.getMapd(matName);
                matTex = map != null ? loadObjTexture(scope, file, map) : null;
            }

            for (int i = 0; i < file.faces.size(); i++) {
                if (file.materials != null && matIndex < nmat && i == nextMatStart) {
                    matName = file.matTimings.get(matIndex)[0];
                    float[] kd = file.materials.getKd(matName);
                    if (kd != null) {
                        matColor = 0xFF000000 | ((int) Math.round(kd[0] * 255) << 16)
                                | ((int) Math.round(kd[1] * 255) << 8) | (int) Math.round(kd[2] * 255);
                    }
                    String map = file.materials.getMapKd(matName);
                    if (map == null) map = file.materials.getMapKa(matName);
                    if (map == null) map = file.materials.getMapd(matName);
                    matTex = map != null ? loadObjTexture(scope, file, map) : null;
                    matIndex++;
                    if (matIndex < nmat) {
                        nextMatStart = Integer.parseInt(file.matTimings.get(matIndex)[1]);
                    }
                }

                int[] fv = file.faces.get(i);
                int[] ft = file.facesTexs.get(i);
                int n = fv.length;
                if (n < 3) continue;
                float[] model = new float[n * 3];
                float[] uv = new float[n * 2];
                boolean hasUv = matTex != null;
                for (int w = 0; w < n; w++) {
                    double[] c = file.setOfVertex.get(fv[w] - 1);
                    // Apply file's initRotation in OBJ coordinates (Y-up) before coordinate conversion
                    if (fileInitRot != null && fileInitRot.getAngle() != 0.0) {
                        c = rotateVertexOBJ(c, fileInitRot);
                    }
                    // Convert to geometry coordinates (Y-down) and flip 180° around X into the
                    // ground-plane frame. The flip is applied BEFORE the heading rotation: a
                    // reflection does not commute with a rotation, so flipping after rotating
                    // mirrored the ant and made it turn opposite to the heading. The rotation
                    // below uses the same rotatePoint as the verified sprite path.
                    double fx = (c[0] - center[0]) * k;
                    double fy = (c[1] + center[1]) * k;
                    double fz = (center[2] - c[2]) * k;
                    double[] p = rotatePoint(fx, fy, fz, 0, 0, 0, rot);
                    model[w * 3] = (float) (center[0] + p[0] + ox);
                    model[w * 3 + 1] = (float) (-center[1] + p[1] + oy);
                    model[w * 3 + 2] = (float) (-center[2] + p[2] + oz);
                    if (ft[w] > 0 && ft[w] - 1 < file.setOfVertexTextures.size()) {
                        double[] tc = file.setOfVertexTextures.get(ft[w] - 1);
                        double v = tc[1];
                        if (v >= 0 && v <= 1) v = 1 - v; else v = Math.abs(v);
                        uv[w * 2] = (float) tc[0];
                        uv[w * 2 + 1] = (float) v;
                    } else {
                        hasUv = false;
                    }
                }
                if (matTex != null && hasUv) {
                    scene3d.addTexturedPoly(model, n, uv, matTex, tint, 0, 0);
                } else {
                    scene3d.addPoly(model, n, matColor, 0, 0, false);
                }
            }
            drawnShapesCount++;
        } catch (Throwable t) {
            android.util.Log.w("ANDROID_3D", "drawObj3D: " + t);
        }
        rect.setRect(0, 0, 0, 0);
        return rect;
    }

    @Override
    public Rectangle2D drawField(IField fieldValues, IDrawingAttributes attributes) {
        if (is3dMode() && !(currentLayer instanceof OverlayLayer)) {
            drawField3D(fieldValues, attributes);
            drawnShapesCount++;
            return rect;
        }
        List<?> textures = attributes.getTextures();
        if (textures != null) {
            Object image = textures.get(0);
            if (image instanceof IImageProvider im) return drawAsset(im, attributes);
            if (image instanceof BufferedImage bi) return drawImage(bi, attributes);
        }
        if (!(fieldValues instanceof GamaField gf)) return null;
        GamaField flatten = (GamaField) gf.flatten(getSurface().getScope(), attributes.getColorProvider());
        attributes.setSize(null);
        return drawImage(flatten.getImage(getSurface().getScope(), false), attributes);
    }

    /**
     * Builds a triangulated 3D surface from the field values, mimicking the desktop
     * OpenGL MeshDrawer: the field is smoothed first (when the model asks for it),
     * the mesh spans the environment with each vertex lifted to z = value * scale,
     * vertices whose value is below "above" are masked out, and each cell is split
     * into two triangles on the same diagonal as the desktop renderer. The mesh is
     * sampled down so the software renderer stays fast.
     */
    private void drawField3D(IField fieldValues, IDrawingAttributes attributes) {
        if (!(fieldValues instanceof GamaField gf)) return;
        int cols = gf.numCols, rows = gf.numRows;
        if (cols < 2 || rows < 2) return;
        double[] data = gf.getMatrix();
        if (data == null || data.length < cols * rows) return;
        IScope scope = getSurface().getScope();
        double noData = gf.getNoData(scope);
        double[] zz = smoothMesh(cols, rows, data, noData, attributes.getSmooth());
        double[] minMax = meshMinMax(zz, noData);
        double min = minMax[0], max = minMax[1];
        boolean wireframe = attributes.isEmpty();
        IMeshColorProvider provider = attributes.getColorProvider();
        boolean triangulated = attributes.isTriangulated();
        double zScale = attributes.getScale() != null ? attributes.getScale() : 1.0;
        double above = attributes.getAbove();
        boolean checkAbove = above != MeshLayerData.ABOVE;

        double envW = getSurface().getData().getEnvWidth();
        double envH = getSurface().getData().getEnvHeight();
        if (!(envW > 0) || !(envH > 0)) return;

        elevationField = gf;
        elevationEnvW = envW;
        elevationEnvH = envH;
        elevationScale = zScale;

        int maxDim = 120;
        int nx = Math.min(maxDim, cols);
        int ny = Math.min(maxDim, rows);

        List<?> texAttrs = attributes.getTextures();
        boolean grayscaled = attributes.isGrayscaled();
        Object tex = (!grayscaled && texAttrs != null && !texAttrs.isEmpty()) ? loadTexture(texAttrs, getSurface().getScope()) : null;
        if (provider == null && !wireframe && triangulated && tex == null) return;
        int tint = ((int) (currentAlpha * 255) & 0xFF) << 24 | 0xFFFFFF;

        double[] zc = new double[(nx + 1) * (ny + 1)];
        int[] vc = new int[(nx + 1) * (ny + 1)];
        double[] rgb = new double[4];
        for (int j = 0; j <= ny; j++) {
            double fj = j * (rows - 1) / (double) ny;
            int j0 = (int) fj;
            int j1 = Math.min(j0 + 1, rows - 1);
            double tj = fj - j0;
            for (int i = 0; i <= nx; i++) {
                double fi = i * (cols - 1) / (double) nx;
                int i0 = (int) fi;
                int i1 = Math.min(i0 + 1, cols - 1);
                double ti = fi - i0;
                double v00 = zz[j0 * cols + i0];
                double v10 = zz[j0 * cols + i1];
                double v01 = zz[j1 * cols + i0];
                double v11 = zz[j1 * cols + i1];
                double v = (v00 * (1 - ti) + v10 * ti) * (1 - tj) + (v01 * (1 - ti) + v11 * ti) * tj;
                if (v == noData || Double.isNaN(v)) v = 0;
                int idx = j * (nx + 1) + i;
                zc[idx] = v;
                if (provider != null) {
                    vc[idx] = vertexMeshColor(provider, Math.min(j, rows - 1) * cols + Math.min(i, cols - 1),
                            v, min, max, rgb, checkAbove, above);
                }
            }
        }

        if (wireframe) {
            int borderColor = attributes.getBorder() != null ? colorToARGB(attributes.getBorder(), 0xFF000000) : 0xFF000000;
            boolean withText = attributes.isWithText();
            float[] seg = new float[6];
            for (int j = 0; j <= ny; j++) {
                float y = (float) (j * envH / ny);
                for (int i = 0; i < nx; i++) {
                    int i0 = j * (nx + 1) + i, i1 = i0 + 1;
                    float x0 = (float) (i * envW / nx);
                    float x1 = (float) ((i + 1) * envW / nx);
                    seg[0] = x0; seg[1] = y; seg[2] = (float) (zc[i0] * zScale);
                    seg[3] = x1; seg[4] = y; seg[5] = (float) (zc[i1] * zScale);
                    scene3d.addLine(seg, borderColor, 1f);
                }
            }
            for (int i = 0; i <= nx; i++) {
                float x = (float) (i * envW / nx);
                for (int j = 0; j < ny; j++) {
                    int i0 = j * (nx + 1) + i, i1 = i0 + (nx + 1);
                    float y0 = (float) (j * envH / ny);
                    float y1 = (float) ((j + 1) * envH / ny);
                    seg[0] = x; seg[1] = y0; seg[2] = (float) (zc[i0] * zScale);
                    seg[3] = x; seg[4] = y1; seg[5] = (float) (zc[i1] * zScale);
                    scene3d.addLine(seg, borderColor, 1f);
                }
            }
            if (withText) {
                double cellW = envW / nx, cellH = envH / ny;
                for (int j = 0; j < ny; j++) {
                    for (int i = 0; i < nx; i++) {
                        int i00 = j * (nx + 1) + i;
                        double val = data[Math.min(j, rows - 1) * cols + Math.min(i, cols - 1)];
                        String label = String.format("%.1f", val);
                        float cx = (float) ((i + 0.5) * cellW);
                        float cy = (float) ((j + 0.5) * cellH);
                        float cz = (float) (zc[i00] * zScale) + 1f;
                        scene3d.addText(cx, cy, cz, label, 0xFF000000, Math.max(8f, (float) Math.min(cellW, cellH) * 0.3f), 0f, 0f);
                    }
                }
            }
            return;
        }

        if (!triangulated) {
            if (!(max > min)) {
                min = 0;
            }
            double cellW = envW / nx, cellH = envH / ny;
            int borderColor = attributes.getBorder() != null ? colorToARGB(attributes.getBorder(), 0) : 0;
            for (int j = 0; j < ny; j++) {
                float y0 = (float) (j * cellH), y1 = (float) ((j + 1) * cellH);
                for (int i = 0; i < nx; i++) {
                    int i00 = j * (nx + 1) + i;
                    float x0 = (float) (i * cellW), x1 = (float) ((i + 1) * cellW);
                    double zTop = zc[i00] * zScale;
                    double depth = Math.abs(zTop) > 0.001 ? Math.abs(zTop) : Math.max(cellW, cellH) * 0.1;
                    double bz0 = zTop > 0 ? 0 : zTop;
                    double bz1 = zTop > 0 ? zTop : 0;
                    if (tex != null) {
                        float u0 = (float) i / nx, u1 = (float) (i + 1) / nx;
                        float v0 = (float) j / ny, v1 = (float) (j + 1) / ny;
                        float[] quad = new float[] {
                            x0, y0, (float) bz1, x1, y0, (float) bz1,
                            x1, y1, (float) bz1, x0, y1, (float) bz1
                        };
                        float[] uv = new float[] { u0, v0, u1, v0, u1, v1, u0, v1 };
                        scene3d.addTexturedPoly(quad, 4, uv, tex, tint, borderColor, 1f);
                    } else {
                        double cz = zTop > 0 ? zTop / 2 : depth / 2;
                        int fill = vc[i00];
                        scene3d.addBox(x0 + cellW / 2, y0 + cellH / 2, cz, cellW, cellH, depth, fill, borderColor, 1f);
                    }
                }
            }
            return;
        }
        if (!(max > min) && tex == null) return;
        float[] tri = new float[9];
        float[] uv = new float[6];
        for (int j = 0; j < ny; j++) {
            float y0 = (float) (j * envH / ny);
            float y1 = (float) ((j + 1) * envH / ny);
            float v0 = (float) j / ny, v1 = (float) (j + 1) / ny;
            for (int i = 0; i < nx; i++) {
                int i00 = j * (nx + 1) + i, i10 = i00 + 1, i01 = i00 + (nx + 1), i11 = i01 + 1;
                float x0 = (float) (i * envW / nx);
                float x1 = (float) ((i + 1) * envW / nx);
                float u0 = (float) i / nx, u1 = (float) (i + 1) / nx;
                if (tex != null) {
                    tri[0] = x0; tri[1] = y0; tri[2] = (float) (zc[i00] * zScale);
                    tri[3] = x1; tri[4] = y0; tri[5] = (float) (zc[i10] * zScale);
                    tri[6] = x0; tri[7] = y1; tri[8] = (float) (zc[i01] * zScale);
                    uv[0] = u0; uv[1] = v0; uv[2] = u1; uv[3] = v0; uv[4] = u0; uv[5] = v1;
                    scene3d.addTexturedPoly(tri, 3, uv, tex, tint, 0, 1f);
                    tri[0] = x1; tri[1] = y0; tri[2] = (float) (zc[i10] * zScale);
                    tri[3] = x1; tri[4] = y1; tri[5] = (float) (zc[i11] * zScale);
                    tri[6] = x0; tri[7] = y1; tri[8] = (float) (zc[i01] * zScale);
                    uv[0] = u1; uv[1] = v0; uv[2] = u1; uv[3] = v1; uv[4] = u0; uv[5] = v1;
                    scene3d.addTexturedPoly(tri, 3, uv, tex, tint, 0, 1f);
                } else {
                    emitMeshTriangle(tri, x0, y0, zc[i00], vc[i00], x1, y0, zc[i10], vc[i10], x0, y1, zc[i01], vc[i01], zScale);
                    emitMeshTriangle(tri, x1, y0, zc[i10], vc[i10], x1, y1, zc[i11], vc[i11], x0, y1, zc[i01], vc[i01], zScale);
                }
            }
        }
    }

    private void emitMeshTriangle(float[] tri, float x0, float y0, double z0, int c0,
                                  float x1, float y1, double z1, int c1,
                                  float x2, float y2, double z2, int c2, double zScale) {
        int a0 = (c0 >>> 24) & 0xFF, a1 = (c1 >>> 24) & 0xFF, a2 = (c2 >>> 24) & 0xFF;
        int aAvg = (a0 + a1 + a2) / 3;
        if (aAvg < 4) return;
        tri[0] = x0; tri[1] = y0; tri[2] = (float) (z0 * zScale);
        tri[3] = x1; tri[4] = y1; tri[5] = (float) (z1 * zScale);
        tri[6] = x2; tri[7] = y2; tri[8] = (float) (z2 * zScale);
        int r = (((c0 >>> 16) & 0xFF) + ((c1 >>> 16) & 0xFF) + ((c2 >>> 16) & 0xFF)) / 3;
        int g = (((c0 >>> 8) & 0xFF) + ((c1 >>> 8) & 0xFF) + ((c2 >>> 8) & 0xFF)) / 3;
        int b = ((c0 & 0xFF) + (c1 & 0xFF) + (c2 & 0xFF)) / 3;
        scene3d.addPoly(tri, 3, (aAvg << 24) | (r << 16) | (g << 8) | b, 0, 1f, false);
    }

    private int vertexMeshColor(IMeshColorProvider provider, int idx, double z, double min, double max,
                                double[] rgb, boolean checkAbove, double above) {
        if (checkAbove && z < above) return 0x00000000;
        double[] c = provider.getColor(idx, z, min, max, rgb);
        int a = (int) (Math.max(0, Math.min(1, c[3])) * currentAlpha * 255);
        int r = (int) (Math.max(0, Math.min(1, c[0])) * 255);
        int g = (int) (Math.max(0, Math.min(1, c[1])) * 255);
        int b = (int) (Math.max(0, Math.min(1, c[2])) * 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private double[] meshMinMax(double[] data, double noData) {
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (double v : data) {
            if (v != noData) {
                if (v < mn) mn = v;
                if (v > mx) mx = v;
            }
        }
        return new double[]{mn, mx};
    }

    /** Smooths the field data like the desktop MeshDrawer (Gaussian box blurs or 3x3 convolution). */
    private double[] smoothMesh(int cols, int rows, double[] data, double noData, int smooth) {
        if (smooth <= 0) return data;
        if (noData == IField.NO_NO_DATA) return gaussianSmooth(cols, rows, data, smooth);
        return convolutionSmooth(cols, rows, data, noData, smooth);
    }

    private static double[] gaussianSmooth(int cols, int rows, double[] data, int passes) {
        double[] result = data.clone();
        int nbBoxes = 3;
        double wIdeal = Math.sqrt(12.0 * passes * passes / nbBoxes + 1);
        double wl = Math.floor(wIdeal);
        if (wl % 2 == 0) wl--;
        double wu = wl + 2;
        double mIdeal = (12.0 * passes * passes - nbBoxes * wl * wl - 4 * nbBoxes * wl - 3 * nbBoxes) / (-4 * wl - 4);
        long m = Math.round(mIdeal);
        double[] sizes = new double[nbBoxes];
        for (int k = 0; k < nbBoxes; k++) sizes[k] = k < m ? wl : wu;
        for (int k = 0; k < nbBoxes; k++) {
            int r = (int) Math.round((sizes[k] - 1) / 2);
            if (r <= cols / 2 && r <= rows / 2) {
                boxBlurAcrossColumns(result, cols, rows, r);
                boxBlurAcrossRows(result, cols, rows, r);
            }
        }
        return result;
    }

    private static void boxBlurAcrossColumns(double[] scl, int cols, int rows, int r) {
        double iarr = 1d / (r + r + 1);
        for (int i = 0; i < rows; i++) {
            int ti = i * cols, li = ti, ri = ti + r;
            double fv = scl[ti], lv = scl[ti + cols - 1];
            double val = (r + 1) * fv;
            for (int j = 0; j < r; j++) val += scl[ti + j];
            for (int j = 0; j <= r; j++) { val += scl[ri++] - fv; scl[ti++] = val * iarr; }
            for (int j = r + 1; j < cols - r; j++) { val += scl[ri++] - scl[li++]; scl[ti++] = val * iarr; }
            for (int j = cols - r; j < cols; j++) { val += lv - scl[li++]; scl[ti++] = val * iarr; }
        }
    }

    private static void boxBlurAcrossRows(double[] scl, int cols, int rows, int r) {
        double iarr = 1d / (r + r + 1);
        for (int i = 0; i < cols; i++) {
            int ti = i, li = ti, ri = ti + r * cols;
            double fv = scl[ti], lv = scl[ti + cols * (rows - 1)];
            double val = (r + 1) * fv;
            for (int j = 0; j < r; j++) val += scl[ti + j * rows];
            for (int j = 0; j <= r; j++) { val += scl[ri] - fv; scl[ti] = val * iarr; ri += cols; ti += cols; }
            for (int j = r + 1; j < rows - r; j++) { val += scl[ri] - scl[li]; scl[ti] = val * iarr; li += cols; ri += cols; ti += cols; }
            for (int j = rows - r; j < rows; j++) { val += lv - scl[li]; scl[ti] = val * iarr; li += cols; ti += cols; }
        }
    }

    private static double[] convolutionSmooth(int cols, int rows, double[] data, double noData, int passes) {
        double[] input = data;
        double[] output = new double[data.length];
        for (int p = 0; p < passes; p++) {
            for (int y = 0; y < rows; y++) {
                for (int x = 0; x < cols; x++) {
                    double z00 = cell(cols, rows, input, x - 1, y - 1);
                    double z02 = cell(cols, rows, input, x + 1, y - 1);
                    double z03 = cell(cols, rows, input, x - 1, y);
                    double z = cell(cols, rows, input, x, y);
                    double z05 = cell(cols, rows, input, x + 1, y);
                    double z06 = cell(cols, rows, input, x - 1, y + 1);
                    double z07 = cell(cols, rows, input, x, y + 1);
                    double z08 = cell(cols, rows, input, x + 1, y + 1);
                    if (z00 == noData || z02 == noData || z03 == noData || z == noData || z05 == noData
                            || z06 == noData || z07 == noData || z08 == noData) continue;
                    output[x + y * cols] = (z00 + z00 + z02 + z03 + z + z05 + z06 + z07 + z08) / 9d;
                }
            }
            input = output;
        }
        return output;
    }

    private static double cell(int cols, int rows, double[] data, int x0, int y0) {
        int x = x0 < 0 ? 0 : x0 > cols - 1 ? cols - 1 : x0;
        int y = y0 < 0 ? 0 : y0 > rows - 1 ? rows - 1 : y0;
        return data[y * cols + x];
    }

    @Override
    public void fillBackground(IColor bgColor) {
        if (canvas == null) return;
        setAlpha(1);
        int argb = gamaColorToArgb(bgColor);
        bgPaint.setColor(argb);
        bgPaint.setStyle(Paint.Style.FILL);
        canvas.drawRect(0, 0, (float) getSurface().getDisplayWidth(),
                (float) getSurface().getDisplayHeight(), bgPaint);
    }

    @Override
    public void setAlpha(double alpha) {
        super.setAlpha(alpha);
        this.currentAlpha = (float) alpha;
        int a = (int) (alpha * 255);
        fillPaint.setAlpha(a);
        strokePaint.setAlpha(a);
        textPaint.setAlpha(a);
        bitmapPaint.setAlpha(a);
    }

    @Override
    public boolean beginDrawingLayers() {
        drawnShapesCount = 0;
        layerCount = 0;
        if (is3dMode()) scene3d.beginFrame();
        return true;
    }

    @Override
    public void endDrawingLayers() {
        if (is3dMode()) {
            renderScene3D();
            blitOverlay();
        }
    }

    private void blitOverlay() {
        if (overlayBitmap == null || mainCanvas == null) return;
        if (getSurface() instanceof AndroidDisplaySurface) {
            AndroidDisplaySurface s = (AndroidDisplaySurface) getSurface();
            mainCanvas.drawBitmap(overlayBitmap, s.getViewPortLeft(), s.getViewPortTop(), null);
        } else {
            mainCanvas.drawBitmap(overlayBitmap, 0, 0, null);
        }
    }

    private static int colorToARGB(Object color, int fallbackARGB) {
        if (color == null) return fallbackARGB;
        try {
            Object awt = null;
            try {
                awt = color.getClass().getMethod("getAWTColor").invoke(color);
            } catch (NoSuchMethodException e) {
                try {
                    awt = color.getClass().getMethod("internalColor").invoke(color);
                } catch (NoSuchMethodException e2) {
                    awt = null;
                }
            }
            if (awt instanceof java.awt.Color) {
                java.awt.Color c = (java.awt.Color) awt;
                return (c.getAlpha() << 24) | (c.getRed() << 16) | (c.getGreen() << 8) | c.getBlue();
            }
        } catch (Throwable t) { /* fall back to direct accessors */ }
        try {
            int r = ((Number) color.getClass().getMethod("getRed").invoke(color)).intValue();
            int g = ((Number) color.getClass().getMethod("getGreen").invoke(color)).intValue();
            int b = ((Number) color.getClass().getMethod("getBlue").invoke(color)).intValue();
            int a = 255;
            try {
                a = ((Number) color.getClass().getMethod("getAlpha").invoke(color)).intValue();
            } catch (Throwable t) { /* keep 255 */ }
            return (a << 24) | (r << 16) | (g << 8) | b;
        } catch (Throwable t) {
            return fallbackARGB;
        }
    }

    /** Sets whether the 3D scene cover-fits the viewport (fill the screen). */
    public void setSceneCoverFit(boolean cover) {
        if (scene3d != null) scene3d.setCoverFit(cover);
    }

    /** Re-runs the 3D auto-fit on the next frame (e.g. after a fullscreen toggle). */
    public void resetSceneFit() {
        if (scene3d != null) {
            scene3d.resetFit();
            scene3d.resetZoom();
        }
    }

    private void renderScene3D() {
        Canvas c = canvas;
        if (c == null || scene3d.size() == 0) return;
        try {
            // Apply ambient + every point/spot/directional light from the display
            // data. Ambient is passed separately; all other lights are gathered
            // into the renderer's light array so multi-light GAMA displays (the
            // Lighting and Specular Effects recipes) shade correctly.
            int ambientARGB = 0xFFFFFFFF;
            java.util.List<AndroidScene3D.GamaLight> ls = new java.util.ArrayList<>();
            try {
                java.util.Map<String, Object> lights = (java.util.Map<String, Object>) data.getClass().getMethod("getLights").invoke(data);
                if (lights != null) {
                    for (java.util.Map.Entry<String, Object> e : lights.entrySet()) {
                        Object light = e.getValue();
                        if (light == null) continue;
                        try {
                            String type = String.valueOf(light.getClass().getMethod("getType").invoke(light));
                            if (type == null) continue;
                            String name = e.getKey() != null ? String.valueOf(e.getKey()) : "";
                            // Ambient is identified by its NAME ("Ambient light") because
                            // #ambient keeps the default #direction type.
                            if (name.contains("Ambient")) {
                                Object intensity = light.getClass().getMethod("getIntensity").invoke(light);
                                ambientARGB = colorToARGB(intensity, ambientARGB);
                                continue;
                            }
                            Object intensityObj = light.getClass().getMethod("getIntensity").invoke(light);
                            int argb = colorToARGB(intensityObj, 0);
                            int rr = (argb >>> 16) & 0xFF, gg = (argb >>> 8) & 0xFF, bb = argb & 0xFF;
                            AndroidScene3D.GamaLight gl = new AndroidScene3D.GamaLight();
                            gl.r = rr / 255f; gl.g = gg / 255f; gl.b = bb / 255f;
                            gl.ca = 1f; gl.la = 0f; gl.qa = 0f;
                            gl.active = (rr | gg | bb) != 0;
                            if (!type.contains("Direction")) {
                                try {
                                    Object loc = light.getClass().getMethod("getLocation").invoke(light);
                                    if (loc != null) {
                                        gl.px = ((Number) loc.getClass().getMethod("getX").invoke(loc)).floatValue();
                                        gl.py = ((Number) loc.getClass().getMethod("getY").invoke(loc)).floatValue();
                                        gl.pz = ((Number) loc.getClass().getMethod("getZ").invoke(loc)).floatValue();
                                    }
                                } catch (Throwable ignored) { }
                            }
                            // Beam axis / direction (unit, toward the lit scene).
                            try {
                                Object dir = light.getClass().getMethod("getDirection").invoke(light);
                                if (dir != null) {
                                    float dx = ((Number) dir.getClass().getMethod("getX").invoke(dir)).floatValue();
                                    float dy = ((Number) dir.getClass().getMethod("getY").invoke(dir)).floatValue();
                                    float dz = ((Number) dir.getClass().getMethod("getZ").invoke(dir)).floatValue();
                                    float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
                                    if (len > 1e-6f) { gl.ldx = dx / len; gl.ldy = dy / len; gl.ldz = dz / len; }
                                    else { gl.ldx = 0; gl.ldy = 0; gl.ldz = 1; }
                                }
                            } catch (Throwable ignored) { }
                            if (type.contains("Spot")) {
                                gl.type = AndroidScene3D.LT_SPOT;
                                // A dynamic spot may expose a null direction (its
                                // direction is a live expression not pre-evaluated).
                                // Fall back to a downward beam so it still lights.
                                if (gl.ldx == 0 && gl.ldy == 0 && gl.ldz == 0) {
                                    gl.ldx = 0; gl.ldy = 0; gl.ldz = -1;
                                }
                                double ang = 45d;
                                try { ang = ((Number) light.getClass().getMethod("getAngle").invoke(light)).doubleValue(); }
                                catch (Throwable ignored) { }
                                gl.cosSpot = (float) Math.cos(Math.toRadians(Math.max(0.0, Math.min(90.0, ang))));
                            } else if (type.contains("Point")) {
                                gl.type = AndroidScene3D.LT_POINT;
                            } else if (type.contains("Direction")) {
                                gl.type = AndroidScene3D.LT_DIR;
                                gl.ldx = -gl.ldx; gl.ldy = -gl.ldy; gl.ldz = -gl.ldz;
                            }
                            try { gl.ca = ((Number) light.getClass().getMethod("getConstantAttenuation").invoke(light)).floatValue(); } catch (Throwable ignored) { }
                            try { gl.la = ((Number) light.getClass().getMethod("getLinearAttenuation").invoke(light)).floatValue(); } catch (Throwable ignored) { }
                            try { gl.qa = ((Number) light.getClass().getMethod("getQuadraticAttenuation").invoke(light)).floatValue(); } catch (Throwable ignored) { }
                            ls.add(gl);
                        } catch (Throwable t) { /* skip unreadable light */ }
                    }
                }
            } catch (Throwable t) {
                android.util.Log.w("ANDROID_3D", "Failed to get lights: " + t);
            }
            scene3d.setAmbientLight(ambientARGB);
            scene3d.setLights(ls.toArray(new AndroidScene3D.GamaLight[0]));

            // Pass the display background color to the 3D renderer so the
            // frame bitmap matches the model's background:#... instead of
            // always using white.
            try {
                LayeredDisplayOutput out = getSurface() instanceof AndroidDisplaySurface
                        ? ((AndroidDisplaySurface) getSurface()).getOutput() : null;
                if (out != null) {
                    int displayBg = gama.api.types.color.IColor.toAWTColor(out.getData().getBackgroundColor()).getRGB();
                    scene3d.setBgColor(displayBg);
                }
            } catch (Throwable t) { /* keep default white */ }
            // The display's axes/draw_env facet maps onto isDrawEnv() in this
            // build, whose default comes from a preference (CORE_DRAW_ENV). Only
            // honour it when the model explicitly wrote one of the two facets,
            // otherwise every 3D display would suddenly show the world axes.
            boolean axesOn = false;
            try {
                LayeredDisplayOutput out = getSurface() instanceof AndroidDisplaySurface
                        ? ((AndroidDisplaySurface) getSurface()).getOutput() : null;
                if (out != null && (out.hasFacet("axes") || out.hasFacet("draw_env"))) {
                    axesOn = data.isDrawEnv();
                }
            } catch (Throwable ignored) {
            }
            scene3d.setAxesEnabled(axesOn);

            IPoint camPos = null, camTarget = null;
            Double camLens = null;
            try {
                camPos = data.getCameraPos();
                camTarget = data.getCameraTarget();
                camLens = data.getCameraLens();
            } catch (Throwable camErr) {
                camPos = null;
            }
            // The camera framing is frozen by AndroidScene3D itself on the first
            // render (to the live prims bounds), so live agent movement outside
            // the world never pans/re-scales the ground.
            if (camPos == null || camTarget == null) {
                scene3d.explicitCamera = false;
                scene3d.renderDefaultTopDown(c, 45.0, getDisplayWidth(), getDisplayHeight());
                return;
            }
            scene3d.explicitCamera = true;
            scene3d.setViewPos(camPos.getX(), camPos.getY(), camPos.getZ());
            scene3d.render(c,
                    camPos.getX(), camPos.getY(), camPos.getZ(),
                    camTarget.getX(), camTarget.getY(), camTarget.getZ(),
                    camLens != null ? camLens : 45.0,
                    getDisplayWidth(), getDisplayHeight());
        } catch (Throwable t) {
            android.util.Log.w("ANDROID_3D", "renderScene3D failed: " + t);
        }
    }

    private int layerCount = 0;
    private int layerPrimStart = -1;

    @Override
    public void beginDrawingLayer(final ILayer layer) {
        currentLayer = layer;
        layerCount++;
        if (is3dMode()) {
            layerPrimStart = scene3d.size();
            scene3d.nextLayer();
        }
        applyLayerTransparency(layer);
    }

    /** Applies the GAML layer transparency (0 = opaque, 1 = invisible) as an
     *  opacity factor so translucent layers composite through the painter's
     *  algorithm instead of being drawn fully opaque. */
    private void applyLayerTransparency(ILayer layer) {
        double alpha = 1.0;
        try {
            if (layer != null && layer.getData() != null) {
                Double t = layer.getData().getTransparency(getSurface().getScope());
                if (t != null) alpha = Math.max(0.0, Math.min(1.0, 1.0 - t));
            }
        } catch (Throwable ignored) {
        }
        setAlpha(alpha);
    }

    @Override
    public void endDrawingLayer(ILayer layer) {
        super.endDrawingLayer(layer);
        if (is3dMode() && layerPrimStart >= 0) {
            boolean isDynamic = layer.getData().isDynamic();
            if (!isDynamic) {
                scene3d.captureStaticPrims(layer, layerPrimStart);
            }
        }
        layerPrimStart = -1;
        setAlpha(1);
    }

    public void manuallyDrawAgents(IAgent[] agents) {
        if (canvas == null || agents == null) return;
        fillPaint.setColor(0xFF0000FF);
        fillPaint.setStyle(Paint.Style.FILL);
        for (IAgent a : agents) {
            if (a == null || a.dead()) continue;
            float x = toPixelX(a.getLocation().getX());
            float y = toPixelY(a.getLocation().getY());
            float r = (float) toPixelW(3.0);
            canvas.drawCircle(x, y, r, fillPaint);
        }
    }

    @Override
    public void beginOverlay(ILayer layer) {
        if (canvas == null) return;
        if (is3dMode()) {
            int ow = getDisplayWidth();
            int oh = getDisplayHeight();
            if (overlayBitmap == null || overlayBitmap.getWidth() != Math.max(1, ow)
                    || overlayBitmap.getHeight() != Math.max(1, oh)) {
                overlayBitmap = Bitmap.createBitmap(Math.max(1, ow), Math.max(1, oh), Bitmap.Config.ARGB_8888);
                overlayCanvas = new Canvas(overlayBitmap);
            }
            overlayCanvas.drawColor(0, android.graphics.PorterDuff.Mode.CLEAR);
            canvas = overlayCanvas;
            overlayActive = true;
        }
        int x = (int) getXOffsetInPixels();
        int y = (int) getYOffsetInPixels();
        int w = getLayerWidth();
        int h = getLayerHeight();
        if (!(layer instanceof OverlayLayer overlay)) return;
        gama.core.outputs.layers.OverlayLayerData od = overlay.getData();
        bgPaint.setColor(gamaColorToArgb(od.getBackgroundColor(getSurface().getScope())));
        bgPaint.setStyle(Paint.Style.FILL);
        if (od.isRounded()) {
            canvas.drawRoundRect(new RectF(x, y, x + w, y + h), 10, 10, bgPaint);
        } else {
            canvas.drawRect(x, y, x + w, y + h, bgPaint);
        }
        if (od.getBorderColor() != null) {
            bgPaint.setColor(gamaColorToArgb(od.getBorderColor()));
            bgPaint.setStyle(Paint.Style.STROKE);
            if (od.isRounded()) {
                canvas.drawRoundRect(new RectF(x, y, x + w, y + h), 10, 10, bgPaint);
            } else {
                canvas.drawRect(x, y, x + w, y + h, bgPaint);
            }
        }
    }

    @Override
    public void endOverlay() {
        if (overlayActive) {
            canvas = mainCanvas;
            overlayActive = false;
        }
    }

    @Override
    public boolean is2D() { return !is3dMode(); }

    @Override
    public void dispose() {
        super.dispose();
        canvas = null;
        mainCanvas = null;
        overlayCanvas = null;
        if (overlayBitmap != null) {
            overlayBitmap.recycle();
            overlayBitmap = null;
        }
        overlayActive = false;
    }
}
