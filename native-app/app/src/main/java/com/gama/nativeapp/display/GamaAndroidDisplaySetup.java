package com.gama.nativeapp.display;

import android.util.Log;

import gama.core.common.interfaces.IDisplayCreator;
import gama.core.common.interfaces.IDisplaySurface;
import gama.core.common.interfaces.IGui;
import gama.gaml.compilation.GAML;

public class GamaAndroidDisplaySetup {

    private static final String TAG = "GamaAndroidSetup";

    public static void registerDisplays() {
        IDisplayCreator creator = (args) -> {
            if (args.length == 0) return null;
            return new AndroidDisplaySurface(
                    com.gama.nativeapp.gui.AndroidGuiHandler.getCurrentActivity(),
                    (gama.core.outputs.LayeredDisplayOutput) args[0]);
        };

        IDisplayCreator.DisplayDescription desc2d = new IDisplayCreator.DisplayDescription(
                creator,
                AndroidDisplaySurface.class,
                "android2d",
                "com.gama.nativeapp"
        );

        IGui.DISPLAYS.put("android2d", desc2d);
        IGui.DISPLAYS.put("2d", desc2d);
        IGui.DISPLAYS.put("android3d", desc2d);
        IGui.DISPLAYS.put("3d", desc2d);
        IGui.DISPLAYS.put("opengl", desc2d);
        IGui.DISPLAYS.put("opengl2", desc2d);

        GAML.CONSTANTS.add("android2d");
        GAML.CONSTANTS.add("2d");
        GAML.CONSTANTS.add("android3d");
        GAML.CONSTANTS.add("3d");
        GAML.CONSTANTS.add("opengl");
        GAML.CONSTANTS.add("opengl2");

        Log.i(TAG, "Registered android2d/android3d display types");
    }
}
