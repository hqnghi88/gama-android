package com.gama.nativeapp;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.content.Intent;
import android.widget.Button;
import android.view.Gravity;
import androidx.appcompat.app.AppCompatActivity;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private TextView statusText;
    private TextView logText;
    private LinearLayout modelPanel;
    private final StringBuilder logBuilder = new StringBuilder();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setGuiActivity(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(32, 32, 32, 32);

        statusText = new TextView(this);
        statusText.setText("Initializing GAMA engine...");
        statusText.setTextSize(18);
        statusText.setPadding(0, 0, 0, 16);
        root.addView(statusText);

        modelPanel = new LinearLayout(this);
        modelPanel.setOrientation(LinearLayout.VERTICAL);
        modelPanel.setVisibility(android.view.View.GONE);
        root.addView(modelPanel);

        logText = new TextView(this);
        logText.setTextSize(12);
        logText.setTypeface(android.graphics.Typeface.MONOSPACE);

        ScrollView scrollView = new ScrollView(this);
        scrollView.addView(logText);
        root.addView(scrollView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f));

        setContentView(root);

        appendLog("Starting GAMA Native Android initialization...");

        executor.execute(() -> {
            try {
                GamaNativeBootstrap.initialize(MainActivity.this, new GamaNativeBootstrap.ProgressCallback() {
                    @Override
                    public void onProgress(String message) {
                        appendLog("[PROGRESS] " + message);
                    }

                    @Override
                    public void onSuccess(String message) {
                        appendLog("[SUCCESS] " + message);
                        mainHandler.post(() -> {
                            statusText.setText(message);
                            showModelBrowser();
                        });
                    }

                    @Override
                    public void onFailure(String message, Throwable t) {
                        appendLog("[FAILURE] " + message + ": " + t.getMessage());
                        mainHandler.post(() -> statusText.setText("FAILED: " + message));
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "Bootstrap failed", e);
                appendLog("[ERROR] " + e.getClass().getSimpleName() + ": " + e.getMessage());
                if (e.getCause() != null) {
                    appendLog("[CAUSE] " + e.getCause().getMessage());
                }
                mainHandler.post(() -> statusText.setText("ERROR: " + e.getMessage()));
            }
        });
    }

    private void showModelBrowser() {
        modelPanel.removeAllViews();
        modelPanel.setVisibility(android.view.View.VISIBLE);

        TextView label = new TextView(this);
        label.setText("Available Models:");
        label.setTextSize(16);
        label.setPadding(0, 16, 0, 8);
        modelPanel.addView(label);

        String[] models;
        try {
            models = getAssets().list("models");
        } catch (Exception e) {
            models = new String[0];
        }

        if (models == null || models.length == 0) {
            TextView none = new TextView(this);
            none.setText("No .gaml models found in assets/models/");
            none.setTextSize(14);
            modelPanel.addView(none);
            return;
        }

        for (String model : models) {
            if (!model.endsWith(".gaml")) continue;
            String modelName = model.replace(".gaml", "");

            Button btn = new Button(this);
            btn.setText(modelName);
            btn.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            btn.setOnClickListener(v -> launchExperiment(modelName, model));
            modelPanel.addView(btn);
        }
    }

    private void launchExperiment(String modelName, String fileName) {
        appendLog("Launching model: " + modelName);
        Intent intent = new Intent(this, ExperimentActivity.class);
        intent.putExtra("model_name", modelName);
        intent.putExtra("asset_path", "models/" + fileName);
        startActivity(intent);
    }

    private static void setGuiActivity(MainActivity activity) {
        try {
            Class<?> handlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
            handlerClass.getMethod("setActivity", android.app.Activity.class).invoke(null, activity);
        } catch (Throwable e) {
            Log.w(TAG, "Could not set GUI activity (will retry after bootstrap)", e);
        }
    }

    private void appendLog(String message) {
        mainHandler.post(() -> {
            logBuilder.append(message).append("\n");
            logText.setText(logBuilder.toString());
        });
    }
}
