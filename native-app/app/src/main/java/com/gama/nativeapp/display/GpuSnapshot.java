package com.gama.nativeapp.display;

import java.util.List;

/**
 * Immutable snapshot of the 3D scene for GPU rendering.
 * Holds a reference to the live prims list (safe because the sim thread blocks
 * on the GPU render latch while the GL thread reads it).
 */
final class GpuSnapshot {

    final List<AndroidScene3D.Prim> prims;
    final float[] view = new float[16];
    final float[] proj = new float[16];
    final int viewW, viewH;
    final int bgColor;
    final float nearPlane, farPlane;
    final float ambR, ambG, ambB;
    final AndroidScene3D.GamaLight[] lights;

    GpuSnapshot(List<AndroidScene3D.Prim> prims,
                float[] viewMatrix, float[] projMatrix,
                int viewW, int viewH,
                int bgColor, float nearPlane, float farPlane,
                float ambR, float ambG, float ambB,
                AndroidScene3D.GamaLight[] lights) {
        this.prims = prims;
        System.arraycopy(viewMatrix, 0, this.view, 0, 16);
        System.arraycopy(projMatrix, 0, this.proj, 0, 16);
        this.viewW = viewW;
        this.viewH = viewH;
        this.bgColor = bgColor;
        this.nearPlane = nearPlane;
        this.farPlane = farPlane;
        this.ambR = ambR;
        this.ambG = ambG;
        this.ambB = ambB;
        this.lights = lights != null ? lights : new AndroidScene3D.GamaLight[0];
    }
}
