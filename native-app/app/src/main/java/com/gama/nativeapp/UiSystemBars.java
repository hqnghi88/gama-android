package com.gama.nativeapp;

import android.app.Activity;
import android.view.View;

import androidx.activity.ComponentActivity;
import androidx.activity.EdgeToEdge;
import androidx.core.graphics.Insets;
import androidx.core.view.OnApplyWindowInsetsListener;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * Android 15+ (targetSdk 35+) enforces edge-to-edge. The app opts in explicitly
 * and keeps content clear of the system bars using the modern WindowInsets APIs
 * instead of the deprecated statusBarColor/SYSTEM_UI_FLAG_* calls.
 */
final class UiSystemBars {

    private UiSystemBars() {}

    static void enable(ComponentActivity activity) {
        EdgeToEdge.enable(activity);
    }

    /** Minimal opt-in for plain android.app.Activity subclasses. */
    static void enable(Activity activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
    }

    /** Pads a root view so content renders below the status bar and above the
     *  navigation bar while edge-to-edge is active. Existing manual padding is
     *  preserved. */
    static <T extends View> T applyInsets(final T root) {
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, new OnApplyWindowInsetsListener() {
            @Override
            public WindowInsetsCompat onApplyWindowInsets(View v, WindowInsetsCompat insets) {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(baseLeft + systemBars.left,
                        baseTop + systemBars.top,
                        baseRight + systemBars.right,
                        baseBottom + systemBars.bottom);
                return insets;
            }
        });
        return root;
    }

    /** Toggles immersive fullscreen (hide system bars, swipe to reveal) using the
     *  modern controller API. */
    static void setFullscreen(View decor, boolean fullscreen) {
        WindowInsetsControllerCompat controller = ViewCompat.getWindowInsetsController(decor);
        if (controller == null) return;
        if (fullscreen) {
            controller.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
    }

    /** Sets whether the status bar icons are dark (light background). */
    static void setLightStatusBar(View decor, boolean light) {
        WindowInsetsControllerCompat controller = ViewCompat.getWindowInsetsController(decor);
        if (controller == null) return;
        controller.setAppearanceLightStatusBars(light);
    }
}