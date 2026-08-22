package com.gama.nativeapp.gui;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import gama.api.gaml.symbols.IParameter;
import gama.api.runtime.scope.IScope;
import gama.api.gaml.types.IType;

/**
 * Blocking Android dialogs for GAMA user interactions (user_confirm,
 * user_input_dialog, wizard). The engine thread blocks on a latch while the
 * dialog runs on the UI thread, mirroring desktop SWT modal behavior.
 */
public final class AndroidDialogs {

    private static final String TAG = "AndroidDialogs";

    private AndroidDialogs() {}

    /** Runs a UI task and blocks the calling thread until it completes. */
    public static <T> T runBlocking(Activity activity, long timeoutMs, Supplier<T> uiTask) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        final Object[] result = new Object[1];
        CountDownLatch latch = new CountDownLatch(1);
        activity.runOnUiThread(() -> {
            try {
                result[0] = uiTask.get();
            } catch (Throwable t) {
                android.util.Log.e(TAG, "dialog task failed", t);
            } finally {
                latch.countDown();
            }
        });
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        @SuppressWarnings("unchecked")
        T r = (T) result[0];
        return r;
    }

    /** user_confirm / question: OK returns true, Cancel/dismiss false. */
    public static boolean confirm(final Activity activity, final String title, final String message) {
        Boolean res = runBlocking(activity, 10 * 60_000L, () -> {
            final Boolean[] answer = {Boolean.FALSE};
            AlertDialog d = new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(message)
                    .setPositiveButton("OK", (dlg, w) -> answer[0] = Boolean.TRUE)
                    .setNegativeButton("Cancel", (dlg, w) -> answer[0] = Boolean.FALSE)
                    .setOnCancelListener(dlg -> answer[0] = Boolean.FALSE)
                    .create();
            d.show();
            return answer[0];
        });
        return Boolean.TRUE.equals(res);
    }

    /** error / inform / warning: OK-only message box. */
    public static void message(final Activity activity, final String title, final String msg) {
        runBlocking(activity, 10 * 60_000L, () -> {
            AlertDialog d = new AlertDialog.Builder(activity)
                    .setTitle(title)
                    .setMessage(msg)
                    .setPositiveButton("OK", null)
                    .create();
            d.show();
            return Boolean.TRUE;
        });
    }

    /**
     * user_input_dialog: one editor per IParameter (switch, numeric/text field
     * or dropdown for among values). Returns entered values keyed by parameter
     * name; empty map when cancelled.
     */
    public static Map<String, Object> userInput(final Activity activity, IScope scope,
                                                final String title, List<IParameter> params) {
        Map<String, Object> res = runBlocking(activity, 10 * 60_000L, () -> {
            LinearLayout form = new LinearLayout(activity);
            form.setOrientation(LinearLayout.VERTICAL);
            int pad = dp(activity, 20);
            form.setPadding(pad, dp(activity, 8), pad, 0);

            final Map<String, Object> values = new LinkedHashMap<>();
            if (params != null) {
                for (IParameter p : params) {
                    View editor = buildEditor(activity, p, scope, values);
                    if (editor != null) form.addView(editor);
                }
            }
            ScrollView scroll = new ScrollView(activity);
            scroll.addView(form);

            final Map<String, Object>[] out = new Map[1];
            AlertDialog d = new AlertDialog.Builder(activity)
                    .setTitle(title == null ? "Input" : title)
                    .setView(scroll)
                    .setPositiveButton("OK", (dlg, w) -> out[0] = values)
                    .setNegativeButton("Cancel", (dlg, w) -> out[0] = new LinkedHashMap<>())
                    .setOnCancelListener(dlg -> out[0] = new LinkedHashMap<>())
                    .create();
            d.show();
            return out[0];
        });
        return res != null ? res : new LinkedHashMap<>();
    }

    private static View buildEditor(Activity ctx, IParameter p, IScope scope,
                                    Map<String, Object> values) {
        String name = p.getName();
        IType<?> type = p.getType();
        int tid = type != null ? type.id() : IType.STRING;
        Object cur = safe(() -> p.value(scope));
        if (cur == null) cur = safe(() -> p.getInitialValue(scope));
        List<?> among = safe(() -> p.getAmongValue(scope));

        TextView label = new TextView(ctx);
        label.setText(name);
        label.setTextSize(14);
        label.setPadding(0, dp(ctx, 10), 0, dp(ctx, 2));

        if (among != null && !among.isEmpty()) {
            Spinner sp = new Spinner(ctx);
            sp.setAdapter(new ArrayAdapter<>(ctx,
                    android.R.layout.simple_spinner_dropdown_item,
                    among.stream().map(String::valueOf).toArray()));
            int sel = -1;
            for (int i = 0; i < among.size(); i++) {
                if (String.valueOf(among.get(i)).equals(String.valueOf(cur))) { sel = i; break; }
            }
            if (sel >= 0) sp.setSelection(sel, false);
            sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                    values.put(name, among.get(pos));
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
            if (sel < 0) values.put(name, among.get(0));
            LinearLayout wrap = new LinearLayout(ctx);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.addView(label);
            wrap.addView(sp);
            return wrap;
        }

        switch (tid) {
            case IType.BOOL: {
                Switch sw = new Switch(ctx);
                sw.setChecked(Boolean.TRUE.equals(cur));
                sw.setOnCheckedChangeListener((b, checked) ->
                        values.put(name, checked));
                values.put(name, sw.isChecked());
                LinearLayout wrap = new LinearLayout(ctx);
                wrap.setOrientation(LinearLayout.HORIZONTAL);
                label.setLayoutParams(new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
                wrap.addView(label);
                wrap.addView(sw);
                return wrap;
            }
            case IType.INT:
            case IType.FLOAT: {
                EditText edit = new EditText(ctx);
                edit.setInputType(InputType.TYPE_CLASS_NUMBER
                        | InputType.TYPE_NUMBER_FLAG_DECIMAL
                        | InputType.TYPE_NUMBER_FLAG_SIGNED);
                edit.setText(cur != null ? String.valueOf(cur) : "");
                edit.setOnFocusChangeListener((v, focus) -> commitNumeric(edit, tid, name, values));
                LinearLayout wrap = new LinearLayout(ctx);
                wrap.setOrientation(LinearLayout.VERTICAL);
                wrap.addView(label);
                wrap.addView(edit);
                // Pre-fill so OK without touching still yields a value
                commitNumeric(edit, tid, name, values);
                return wrap;
            }
            default: {
                EditText edit = new EditText(ctx);
                edit.setSingleLine(true);
                edit.setText(cur != null ? String.valueOf(cur) : "");
                values.put(name, edit.getText().toString());
                LinearLayout wrap = new LinearLayout(ctx);
                wrap.setOrientation(LinearLayout.VERTICAL);
                wrap.addView(label);
                wrap.addView(edit);
                return wrap;
            }
        }
    }

    private static void commitNumeric(EditText edit, int tid, String name,
                                      Map<String, Object> values) {
        try {
            String s = edit.getText().toString().trim();
            if (s.isEmpty()) return;
            if (tid == IType.INT) {
                values.put(name, (int) Double.parseDouble(s));
            } else {
                values.put(name, Double.parseDouble(s));
            }
        } catch (Throwable ignored) {}
    }

    private static <T> T safe(Supplier<T> s) {
        try { return s.get(); } catch (Throwable t) { return null; }
    }

    private static int dp(Activity ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
