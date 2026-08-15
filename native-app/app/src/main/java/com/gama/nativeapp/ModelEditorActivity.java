package com.gama.nativeapp;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.content.ContextCompat;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ModelEditorActivity extends AppCompatActivity {

    private static final String TAG = "ModelEditor";

    private static final String PREFS_DARK_THEME = "dark_theme";

    private int COLOR_KEYWORD;
    private int COLOR_TYPE;
    private int COLOR_CONSTANT;
    private int COLOR_STRING;
    private int COLOR_COMMENT;
    private int COLOR_NUMBER;
    private int COLOR_FUNCTION;
    private int COLOR_DEFAULT;
    private int COLOR_LINE_NUMBER;
    private int COLOR_LINE_NUMBER_BG;
    private int COLOR_EDITOR_BG;
    private int COLOR_TOOLBAR_BG;
    private int COLOR_TOOLBAR_TEXT;
    private int COLOR_STATUS_BG;
    private int COLOR_STATUS_TEXT;
    private int COLOR_LANG;
    private int COLOR_BTN_BG;
    private int COLOR_BTN_TEXT;
    private int COLOR_BTN_STROKE;

    // GAML syntax patterns
    private static final Pattern PATTERN_KEYWORD = Pattern.compile(
            "\\b(model|species|grid|experiment|reflex|action|rule|output|init|global|test|" +
            "when|if|else|loop|ask|create|die|move|turn|release|capture|restore|write|save|" +
            "return|break|continue|while|for|each|in|as|of|from|to|step|every|" +
            "assert|error|warning|note|debug|info|" +
            "not|and|or|xor|" +
            "aspect|parent|definition|skills|location|text|" +
            "parameter|returns|type|returns|let|var|" +
            "schedule|status|update|constant|value|" +
            "equation|solve|diffuse|" +
            "state|enter|exit|on_match|" +
            "invalidate|message|condition|" +
            "species|of|parent|children|host|" +
            "using|neighbors|mesh|with_agent_type|" +
            "field|overlay|agate|draw|" +
            "light|camera|" +
            "data|image|file|database|" +
            "menu|button|slider|" +
            "inspect|ask|tell|do|kill|create|" +
            "match|switch|try|catch|throw|" +
            "abs|acos|asin|atan|atan2|ceil|cos|exp|floor|ln|log|max|min|mod|round|sin|sqrt|tan|" +
            "length|empty|contains|copy|reverse|sort|among|first|last|one_of|n_of|" +
            "shuffle|where|collect|accumulate|all_match|any_match|none_match|count|" +
            "mean|variance|std_dev|min_of|max_of|sum_of|product_of|" +
            "remove|add|at|index_of|sort_by|group_by|aggregat|" +
            "self|myself|world|nil|pi|e|" +
            "true|false)\\b");

    private static final Pattern PATTERN_STRING = Pattern.compile("\"[^\"]*\"|'[^']*'");
    private static final Pattern PATTERN_COMMENT_LINE = Pattern.compile("//[^\n]*");
    private static final Pattern PATTERN_COMMENT_BLOCK = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    private static final Pattern PATTERN_NUMBER = Pattern.compile("\\b\\d+\\.?\\d*([eE][+-]?\\d+)?\\b");
    private static final Pattern PATTERN_FUNCTION = Pattern.compile("\\b([a-zA-Z_]\\w*)\\s*(?=\\()");

    private EditText codeEditor;
    private TextView lineNumbers;
    private MaterialToolbar toolbar;
    private LinearProgressIndicator progressBar;
    private String modelName;
    private String jarPath;
    private boolean fromLibrary;
    private String filePath;
    private String currentContent = "";
    private boolean isModified = false;
    private boolean isLoading = true;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private MaterialButton runBtn;
    private MaterialButton saveBtn;
    private TextView themeToggle;
    private TextView backBtn;
    private TextView titleText;
    private TextView langLabel;
    private TextView statusInfo;
    private LinearLayout statusBar;
    private boolean isDarkTheme = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        isDarkTheme = getSharedPreferences("gama_prefs", 0).getBoolean(PREFS_DARK_THEME, true);
        setTheme(isDarkTheme ? R.style.Theme_GamaNative_Dark : R.style.Theme_GamaNative);
        super.onCreate(savedInstanceState);
        setGuiActivity(this);
        applyEditorTheme(isDarkTheme);

        modelName = getIntent().getStringExtra("model_name");
        jarPath = getIntent().getStringExtra("jar_path");
        fromLibrary = getIntent().getBooleanExtra("from_library", false);
        filePath = getIntent().getStringExtra("file_path");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setFitsSystemWindows(true);

        toolbar = new MaterialToolbar(this);
        toolbar.setBackgroundTintList(ColorStateList.valueOf(COLOR_TOOLBAR_BG));
        toolbar.setTitleTextColor(COLOR_TOOLBAR_TEXT);
        toolbar.setSubtitleTextColor(0xB3FFFFFF);
        toolbar.setNavigationIcon(null);

        LinearLayout toolbarContent = new LinearLayout(toolbar.getContext());
        toolbarContent.setOrientation(LinearLayout.HORIZONTAL);
        toolbarContent.setGravity(Gravity.CENTER_VERTICAL);
        toolbarContent.setPadding(dp(4), 0, dp(8), 0);

        backBtn = new TextView(this);
        backBtn.setText("\u2190 Back");
        backBtn.setTextSize(14);
        backBtn.setTextColor(Color.WHITE);
        backBtn.setTypeface(null, Typeface.BOLD);
        backBtn.setGravity(Gravity.CENTER);
        backBtn.setPadding(dp(12), dp(6), dp(12), dp(6));
        backBtn.setMinWidth(0);
        backBtn.setMinHeight(0);
        {
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
            bg.setCornerRadius(dp(8));
            bg.setColor(0xFF2196F3);
            backBtn.setBackground(bg);
        }
        backBtn.setOnClickListener(v -> onBackPressed());
        toolbarContent.addView(backBtn);

        titleText = new TextView(toolbar.getContext());
        titleText.setText(modelName != null ? modelName : "Editor");
        titleText.setTextSize(16);
        titleText.setTextColor(COLOR_TOOLBAR_TEXT);
        titleText.setTypeface(null, Typeface.BOLD);
        titleText.setPadding(dp(8), 0, 0, 0);
        titleText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        toolbarContent.addView(titleText);

        progressBar = new LinearProgressIndicator(toolbar.getContext());
        progressBar.setIndeterminate(true);
        progressBar.setTrackThickness(dp(2));
        progressBar.setVisibility(View.GONE);
        progressBar.setLayoutParams(new LinearLayout.LayoutParams(dp(20), dp(20)));
        toolbarContent.addView(progressBar);

        saveBtn = new MaterialButton(toolbar.getContext());
        saveBtn.setText("Save");
        saveBtn.setTextSize(12);
        saveBtn.setTypeface(null, Typeface.BOLD);
        saveBtn.setBackgroundTintList(ColorStateList.valueOf(COLOR_BTN_BG));
        saveBtn.setStrokeColor(ColorStateList.valueOf(COLOR_BTN_STROKE));
        saveBtn.setStrokeWidth(dp(1));
        saveBtn.setCornerRadius(dp(16));
        saveBtn.setTextColor(COLOR_BTN_TEXT);
        saveBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
        saveBtn.setMinimumHeight(0);
        saveBtn.setMinimumWidth(0);
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
        saveLp.setMargins(dp(4), 0, dp(4), 0);
        saveBtn.setLayoutParams(saveLp);
        saveBtn.setOnClickListener(v -> saveFile());
        toolbarContent.addView(saveBtn);

        runBtn = new MaterialButton(toolbar.getContext());
        runBtn.setText("Run");
        runBtn.setTextSize(12);
        runBtn.setTypeface(null, Typeface.BOLD);
        runBtn.setBackgroundTintList(ColorStateList.valueOf(0xFF4CAF50));
        runBtn.setCornerRadius(dp(16));
        runBtn.setTextColor(0xFFFFFFFF);
        runBtn.setPadding(dp(16), dp(4), dp(16), dp(4));
        runBtn.setMinimumHeight(0);
        runBtn.setMinimumWidth(0);
        runBtn.setIcon(ContextCompat.getDrawable(this, R.drawable.ic_play));
        LinearLayout.LayoutParams runLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, dp(32));
        runLp.setMargins(dp(4), 0, 0, 0);
        runBtn.setLayoutParams(runLp);
        runBtn.setOnClickListener(v -> runModel());
        toolbarContent.addView(runBtn);

        toolbar.addView(toolbarContent);
        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout editorArea = new LinearLayout(this);
        editorArea.setOrientation(LinearLayout.HORIZONTAL);
        editorArea.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        lineNumbers = new TextView(this);
        lineNumbers.setBackgroundColor(COLOR_LINE_NUMBER_BG);
        lineNumbers.setTextColor(COLOR_LINE_NUMBER);
        lineNumbers.setTextSize(12);
        lineNumbers.setTypeface(Typeface.MONOSPACE);
        lineNumbers.setPadding(dp(8), dp(12), dp(4), dp(12));
        lineNumbers.setGravity(Gravity.TOP | Gravity.END);
        lineNumbers.setText("1\n");
        editorArea.addView(lineNumbers, new LinearLayout.LayoutParams(dp(42), LinearLayout.LayoutParams.MATCH_PARENT));

        codeEditor = new EditText(this) {
            @Override
            protected void onScrollChanged(int horiz, int vert, int oldHoriz, int oldVert) {
                super.onScrollChanged(horiz, vert, oldHoriz, oldVert);
                lineNumbers.scrollTo(0, vert);
            }
        };
        codeEditor.setBackgroundColor(COLOR_EDITOR_BG);
        codeEditor.setTextColor(COLOR_DEFAULT);
        codeEditor.setTextSize(13);
        codeEditor.setTypeface(Typeface.MONOSPACE);
        codeEditor.setPadding(dp(8), dp(12), dp(8), dp(12));
        codeEditor.setGravity(Gravity.TOP | Gravity.START);
        codeEditor.setHorizontallyScrolling(false);
        codeEditor.setVerticalScrollBarEnabled(true);
        codeEditor.setHorizontalScrollBarEnabled(true);
        // Normal text keyboard for code: no CAP_CHARACTERS (that makes the IME
        // stay in caps lock), no auto-correct/suggestions that would mangle GAML.
        codeEditor.setRawInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        codeEditor.setTextIsSelectable(true);

        editorArea.addView(codeEditor, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        root.addView(editorArea);

        statusBar = new LinearLayout(this);
        statusBar.setOrientation(LinearLayout.HORIZONTAL);
        statusBar.setPadding(dp(12), dp(4), dp(12), dp(4));
        statusBar.setBackgroundColor(COLOR_STATUS_BG);

        langLabel = new TextView(this);
        langLabel.setText("GAML");
        langLabel.setTextSize(11);
        langLabel.setTextColor(COLOR_LANG);
        langLabel.setTypeface(null, Typeface.BOLD);
        statusBar.addView(langLabel);

        statusInfo = new TextView(this);
        statusInfo.setId(android.R.id.text1);
        statusInfo.setText("Ready");
        statusInfo.setTextSize(11);
        statusInfo.setTextColor(COLOR_STATUS_TEXT);
        statusInfo.setPadding(dp(16), 0, 0, 0);
        statusInfo.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        statusBar.addView(statusInfo);

        themeToggle = new TextView(this);
        themeToggle.setText(isDarkTheme ? "\u2600" : "\u263E");
        themeToggle.setTextSize(16);
        themeToggle.setTextColor(COLOR_STATUS_TEXT);
        themeToggle.setPadding(dp(12), 0, dp(12), 0);
        themeToggle.setOnClickListener(v -> {
            isDarkTheme = !isDarkTheme;
            getSharedPreferences("gama_prefs", 0).edit().putBoolean(PREFS_DARK_THEME, isDarkTheme).apply();
            applyEditorTheme(isDarkTheme);
        });
        statusBar.addView(themeToggle);

        root.addView(statusBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        setContentView(root);

        codeEditor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                if (isLoading) return;
                if (!s.toString().equals(currentContent)) {
                    isModified = true;
                    titleText.setText(modelName + "  \u00B7  modified");
                }
                highlightSyntax(s);
                updateLineNumbers();
                updateStatusInfo(s.toString());
            }
        });

        loadFile();
    }

    private void updateStatusInfo(String text) {
        int lines = 0;
        int chars = text.length();
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        if (statusInfo != null) {
            statusInfo.setText(lines + " lines  \u00B7  " + chars + " chars");
        }
    }

    private void applyEditorTheme(boolean dark) {
        if (dark) {
            COLOR_KEYWORD = 0xFF569CD6;
            COLOR_TYPE = 0xFF4EC9B0;
            COLOR_CONSTANT = 0xFF569CD6;
            COLOR_STRING = 0xFFCE9178;
            COLOR_COMMENT = 0xFF6A9955;
            COLOR_NUMBER = 0xFFB5CEA8;
            COLOR_FUNCTION = 0xFFDCDCAA;
            COLOR_DEFAULT = 0xFFD4D4D4;
            COLOR_LINE_NUMBER = 0xFF858585;
            COLOR_LINE_NUMBER_BG = 0xFF2D2D2D;
            COLOR_EDITOR_BG = 0xFF1E1E1E;
            COLOR_TOOLBAR_BG = 0xFF2D2D2D;
            COLOR_TOOLBAR_TEXT = 0xFFFFFFFF;
            COLOR_STATUS_BG = 0xFF252526;
            COLOR_STATUS_TEXT = 0xFF888888;
            COLOR_LANG = 0xFF4CAF50;
            COLOR_BTN_BG = 0xFF444444;
            COLOR_BTN_TEXT = 0xFF90CAF9;
            COLOR_BTN_STROKE = 0xFF666666;
        } else {
            COLOR_KEYWORD = 0xFF0000FF;
            COLOR_TYPE = 0xFF267F99;
            COLOR_CONSTANT = 0xFF0000FF;
            COLOR_STRING = 0xFF008000;
            COLOR_COMMENT = 0xFF808080;
            COLOR_NUMBER = 0xFF0000C0;
            COLOR_FUNCTION = 0xFF795E26;
            COLOR_DEFAULT = 0xFF000000;
            COLOR_LINE_NUMBER = 0xFF808080;
            COLOR_LINE_NUMBER_BG = 0xFFF0F0F0;
            COLOR_EDITOR_BG = 0xFFFFFFFF;
            COLOR_TOOLBAR_BG = 0xFFF0F0F0;
            COLOR_TOOLBAR_TEXT = 0xFF333333;
            COLOR_STATUS_BG = 0xFFE8E8E8;
            COLOR_STATUS_TEXT = 0xFF666666;
            COLOR_LANG = 0xFF006847;
            COLOR_BTN_BG = 0xFFE0E0E0;
            COLOR_BTN_TEXT = 0xFF1565C0;
            COLOR_BTN_STROKE = 0xFFBBBBBB;
        }
        getWindow().getDecorView().setBackgroundColor(COLOR_EDITOR_BG);
        getWindow().setStatusBarColor(COLOR_TOOLBAR_BG);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            int flags = getWindow().getDecorView().getSystemUiVisibility();
            flags = dark ? (flags & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR) : (flags | View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
            getWindow().getDecorView().setSystemUiVisibility(flags);
        }
        if (toolbar != null) {
            toolbar.setBackgroundTintList(ColorStateList.valueOf(COLOR_TOOLBAR_BG));
            toolbar.setTitleTextColor(COLOR_TOOLBAR_TEXT);
            titleText.setTextColor(COLOR_TOOLBAR_TEXT);
            saveBtn.setBackgroundTintList(ColorStateList.valueOf(COLOR_BTN_BG));
            saveBtn.setStrokeColor(ColorStateList.valueOf(COLOR_BTN_STROKE));
            saveBtn.setTextColor(COLOR_BTN_TEXT);
        }
        if (lineNumbers != null) {
            lineNumbers.setBackgroundColor(COLOR_LINE_NUMBER_BG);
            lineNumbers.setTextColor(COLOR_LINE_NUMBER);
        }
        if (codeEditor != null) {
            codeEditor.setBackgroundColor(COLOR_EDITOR_BG);
            codeEditor.setTextColor(COLOR_DEFAULT);
        }
        if (statusBar != null) {
            statusBar.setBackgroundColor(COLOR_STATUS_BG);
            langLabel.setTextColor(COLOR_LANG);
            statusInfo.setTextColor(COLOR_STATUS_TEXT);
            themeToggle.setTextColor(COLOR_STATUS_TEXT);
            themeToggle.setText(isDarkTheme ? "\u2600" : "\u263E");
        }
        if (codeEditor != null && codeEditor.getEditableText().length() > 0) {
            highlightSyntax(codeEditor.getEditableText());
            updateLineNumbers();
        }
    }

    private void loadFile() {
        executor.execute(() -> {
            try {
                String content = null;

                if (filePath != null) {
                    File file = new File(filePath);
                    if (file.exists()) {
                        content = readFile(file);
                    }
                } else if (fromLibrary && jarPath != null) {
                    File cacheFile = new File(getCacheDir(), jarPath);
                    if (cacheFile.exists()) {
                        content = readFile(cacheFile);
                    } else {
                        content = readFromJar(jarPath);
                    }
                } else {
                    File internalFile = new File(getFilesDir(), "models/" + modelName + ".gaml");
                    if (internalFile.exists()) {
                        content = readFile(internalFile);
                    } else if (jarPath != null) {
                        content = readFromAssets(jarPath);
                    }
                }

                if (content == null) {
                    content = "// " + modelName + "\n// Model not found\n";
                }

                final String finalContent = content;
                mainHandler.post(() -> {
                    isLoading = true;
                    currentContent = finalContent;
                    isModified = false;
                    codeEditor.setText(finalContent);
                    codeEditor.setSelection(0);
                    highlightSyntax(codeEditor.getEditableText());
                    updateLineNumbers();
                    updateStatusInfo(finalContent);
                    isLoading = false;
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    Toast.makeText(this, "Error loading: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                    codeEditor.setText("// Error loading model: " + e.getMessage());
                });
            }
        });
    }

    private String readFromJar(String entryPath) {
        try {
            JarFile jarFile = findLibraryJar();
            if (jarFile == null) return null;
            JarEntry entry = jarFile.getJarEntry(entryPath);
            if (entry == null) { jarFile.close(); return null; }
            InputStream is = jarFile.getInputStream(entry);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) sb.append("\n");
                sb.append(line);
                first = false;
            }
            reader.close();
            is.close();
            jarFile.close();
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private String readFromAssets(String path) {
        try {
            InputStream is = getAssets().open(path);
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) sb.append("\n");
                sb.append(line);
                first = false;
            }
            reader.close();
            is.close();
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private String readFile(File file) {
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fis, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            boolean first = true;
            while ((line = reader.readLine()) != null) {
                if (!first) sb.append("\n");
                sb.append(line);
                first = false;
            }
            reader.close();
            fis.close();
            return sb.toString();
        } catch (Exception e) { return null; }
    }

    private JarFile findLibraryJar() {
        try {
            java.io.File cacheJar = LibraryJarUtil.ensureCached(this);
            if (cacheJar == null) return null;
            return new JarFile(cacheJar);
        } catch (Exception e) { return null; }
    }

    private void saveFile() {
        executor.execute(() -> {
            try {
                String content = codeEditor.getText().toString();

                if (filePath != null) {
                    WorkspaceManager.writeText(new File(filePath), content);
                } else if (fromLibrary && jarPath != null) {
                    File cacheFile = new File(getCacheDir(), jarPath);
                    WorkspaceManager.writeText(cacheFile, content);
                    mainHandler.post(() -> {
                        titleText.setText(modelName + "  \u00B7  library");
                        Toast.makeText(this, "Saved to library", Toast.LENGTH_SHORT).show();
                    });
                } else {
                    File modelsDir = new File(getFilesDir(), "models");
                    modelsDir.mkdirs();
                    File file = new File(modelsDir, modelName + ".gaml");
                    WorkspaceManager.writeText(file, content);
                    filePath = file.getAbsolutePath();
                }

                mainHandler.post(() -> {
                    isModified = false;
                    if (!(fromLibrary && jarPath != null)) {
                        Toast.makeText(this, "Saved successfully", Toast.LENGTH_SHORT).show();
                    }
                });
            } catch (Exception e) {
                mainHandler.post(() ->
                        Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void runModel() {
        try {
            Intent intent = new Intent(this, ExperimentActivity.class);
            intent.putExtra("model_name", modelName);

            if (fromLibrary && jarPath != null) {
                File cacheFile = new File(getCacheDir(), jarPath);
                cacheFile.getParentFile().mkdirs();
                FileOutputStream fos = new FileOutputStream(cacheFile);
                fos.write(codeEditor.getText().toString().getBytes("UTF-8"));
                fos.close();
                isModified = false;
                intent.putExtra("jar_path", jarPath);
                intent.putExtra("from_library", true);
            } else if (filePath != null) {
                WorkspaceManager.writeText(new File(filePath), codeEditor.getText().toString());
                isModified = false;
                intent.putExtra("file_path", filePath);
            } else {
                File modelsDir = new File(getFilesDir(), "models");
                modelsDir.mkdirs();
                File file = new File(modelsDir, modelName + ".gaml");
                FileOutputStream fos = new FileOutputStream(file);
                fos.write(codeEditor.getText().toString().getBytes("UTF-8"));
                fos.close();
                isModified = false;
                intent.putExtra("file_path", file.getAbsolutePath());
            }
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Save failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private static final int MAX_HIGHLIGHT_LENGTH = 50000;

    private void highlightSyntax(Editable text) {
        if (text.length() > MAX_HIGHLIGHT_LENGTH) return;

        ForegroundColorSpan[] spans = text.getSpans(0, text.length(), ForegroundColorSpan.class);
        for (ForegroundColorSpan span : spans) text.removeSpan(span);
        StyleSpan[] styleSpans = text.getSpans(0, text.length(), StyleSpan.class);
        for (StyleSpan span : styleSpans) text.removeSpan(span);

        String code = text.toString();

        Matcher commentBlock = PATTERN_COMMENT_BLOCK.matcher(code);
        while (commentBlock.find()) {
            text.setSpan(new ForegroundColorSpan(COLOR_COMMENT),
                    commentBlock.start(), commentBlock.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        Matcher commentLine = PATTERN_COMMENT_LINE.matcher(code);
        while (commentLine.find()) {
            boolean inBlock = false;
            Matcher blockChecker = PATTERN_COMMENT_BLOCK.matcher(code);
            while (blockChecker.find()) {
                if (blockChecker.start() <= commentLine.start() && blockChecker.end() >= commentLine.end()) {
                    inBlock = true; break;
                }
            }
            if (!inBlock) {
                text.setSpan(new ForegroundColorSpan(COLOR_COMMENT),
                        commentLine.start(), commentLine.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        Matcher stringMatch = PATTERN_STRING.matcher(code);
        while (stringMatch.find()) {
            boolean covered = false;
            ForegroundColorSpan[] existing = text.getSpans(stringMatch.start(), stringMatch.end(), ForegroundColorSpan.class);
            for (ForegroundColorSpan s : existing) {
                if (text.getSpanStart(s) <= stringMatch.start() && text.getSpanEnd(s) >= stringMatch.end()) {
                    covered = true; break;
                }
            }
            if (!covered) {
                text.setSpan(new ForegroundColorSpan(COLOR_STRING),
                        stringMatch.start(), stringMatch.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        Matcher numberMatch = PATTERN_NUMBER.matcher(code);
        while (numberMatch.find()) {
            boolean covered = false;
            ForegroundColorSpan[] existing = text.getSpans(numberMatch.start(), numberMatch.end(), ForegroundColorSpan.class);
            for (ForegroundColorSpan s : existing) {
                if (text.getSpanStart(s) <= numberMatch.start() && text.getSpanEnd(s) >= numberMatch.end()) {
                    covered = true; break;
                }
            }
            if (!covered) {
                text.setSpan(new ForegroundColorSpan(COLOR_NUMBER),
                        numberMatch.start(), numberMatch.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        Matcher keywordMatch = PATTERN_KEYWORD.matcher(code);
        while (keywordMatch.find()) {
            boolean covered = false;
            ForegroundColorSpan[] existing = text.getSpans(keywordMatch.start(), keywordMatch.end(), ForegroundColorSpan.class);
            for (ForegroundColorSpan s : existing) {
                if (text.getSpanStart(s) <= keywordMatch.start() && text.getSpanEnd(s) >= keywordMatch.end()) {
                    covered = true; break;
                }
            }
            if (!covered) {
                text.setSpan(new ForegroundColorSpan(COLOR_KEYWORD),
                        keywordMatch.start(), keywordMatch.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                text.setSpan(new StyleSpan(Typeface.BOLD),
                        keywordMatch.start(), keywordMatch.end(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }

        Matcher funcMatch = PATTERN_FUNCTION.matcher(code);
        while (funcMatch.find()) {
            boolean covered = false;
            ForegroundColorSpan[] existing = text.getSpans(funcMatch.start(), funcMatch.end(), ForegroundColorSpan.class);
            for (ForegroundColorSpan s : existing) {
                if (text.getSpanStart(s) <= funcMatch.start() && text.getSpanEnd(s) >= funcMatch.end()) {
                    covered = true; break;
                }
            }
            if (!covered) {
                String funcName = funcMatch.group(1);
                if (funcName != null && !PATTERN_KEYWORD.matcher(funcName).matches()) {
                    text.setSpan(new ForegroundColorSpan(COLOR_FUNCTION),
                            funcMatch.start(1), funcMatch.end(1), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
    }

    private void updateLineNumbers() {
        String text = codeEditor.getText().toString();
        int lines = 1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') lines++;
        }
        StringBuilder sb = new StringBuilder(lines * 4);
        for (int i = 1; i <= lines; i++) {
            sb.append(i).append('\n');
        }
        lineNumbers.setText(sb.toString());
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                value, getResources().getDisplayMetrics());
    }

    private static void setGuiActivity(ModelEditorActivity activity) {
        try {
            Class<?> handlerClass = Class.forName("com.gama.nativeapp.gui.AndroidGuiHandler");
            handlerClass.getMethod("setActivity", android.app.Activity.class).invoke(null, activity);
        } catch (Throwable e) { /* ignore */ }
    }

    @Override
    protected void onResume() {
        super.onResume();
        setGuiActivity(this);
    }

    @Override
    public void onBackPressed() {
        if (isModified) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Unsaved Changes")
                    .setMessage("Save changes before closing?")
                    .setPositiveButton("Save", (d, w) -> { saveFile(); finish(); })
                    .setNegativeButton("Discard", (d, w) -> finish())
                    .setNeutralButton("Cancel", null)
                    .show();
        } else {
            super.onBackPressed();
        }
    }

}
