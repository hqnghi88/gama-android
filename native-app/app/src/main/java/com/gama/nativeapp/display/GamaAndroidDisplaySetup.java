package com.gama.nativeapp.display;

import android.util.Log;

import gama.api.gaml.GAML;

public class GamaAndroidDisplaySetup {

    private static final String TAG = "GamaAndroidSetup";

    public static void registerDisplays() {
        GAML.addConstants("android2d", "2d", "android3d", "3d", "opengl", "opengl2");
        Log.i(TAG, "Registered android2d/android3d display types");
    }
}
