package com.gama.nativeapp.gui;

import android.util.Log;

import gama.api.ui.displays.IDisplaySurface;
import gama.api.ui.IGamaView;
import gama.api.ui.IOutput;
import gama.core.outputs.LayeredDisplayOutput;
import gama.api.runtime.scope.IScope;

public class AndroidGamaView implements IGamaView.Display {

    private static final String TAG = "AndroidGamaView";
    private String name;
    private LayeredDisplayOutput output;

    public AndroidGamaView(String name) {
        this.name = name;
    }

    @Override
    public void update(IOutput out) {
        if (out instanceof LayeredDisplayOutput) {
            this.output = (LayeredDisplayOutput) out;
            IDisplaySurface surface = output.getSurface();
            if (surface != null && !surface.isDisposed()) {
                surface.updateDisplay(false, null);
            }
        }
    }

    @Override
    public void addOutput(IOutput output) {
        if (output instanceof LayeredDisplayOutput) {
            this.output = (LayeredDisplayOutput) output;
        }
    }

    @Override
    public void removeOutput(IOutput out) {}

    @Override
    public LayeredDisplayOutput getOutput() { return output; }

    @Override
    public void close(IScope scope) {
    }

    @Override
    public void changePartNameWithSimulation(gama.api.kernel.simulation.ISimulationAgent agent) {}

    @Override
    public void reset() {}

    @Override
    public String getPartName() { return name != null ? name : "Display"; }

    @Override
    public void setName(String name) { this.name = name; }

    @Override
    public void updateToolbarState() {}

    @Override
    public void showToolbar(boolean show) {}

    @Override
    public boolean containsPoint(int x, int y) { return false; }

    @Override
    public IDisplaySurface getDisplaySurface() {
        return output != null ? output.getSurface() : null;
    }

    @Override
    public void toggleFullScreen() {}

    @Override
    public boolean isFullScreen() { return false; }

    @Override
    public void toggleOverlay() {}

    @Override
    public void showOverlay(boolean show) {}

    @Override
    public int getIndex() { return 0; }

    @Override
    public void setIndex(int i) {}

    @Override
    public void takeSnapshot(gama.api.types.geometry.IPoint customDimensions) {}

    @Override
    public boolean isHiDPI() { return false; }

    @Override
    public boolean is2D() { return true; }

    @Override
    public boolean isEscRedefined() { return false; }

    @Override
    public boolean isVisible() { return true; }
}
