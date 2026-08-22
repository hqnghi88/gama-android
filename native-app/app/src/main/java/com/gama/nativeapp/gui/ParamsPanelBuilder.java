package com.gama.nativeapp.gui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.List;

import gama.api.gaml.symbols.IParameter;
import gama.api.gaml.statements.IStatement;
import gama.api.gaml.types.IType;
import gama.api.kernel.species.IExperimentSpecies;
import gama.api.runtime.scope.IScope;
import gama.api.types.color.GamaColorFactory;
import gama.api.types.color.IColor;
import gama.api.ui.IExperimentDisplayable;
import gama.core.experiment.parameters.TextStatement;

/**
 * Builds the experiment parameter pane (Params tab) from the GAMA engine:
 * parameters (switches, sliders, text fields, dropdowns, colors, read-only
 * complex types), text labels and user_command buttons, grouped by category.
 */
public final class ParamsPanelBuilder {

    private static final String TAG = "ParamsPanelBuilder";

    /** Preset palette for color parameters (ARGB ints). */
    private static final int[] PALETTE = {
            0xFFFFFFFF, 0xFF000000, 0xFF808080, 0xFFD3D3D3,
            0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFF00,
            0xFFFFA500, 0xFF800080, 0xFFFFC0CB, 0xFF00FFFF,
            0xFFFF00FF, 0xFFA52A2A, 0xFF32CD32, 0xFF008080,
            0xFFEE82EE, 0xFF00008B, 0xFF90EE90, 0xFF87CEEB
    };

    private ParamsPanelBuilder() {}

