package com.gama.nativeapp.util;

import gama.core.common.interfaces.ILayer;
import gama.core.outputs.LayeredDisplayOutput;
import gama.core.outputs.display.LayerManager;
import gama.core.outputs.layers.ILayerStatement;

/**
 * Wraps LayerManager.createLayer to catch exceptions per-layer,
 * preventing one failing layer (e.g. ImageLayer) from crashing the entire display.
 */
public class LayerManagerHelper {
    public static ILayer createLayerSafe(LayeredDisplayOutput output, ILayerStatement layer) {
        try {
            return LayerManager.createLayer(output, layer);
        } catch (Throwable t) {
            System.err.println("WARNING: Skipping layer (error: " + t.getMessage() + ")");
            t.printStackTrace();
            return null;
        }
    }
}
