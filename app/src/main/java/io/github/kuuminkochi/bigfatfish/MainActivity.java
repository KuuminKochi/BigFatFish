package io.github.kuuminkochi.bigfatfish;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Space;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** The small, native control surface for BigFatFish. */
public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(11, 36, 48);
    private static final int MIST = Color.rgb(233, 246, 246);
    private static final int TEAL = Color.rgb(8, 127, 140);
    private static final int GOLD = Color.rgb(241, 189, 88);
    private static final int WHITE = Color.WHITE;
    private static final int IMPORT_REQUEST = 41;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Runnable statusPoll = new Runnable() {
        @Override public void run() {
            if (!isFinishing()) {
                updateStatus();
                main.postDelayed(this, 1000L);
            }
        }
    };

    private LinearLayout content;
    private TextView status;
    private TextView artworkInfo;
    private Preview preview;
    private SpritePack activePack;
    private SettingsStore.Config config;
    private boolean destroyed;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.setStatusBarColor(INK);
        window.setNavigationBarColor(INK);
        config = SettingsStore.read(this);
        buildUi();
        window.getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
            int types = WindowInsets.Type.systemBars();
            view.setPadding(view.getPaddingLeft(), insets.getInsets(types).top,
                    view.getPaddingRight(), insets.getInsets(types).bottom);
            return insets;
        });
        loadPack(config.packId);
    }

    @Override protected void onResume() {
        super.onResume();
        config = SettingsStore.read(this);
        if (content != null) updateStatus();
        main.removeCallbacks(statusPoll);
        main.post(statusPoll);
    }

    @Override protected void onPause() {
        main.removeCallbacks(statusPoll);
        super.onPause();
    }

    @Override protected void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(MIST);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(18), dp(20), dp(26));
        scroll.addView(content, new ScrollView.LayoutParams(-1, -2));
        setContentView(scroll);

        TextView title = text("BigFatFish", 30, INK, true);
        content.addView(title, margins(0, 0, 0, 2));
        content.addView(text("Your animated cursor companion", 15, INK, false), margins(0, 0, 0, 14));

        FrameLayout statusCard = card();
        LinearLayout statusColumn = column();
        status = text("Checking setup…", 16, INK, true);
        statusColumn.addView(status);
        statusColumn.addView(text("Observes mouse position and buttons; never reads keyboard or screen content.", 13, INK, false), margins(0, 5, 0, 0));
        statusCard.addView(statusColumn, new FrameLayout.LayoutParams(-1, -2));
        content.addView(statusCard, margins(0, 0, 0, 10));

        LinearLayout actions = row();
        Button enable = button(SettingsStore.enabled(this) ? "Stop BigFatFish" : "Enable BigFatFish");
        enable.setOnClickListener(v -> {
            boolean next = !SettingsStore.enabled(this);
            CompanionService.setDesiredEnabled(this, next);
            refreshActionButton(enable);
            updateStatus();
        });
        actions.addView(enable, weight(1, 0, 0, 0));
        Button accessibility = button("Accessibility setup");
        accessibility.setOnClickListener(v -> openAccessibilitySettings());
        actions.addView(accessibility, weight(1, dp(8), 0, 0));
        content.addView(actions, margins(0, 0, 0, 18));

        preview = new Preview(this);
        content.addView(preview, new LinearLayout.LayoutParams(-1, dp(180)));
        content.addView(text("Preview uses the selected pack and updates after import.", 12, Color.DKGRAY, false), margins(0, 5, 0, 18));

        addHeading("Appearance");
        addSlider("Character size", "How large the character appears", 24f, 128f, config.sizeDp, value -> putFloat(SettingsStore.KEY_SIZE, value), "%.0f dp");
        addSlider("Thread length", "Distance from the pointer to the character", 0f, 120f, config.threadDp, value -> putFloat(SettingsStore.KEY_THREAD, value), "%.0f dp");
        addSwitch("Show thread", "Hide the line while keeping the character anchored", config.showThread, value -> putBoolean(SettingsStore.KEY_THREAD_VISIBLE, value));
        addResetAppearance();

        addHeading("Motion");
        addSlider("Swing strength", "How much movement swings the character", 0f, 2f, config.swingStrength, value -> putFloat(SettingsStore.KEY_STRENGTH, value), "%.2f");
        addSlider("Damping", "How quickly a swing settles", 2f, 16f, config.damping, value -> putFloat(SettingsStore.KEY_DAMPING, value), "%.1f");
        addSlider("Animation speed", "Playback speed of the character animation", .25f, 3f, config.animationSpeed, value -> putFloat(SettingsStore.KEY_SPEED, value), "%.2fx");
        addSwitch("Realistic physics", "Use gravity, momentum, and pendulum length in a rigid-pendulum approximation", config.realisticPhysics, value -> putBoolean(SettingsStore.KEY_REALISTIC_PHYSICS, value));
        addReactionChooser();

        addHeading("Idle");
        addSlider("Idle delay", "Time without mouse activity before sleeping or fading", 1000f, 60000f, config.idleDelayMs, value -> putLong(SettingsStore.KEY_IDLE, Math.round(value)), value -> formatDuration(Math.round(value)));
        addSwitch("Sleep when idle", "Use the sleeping animation after the idle delay", config.sleepEnabled, value -> putBoolean(SettingsStore.KEY_SLEEP, value));
        addSwitch("Fade when idle", "Fade after the idle delay without pausing motion", config.fadeWhenIdle, value -> putBoolean(SettingsStore.KEY_FADE_IDLE, value));
        addSlider("Active opacity", "Opacity while active", 0f, 1f, config.activeOpacity, value -> putFloat(SettingsStore.KEY_ACTIVE_OPACITY, value), value -> formatPercent(value));
        addSlider("Idle opacity", "Opacity after the idle delay", 0f, 1f, config.idleOpacity, value -> putFloat(SettingsStore.KEY_IDLE_OPACITY, value), value -> formatPercent(value));

        addHeading("Artwork");
        LinearLayout artActions = row();
        Button importButton = button("Import artwork");
        importButton.setOnClickListener(v -> choosePack());
        artActions.addView(importButton, weight(1, 0, 0, 0));
        Button builtin = button("Use built-in");
        builtin.setOnClickListener(v -> selectBuiltin());
        artActions.addView(builtin, weight(1, dp(8), 0, 0));
        content.addView(artActions, margins(0, 0, 0, 6));
        artworkInfo = text("Loading artwork…", 14, INK, false);
        content.addView(artworkInfo, margins(0, 0, 0, 16));

        addHeading("Help & setup");
        Button guide = button("Open setup guide");
        guide.setOnClickListener(v -> showGuide());
        content.addView(guide, margins(0, 0, 0, 8));
        TextView privacy = text("No network permission is needed. BigFatFish uses a local helper and an Android accessibility service for passive mouse observation.", 13, INK, false);
        content.addView(privacy, margins(0, 0, 0, 4));
    }

    private void refreshActionButton(Button button) {
        button.setText(SettingsStore.enabled(this) ? "Stop BigFatFish" : "Enable BigFatFish");
    }

    private void addHeading(String value) {
        TextView heading = text(value, 21, TEAL, true);
        content.addView(heading, margins(0, 10, 0, 6));
    }

    private void addSlider(String label, String explanation, float min, float max, float initial,
                           FloatChange change, String format) {
        addSlider(label, explanation, min, max, initial, change, value -> String.format(Locale.US, format, value));
    }

    private void addSlider(String label, String explanation, float min, float max, float initial,
                           FloatChange change, ValueLabel formatter) {
        LinearLayout group = column();
        LinearLayout line = row();
        TextView name = text(label, 16, INK, true);
        line.addView(name, weight(1, 0, 0, 0));
        TextView value = text(formatter.format(initial), 15, TEAL, true);
        line.addView(value, new LinearLayout.LayoutParams(-2, -2));
        group.addView(line);
        group.addView(text(explanation, 12, Color.DKGRAY, false), margins(0, 2, 0, 0));
        SeekBar bar = new SeekBar(this);
        bar.setMax(1000);
        bar.setProgress(Math.round((initial - min) * 1000f / (max - min)));
        bar.setContentDescription(label);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                float v = min + (max - min) * progress / 1000f;
                value.setText(formatter.format(v));
                if (fromUser) change.apply(v);
            }
            @Override public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        group.addView(bar, margins(0, 0, 0, 3));
        content.addView(group, margins(0, 0, 0, 11));
    }

    private void addSwitch(String label, String explanation, boolean initial, BooleanChange change) {
        LinearLayout group = column();
        LinearLayout line = row();
        TextView name = text(label, 16, INK, true);
        line.addView(name, weight(1, 0, 0, 0));
        Switch toggle = new Switch(this);
        toggle.setChecked(initial);
        toggle.setContentDescription(label);
        toggle.setOnCheckedChangeListener((button, checked) -> change.apply(checked));
        line.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        group.addView(line);
        group.addView(text(explanation, 12, Color.DKGRAY, false), margins(0, 0, 0, 0));
        content.addView(group, margins(0, 0, 0, 11));
    }

    private void addReactionChooser() {
        LinearLayout group = column();
        group.addView(text("Reaction", 16, INK, true));
        group.addView(text("Show a brief reaction for selected mouse buttons", 12, Color.DKGRAY, false), margins(0, 2, 0, 0));
        RadioGroup choices = new RadioGroup(this);
        choices.setOrientation(RadioGroup.VERTICAL);
        RadioButton any = radio("Any button", 1, -1);
        RadioButton middle = radio("Middle button only", 2, 4);
        RadioButton none = radio("Disabled", 3, 0);
        choices.addView(any);
        choices.addView(middle);
        choices.addView(none);
        if (config.reactionMask == 4) choices.check(middle.getId());
        else if (config.reactionMask == 0) choices.check(none.getId());
        else choices.check(any.getId());
        choices.setOnCheckedChangeListener((group1, checkedId) -> {
            View checked = group1.findViewById(checkedId);
            if (checked instanceof RadioButton) putInt(SettingsStore.KEY_REACTION, (Integer) checked.getTag());
        });
        group.addView(choices);
        content.addView(group, margins(0, 0, 0, 11));
    }

    private RadioButton radio(String label, int id, int value) {
        RadioButton button = new RadioButton(this);
        button.setId(View.generateViewId());
        button.setText(label);
        button.setTextColor(INK);
        button.setTextSize(14);
        button.setTag(value);
        return button;
    }

    private void addResetAppearance() {
        Button reset = button("Reset appearance");
        reset.setOnClickListener(v -> {
            SettingsStore.resetAppearance(this);
            config = SettingsStore.read(this);
            buildUi();
            loadPack(config.packId);
            Toast.makeText(this, "Appearance reset", Toast.LENGTH_SHORT).show();
        });
        content.addView(reset, margins(0, 0, 0, 5));
    }

    private void putFloat(String key, float value) {
        SettingsStore.preferences(this).edit().putFloat(key, value).apply();
        config = SettingsStore.read(this);
        if (preview != null) preview.invalidate();
    }

    private void putLong(String key, long value) {
        SettingsStore.preferences(this).edit().putLong(key, value).apply();
        config = SettingsStore.read(this);
    }

    private void putBoolean(String key, boolean value) {
        SettingsStore.preferences(this).edit().putBoolean(key, value).apply();
        config = SettingsStore.read(this);
    }

    private void putInt(String key, int value) {
        SettingsStore.preferences(this).edit().putInt(key, value).apply();
        config = SettingsStore.read(this);
    }

    private void choosePack() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/zip");
        try {
            startActivityForResult(intent, IMPORT_REQUEST);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker is available", Toast.LENGTH_LONG).show();
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != IMPORT_REQUEST || resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        Toast.makeText(this, "Importing artwork…", Toast.LENGTH_SHORT).show();
        Context appContext = getApplicationContext();
        worker.execute(() -> {
            try {
                String id = PackImporter.importPack(appContext, uri);
                main.post(() -> {
                    if (destroyed || isFinishing() || isDestroyed()) return;
                    config = SettingsStore.read(this);
                    loadPack(id);
                    Toast.makeText(this, "Artwork imported", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (destroyed || isFinishing() || isDestroyed()) return;
                    Toast.makeText(this, importError(e), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private String importError(Exception error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) message = "The artwork file could not be imported";
        return "Artwork not imported: " + message;
    }

    private void selectBuiltin() {
        SettingsStore.preferences(this).edit().putString(SettingsStore.KEY_PACK, "builtin").apply();
        config = SettingsStore.read(this);
        loadPack("builtin");
    }

    private void loadPack(String id) {
        if (destroyed || isFinishing() || isDestroyed()) return;
        Context appContext = getApplicationContext();
        worker.execute(() -> {
            try {
                SpritePack pack = SpritePack.load(appContext, id);
                main.post(() -> {
                    if (destroyed || isFinishing() || isDestroyed()) return;
                    SettingsStore.Config latest = SettingsStore.read(this);
                    if (!pack.id.equals(latest.packId)) {
                        loadPack(latest.packId);
                        return;
                    }
                    activePack = pack;
                    if (artworkInfo != null) artworkInfo.setText(pack.name + " — " + pack.author + "\n" + pack.license);
                    if (preview != null) {
                        preview.setPack(pack);
                        preview.invalidate();
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (destroyed || isFinishing() || isDestroyed()) return;
                    String selected = SettingsStore.read(this).packId;
                    if (!id.equals(selected)) {
                        loadPack(selected);
                        return;
                    }
                    if (artworkInfo != null) artworkInfo.setText("No valid artwork selected. Import a pack or use the built-in one.");
                    Toast.makeText(this, "Artwork unavailable: " + (e.getMessage() == null ? "invalid pack" : e.getMessage()), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void updateStatus() {
        if (status == null) return;
        boolean enabled = SettingsStore.enabled(this);
        boolean serviceBound = CompanionService.current != null;
        boolean helperReady = CompanionService.helper != null && CompanionService.helper.isBinderAlive();
        String state;
        if (!enabled) state = "Stopped — ready to enable";
        else if (!isAccessibilityEnabled()) state = "Setup needed — enable Accessibility service";
        else if (!helperReady) state = "Enabled — start the helper after reboot";
        else if (!serviceBound) state = "Service not running — turn BigFatFish off and on in Accessibility setup.";
        else state = CompanionService.status != null ? CompanionService.status
                : (CompanionService.running ? "Running — following your pointer" : "Starting BigFatFish…");
        status.setText(state);
    }

    private boolean isAccessibilityEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        android.content.ComponentName own = new android.content.ComponentName(this, CompanionService.class);
        for (String item : enabled.split(":")) {
            if (own.equals(android.content.ComponentName.unflattenFromString(item))) return true;
        }
        return false;
    }

    private void openAccessibilitySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "Accessibility settings are unavailable", Toast.LENGTH_LONG).show();
        }
    }

    private void showGuide() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("Setup guide")
                .setMessage("1. Enable BigFatFish in Android Accessibility settings.\n\n2. Start the authorized helper once after each reboot. The helper is needed because Android 16 restricts passive mouse observation.\n\n3. Return here and tap Enable. BigFatFish does not need root, does not inject input, and never reads keyboard or screen content.\n\nYour settings are remembered and the helper reconnects when Android binds the service. If your device marks it crashed, turn BigFatFish off and on in Accessibility settings. This app never rewrites the global accessibility-permission list in the background.")
                .setPositiveButton("Accessibility settings", (dialog, which) -> openAccessibilitySettings())
                .setNegativeButton("Close", null)
                .show();
    }

    private FrameLayout card() {
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(WHITE);
        frame.setPadding(dp(15), dp(13), dp(15), dp(13));
        return frame;
    }

    private LinearLayout column() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.VERTICAL);
        return value;
    }

    private LinearLayout row() {
        LinearLayout value = new LinearLayout(this);
        value.setOrientation(LinearLayout.HORIZONTAL);
        value.setGravity(Gravity.CENTER_VERTICAL);
        return value;
    }

    private Button button(String label) {
        Button value = new Button(this);
        value.setText(label);
        value.setTextColor(INK);
        value.setAllCaps(false);
        value.setMinHeight(dp(44));
        return value;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView result = new TextView(this);
        result.setText(value);
        result.setTextSize(size);
        result.setTextColor(color);
        result.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        return result;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams weight(float weight, int left, int top, int right) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, weight);
        params.setMargins(left, top, right, 0);
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String formatDuration(long ms) {
        return ms >= 1000 && ms % 1000 == 0 ? (ms / 1000) + " s" : ms + " ms";
    }
    private String formatPercent(float value) {
        return String.format(Locale.US, "%.0f%%", value * 100f);
    }

    private interface FloatChange { void apply(float value); }
    private interface BooleanChange { void apply(boolean value); }
    private interface ValueLabel { String format(float value); }

    private final class Preview extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.RectF bounds = new android.graphics.RectF();
        private String previewError;
        private SpritePack pack;
        private Bitmap frame;
        private long started;

        Preview(Context context) {
            super(context);
            setBackgroundColor(INK);
            paint.setFilterBitmap(false);
            started = android.os.SystemClock.uptimeMillis();
            setContentDescription("BigFatFish artwork preview");
        }

        void setPack(SpritePack value) {
            pack = value;
            frame = null;
            previewError = null;
            started = android.os.SystemClock.uptimeMillis();
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (pack == null) {
                paint.setColor(MIST);
                paint.setTextSize(dp(15));
                canvas.drawText("Loading character…", dp(16), getHeight() / 2f, paint);
                return;
            }
            if (previewError != null) {
                paint.setColor(MIST);
                paint.setTextSize(dp(14));
                canvas.drawText("Artwork preview unavailable", dp(16), getHeight() / 2f, paint);
                return;
            }
            long elapsed = android.os.SystemClock.uptimeMillis() - started;
            try {
                frame = pack.frameFor(SpritePack.ACTIVE, elapsed, config.animationSpeed);
            } catch (RuntimeException error) {
                previewError = error.getMessage();
                if (previewError == null) previewError = error.getClass().getSimpleName();
                invalidate();
                return;
            }
            paint.setColor(GOLD);
            float x = getWidth() / 2f;
            float maxW = getWidth() * .42f;
            float maxH = getHeight() * .62f;
            float scale = Math.min(maxW / frame.getWidth(), maxH / frame.getHeight());
            float w = frame.getWidth() * scale;
            float h = frame.getHeight() * scale;
            float top = (getHeight() - h) * .58f;
            float left = x - pack.attachmentX * w;
            if (config.showThread) canvas.drawRect(x - 1f, 0, x + 1f, top + pack.attachmentY * h, paint);
            bounds.set(left, top, left + w, top + h);
            canvas.drawBitmap(frame, null, bounds, paint);
            postInvalidateDelayed(33L);
        }
    }
}