    public static void populate(Context ctx, LinearLayout container,
                                IExperimentSpecies expPlan, List<Runnable> refreshers) {
        container.removeAllViews();
        try {
            IScope scope = expPlan.getExperimentScope();
            List<IExperimentDisplayable> items =
                    new ArrayList<>(expPlan.getAgent().getDisplayables());
            if (items.isEmpty()) {
                addNote(ctx, container, "This experiment has no parameters.");
                return;
            }
            String currentCategory = null;
            for (IExperimentDisplayable d : items) {
                try {
                    if (d instanceof IParameter) {
                        container.addView(parameterRow(ctx, (IParameter) d, scope, refreshers));
                    } else if (d instanceof TextStatement) {
                        container.addView(textRow(ctx, (TextStatement) d, scope, refreshers));
                    } else if (d instanceof IStatement.UserCommand) {
                        container.addView(commandRow(ctx, (IStatement.UserCommand) d, scope));
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "Failed to render displayable " + safeTitle(d), t);
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "populate failed", t);
            addNote(ctx, container, "Failed to load parameters: " + t.getMessage());
        }
    }

    // ---- Row builders -------------------------------------------------

    private static View categoryHeader(Context ctx, String title) {
        TextView tv = new TextView(ctx);
        tv.setText(title.toUpperCase());
        tv.setTextSize(12);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setAllCaps(true);
        tv.setTextColor(thc(ctx, 0xFF006847, 0xFF66BB6A));
        tv.setPadding(0, dp(ctx, 14), 0, dp(ctx, 4));
        return tv;
    }

    private static View parameterRow(Context ctx, IParameter p, IScope scope,
                                     List<Runnable> refreshers) {
        boolean editable = safeIsEditable(p);
        IType<?> type = p.getType();
        int tid = type != null ? type.id() : IType.NONE;

        Object cur = safeValue(p, scope);
        List<?> among = safeAmong(p, scope);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(ctx, 6), 0, dp(ctx, 6));

        // Dropdown (among:) takes precedence over other editors
        if (editable && among != null && !among.isEmpty()) {
            TextView label = baseLabel(ctx, safeTitle(p));
            row.addView(label);

            final List<?> values = among;
            List<String> options = new ArrayList<>();
            for (Object o : among) options.add(String.valueOf(o));
            Spinner spinner = new Spinner(ctx);
            spinner.setAdapter(new ArrayAdapter<>(ctx,
                    android.R.layout.simple_spinner_dropdown_item, options));
            int sel = indexOfValue(among, cur);
            if (sel >= 0) spinner.setSelection(sel, false);
            spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override public void onItemSelected(AdapterView<?> parent, View v, int pos, long id) {
                    Object target = values.get(pos);
                    if (String.valueOf(target).equals(String.valueOf(safeValue(p, scope)))) return;
                    try { p.setValue(scope, target); } catch (Throwable t) { logSet(t); }
                }
                @Override public void onNothingSelected(AdapterView<?> parent) {}
            });
            row.addView(spinner);
            registerRefresher(refreshers, () -> {
                try {
                    int i = indexOfValue(values, p.value(scope));
                    if (i >= 0 && spinner.getSelectedItemPosition() != i) {
                        spinner.setSelection(i, false);
                    }
                } catch (Throwable ignored) {}
            });
            return row;
        }

        switch (tid) {
            case IType.BOOL: {
                LinearLayout brow = new LinearLayout(ctx);
                brow.setOrientation(LinearLayout.HORIZONTAL);
                brow.setGravity(Gravity.CENTER_VERTICAL);
                TextView label = baseLabel(ctx, safeTitle(p));
                label.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
                brow.addView(label);
                Switch sw = new Switch(ctx);
                sw.setChecked(Boolean.TRUE.equals(cur));
                sw.setOnCheckedChangeListener((b, checked) -> {
                    try { p.setValue(scope, checked); } catch (Throwable t) { logSet(t); }
                });
                brow.addView(sw);
                row.addView(brow);
                registerRefresher(refreshers, () -> {
                    try {
                        boolean v = Boolean.TRUE.equals(p.value(scope));
                        if (sw.isChecked() != v) sw.setChecked(v);
                    } catch (Throwable ignored) {}
                });
                return row;
            }
            case IType.INT:
            case IType.FLOAT: {
                Number minN = asNumber(safeMin(p, scope));
                Number maxN = asNumber(safeMax(p, scope));
                boolean slider = editable && minN != null && maxN != null
                        && safeAcceptsSlider(p, scope);
                if (!slider) {
                    buildNumericTextRow(ctx, row, safeTitle(p), p, scope, tid, cur);
                    return row;
                }
                double min = minN.doubleValue();
                double max = Math.max(min, maxN.doubleValue());
                Double stepN = asDouble(safeStep(p, scope));
                double step = stepN != null && stepN > 0 ? stepN
                        : (tid == IType.FLOAT ? Math.max((max - min) / 200d, 1e-4) : 1d);
                int steps = Math.max(1, (int) Math.round((max - min) / step));

                TextView valueLabel = new TextView(ctx);
                valueLabel.setTextSize(13);
                valueLabel.setTypeface(Typeface.MONOSPACE);
                valueLabel.setTextColor(thc(ctx, 0xFF006847, 0xFF81C784));

                LinearLayout head = new LinearLayout(ctx);
                head.setOrientation(LinearLayout.HORIZONTAL);
                head.setGravity(Gravity.CENTER_VERTICAL);
                TextView label = baseLabel(ctx, safeTitle(p));
                label.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
                head.addView(label);
                head.addView(valueLabel);
                row.addView(head);

                SeekBar seek = new SeekBar(ctx);
                seek.setMax(steps);
                double c0 = cur instanceof Number n ? n.doubleValue() : min;
                seek.setProgress(Math.max(0, Math.min(steps,
                        (int) Math.round((clamp(c0, min, max) - min) / step))));
                formatNumeric(valueLabel, tid, clamp(c0, min, max));
                row.addView(seek);
                seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                        double v = min + progress * step;
                        formatNumeric(valueLabel, tid, v);
                        if (!fromUser) return;
                        try {
                            p.setValue(scope, tid == IType.INT ? (int) Math.round(v) : v);
                        } catch (Throwable t) { logSet(t); }
                    }
                    @Override public void onStartTrackingTouch(SeekBar s) {}
                    @Override public void onStopTrackingTouch(SeekBar s) {}
                });
                registerRefresher(refreshers, () -> {
                    try {
                        Object v = p.value(scope);
                        if (v instanceof Number n) {
                            double d = n.doubleValue();
                            formatNumeric(valueLabel, tid, d);
                            int prog = (int) Math.round((clamp(d, min, max) - min) / step);
                            if (seek.getProgress() != prog) seek.setProgress(prog);
                        }
                    } catch (Throwable ignored) {}
                });
                return row;
            }
            case IType.STRING: {
                TextView label = baseLabel(ctx, safeTitle(p));
                row.addView(label);
                EditText edit = new EditText(ctx);
                edit.setSingleLine(true);
                edit.setTextSize(14);
                edit.setText(cur != null ? String.valueOf(cur) : "");
                edit.setOnEditorActionListener((v, actionId, event) -> {
                    try { p.setValue(scope, edit.getText().toString()); } catch (Throwable t) { logSet(t); }
                    return false;
                });
                row.addView(edit);
                return row;
            }
            case IType.COLOR: {
                LinearLayout crow = new LinearLayout(ctx);
                crow.setOrientation(LinearLayout.HORIZONTAL);
                crow.setGravity(Gravity.CENTER_VERTICAL);
                TextView label = baseLabel(ctx, safeTitle(p));
                label.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
                crow.addView(label);
                View swatch = new View(ctx);
                int argb = colorOf(cur, 0xFFFFFFFF);
                swatch.setBackgroundColor(argb);
                LinearLayout.LayoutParams slp =
                        new LinearLayout.LayoutParams(dp(ctx, 40), dp(ctx, 24));
                slp.setMargins(dp(ctx, 8), 0, 0, 0);
                swatch.setLayoutParams(slp);
                crow.addView(swatch);
                swatch.setOnClickListener(v -> showColorPicker(ctx, argb, picked -> {
                    swatch.setBackgroundColor(picked);
                    try { p.setValue(scope, GamaColorFactory.get(picked)); }
                    catch (Throwable t) { logSet(t); }
                }));
                row.addView(crow);
                registerRefresher(refreshers, () -> {
                    try {
                        int rgb = colorOf(p.value(scope), 0xFFFFFFFF);
                        swatch.setBackgroundColor(rgb);
                    } catch (Throwable ignored) {}
                });
                return row;
            }
            default: {
                // FILE, LIST, MATRIX, POINT, PAIR, MAP, ... read-only serialized view
                TextView label = baseLabel(ctx, safeTitle(p));
                row.addView(label);
                TextView value = new TextView(ctx);
                value.setTextSize(13);
                value.setTypeface(Typeface.MONOSPACE);
                value.setTextColor(thc(ctx, 0xFF555555, 0xFFAAAAAA));
                value.setText(readOnlyText(p, scope));
                row.addView(value);
                registerRefresher(refreshers, () -> {
                    try { value.setText(readOnlyText(p, scope)); } catch (Throwable ignored) {}
                });
                return row;
            }
        }
    }

    private static void buildNumericTextRow(Context ctx, LinearLayout row, String title,
                                            IParameter p, IScope scope, int tid, Object cur) {
        LinearLayout lrow = new LinearLayout(ctx);
        lrow.setOrientation(LinearLayout.HORIZONTAL);
        lrow.setGravity(Gravity.CENTER_VERTICAL);
        TextView label = baseLabel(ctx, title);
        label.setLayoutParams(new LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f));
        lrow.addView(label);
        EditText edit = new EditText(ctx);
        edit.setSingleLine(true);
        edit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL
                | InputType.TYPE_NUMBER_FLAG_SIGNED);
        edit.setTextSize(14);
        edit.setText(cur != null ? String.valueOf(cur) : "");
        edit.setMinimumWidth(dp(ctx, 80));
        lrow.addView(edit);
        row.addView(lrow);
        Runnable commit = () -> {
            try {
                String s = edit.getText().toString().trim();
                if (s.isEmpty()) return;
                if (tid == IType.INT) p.setValue(scope, (int) Double.parseDouble(s));
                else p.setValue(scope, Double.parseDouble(s));
            } catch (Throwable t) { logSet(t); }
        };
        edit.setOnEditorActionListener((v, actionId, event) -> {
            commit.run();
            return false;
        });
        edit.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) commit.run(); });
    }

    private static View textRow(Context ctx, TextStatement t, IScope scope,
                                List<Runnable> refreshers) {
        TextView tv = new TextView(ctx);
        tv.setTextSize(13);
        tv.setPadding(0, dp(ctx, 8), 0, dp(ctx, 4));
        try {
            tv.setText(t.getText(scope));
            IColor c = t.getColor(scope);
            if (c != null) tv.setTextColor(colorOf(c, 0xFF333333));
            IColor bg = t.getBackground(scope);
            if (bg != null) tv.setBackgroundColor(colorOf(bg, 0x00000000));
        } catch (Throwable ex) {
            Log.w(TAG, "textRow", ex);
        }
        registerRefresher(refreshers, () -> {
            try {
                String s = t.getText(scope);
                if (!s.equals(tv.getText().toString())) tv.setText(s);
            } catch (Throwable ignored) {}
        });
        return tv;
    }

    private static View commandRow(Context ctx, IStatement.UserCommand cmd, IScope scope) {
        MaterialButton btn = new MaterialButton(ctx, null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle);
        btn.setText(cmd.getTitle());
        btn.setTextSize(13);
        btn.setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 4));
        btn.setMinimumHeight(0);
        btn.setInsetTop(0);
        btn.setInsetBottom(0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(ctx, 4), dp(ctx, 8), dp(ctx, 4));
        btn.setLayoutParams(lp);
        btn.setOnClickListener(v -> executeCommand(cmd, scope));
        return btn;
    }

    private static void executeCommand(IStatement.UserCommand cmd, IScope scope) {
        Thread t = new Thread(() -> {
            try {
                java.lang.reflect.Method m =
                        cmd.getClass().getMethod("privateExecuteIn", IScope.class);
                m.invoke(cmd, scope);
            } catch (Throwable e) {
                Log.e(TAG, "user_command failed: " + cmd.getTitle(), e);
            }
        }, "gama-user-command");
        t.setDaemon(true);
        t.start();
    }

    // ---- Color picker ---------------------------------------------------

    private interface ColorPicked { void onPick(int argb); }

    private static void showColorPicker(Context ctx, int current, ColorPicked cb) {
        AlertDialog dlg = new AlertDialog.Builder(ctx).setTitle("Choose color").create();
        LinearLayout grid = new LinearLayout(ctx);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setPadding(dp(ctx, 12), dp(ctx, 8), dp(ctx, 12), dp(ctx, 8));
        int cols = 5;
        int rows = (int) Math.ceil(PALETTE.length / (double) cols);
        for (int r = 0; r < rows; r++) {
            LinearLayout line = new LinearLayout(ctx);
            line.setOrientation(LinearLayout.HORIZONTAL);
            for (int c = 0; c < cols; c++) {
                int idx = r * cols + c;
                if (idx >= PALETTE.length) break;
                View cell = new View(ctx);
                cell.setBackgroundColor(PALETTE[idx]);
                LinearLayout.LayoutParams lp =
                        new LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36));
                lp.setMargins(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
                cell.setLayoutParams(lp);
                int picked = PALETTE[idx];
                cell.setOnClickListener(v -> {
                    cb.onPick(picked);
                    dlg.dismiss();
                });
                line.addView(cell);
            }
            grid.addView(line);
        }
        dlg.setView(grid);
        dlg.show();
    }

    // ---- Helpers ---------------------------------------------------------

    private static void registerRefresher(List<Runnable> refreshers, Runnable r) {
        if (refreshers != null) refreshers.add(r);
    }

    private static void addNote(Context ctx, LinearLayout container, String msg) {
        TextView tv = new TextView(ctx);
        tv.setText(msg);
        tv.setTextSize(13);
        tv.setTextColor(thc(ctx, 0xFF777777, 0xFF999999));
        tv.setPadding(dp(ctx, 4), dp(ctx, 12), dp(ctx, 4), dp(ctx, 12));
        container.addView(tv);
    }

    private static TextView baseLabel(Context ctx, String title) {
        TextView label = new TextView(ctx);
        label.setText(title);
        label.setTextSize(14);
        label.setTextColor(thc(ctx, 0xFF333333, 0xFFE0E0E0));
        label.setPadding(0, 0, 0, dp(ctx, 2));
        return label;
    }

    private static String readOnlyText(IParameter p, IScope scope) {
        try { return String.valueOf(p.value(scope)); } catch (Throwable t) { return "?"; }
    }

    private static int indexOfValue(List<?> values, Object cur) {
        for (int i = 0; i < values.size(); i++) {
            if (String.valueOf(values.get(i)).equals(String.valueOf(cur))) return i;
        }
        return -1;
    }

    private static String safeTitle(IExperimentDisplayable d) {
        try {
            String t = d.getTitle();
            if (t != null && !t.isEmpty()) return t;
        } catch (Throwable ignored) {}
        try {
            String n = d.getName();
            if (n != null) return n;
        } catch (Throwable ignored) {}
        return "(unnamed)";
    }

    private static Object safeValue(IParameter p, IScope scope) {
        try { return p.value(scope); } catch (Throwable t) { return null; }
    }

    private static boolean safeIsEditable(IParameter p) {
        try { return p.isEditable(); } catch (Throwable t) { return false; }
    }

    private static boolean safeAcceptsSlider(IParameter p, IScope scope) {
        try { return p.acceptsSlider(scope); } catch (Throwable t) { return true; }
    }

    private static List<?> safeAmong(IParameter p, IScope scope) {
        try { return p.getAmongValue(scope); } catch (Throwable t) { return null; }
    }

    private static Object safeMin(IParameter p, IScope scope) {
        try { return p.getMinValue(scope); } catch (Throwable t) { return null; }
    }

    private static Object safeMax(IParameter p, IScope scope) {
        try { return p.getMaxValue(scope); } catch (Throwable t) { return null; }
    }

    private static Object safeStep(IParameter p, IScope scope) {
        try { return p.getStepValue(scope); } catch (Throwable t) { return null; }
    }

    private static Number asNumber(Object o) { return o instanceof Number n ? n : null; }

    private static Double asDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return null;
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }

    private static void formatNumeric(TextView tv, int tid, double v) {
        if (tid == IType.INT) tv.setText(String.valueOf(Math.round(v)));
        else tv.setText(String.format(java.util.Locale.US, "%.3f", v));
    }

    private static int colorOf(Object c, int fallback) {
        if (c instanceof IColor gc) {
            try { return gc.getRGB(); } catch (Throwable ignored) {}
        }
        if (c instanceof java.awt.Color ac) return ac.getRGB();
        return fallback;
    }

    private static void logSet(Throwable t) {
        Log.w(TAG, "setParameter failed", t);
    }

    private static int thc(Context ctx, int light, int dark) {
        boolean night = (ctx.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                == android.content.res.Configuration.UI_MODE_NIGHT_YES;
        return night ? dark : light;
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
