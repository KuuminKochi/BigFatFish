package io.github.kuuminkochi.bigfatfish;

import android.content.Context;
import android.content.SharedPreferences;

/** Persistent user intent and appearance settings for BigFatFish. */
public final class SettingsStore {
    public static final String KEY_ENABLED = "enabled";
    public static final String KEY_PACK = "pack_id";
    public static final String KEY_SIZE = "size_dp";
    public static final String KEY_THREAD = "thread_dp";
    public static final String KEY_STRENGTH = "swing_strength";
    public static final String KEY_DAMPING = "damping";
    public static final String KEY_SPEED = "animation_speed";
    public static final String KEY_IDLE = "idle_delay_ms";
    public static final String KEY_SLEEP = "sleep_enabled";
    public static final String KEY_THREAD_VISIBLE = "show_thread";
    public static final String KEY_REACTION = "reaction_mask";
    public static final String KEY_REALISTIC_PHYSICS = "realistic_physics";
    public static final String KEY_FADE_IDLE = "fade_when_idle";
    public static final String KEY_ACTIVE_OPACITY = "active_opacity";
    public static final String KEY_IDLE_OPACITY = "idle_opacity";

    private static final float DEFAULT_SIZE = 56f;
    private static final float DEFAULT_THREAD = 32f;
    private static final float DEFAULT_STRENGTH = 1f;
    private static final float DEFAULT_DAMPING = 7f;
    private static final float DEFAULT_SPEED = 1f;
    private static final long DEFAULT_IDLE = 5000L;
    private static final String DEFAULT_PACK = "builtin";
    private static final boolean DEFAULT_REALISTIC_PHYSICS = false;
    private static final boolean DEFAULT_FADE_IDLE = false;
    private static final float DEFAULT_ACTIVE_OPACITY = 1f;
    private static final float DEFAULT_IDLE_OPACITY = .25f;

    private SettingsStore() { }

    public static SharedPreferences preferences(Context context) {
        return context.getApplicationContext().getSharedPreferences(AppIdentity.PREFS, Context.MODE_PRIVATE);
    }

    public static boolean enabled(Context context) {
        return preferences(context).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context context, boolean enabled) {
        preferences(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** Reset only appearance and behavior knobs; user intent and selected artwork survive. */
    public static void resetAppearance(Context context) {
        preferences(context).edit()
                .remove(KEY_SIZE)
                .remove(KEY_THREAD)
                .remove(KEY_STRENGTH)
                .remove(KEY_DAMPING)
                .remove(KEY_SPEED)
                .remove(KEY_IDLE)
                .remove(KEY_SLEEP)
                .remove(KEY_THREAD_VISIBLE)
                .remove(KEY_REACTION)
                .remove(KEY_REALISTIC_PHYSICS)
                .remove(KEY_FADE_IDLE)
                .remove(KEY_ACTIVE_OPACITY)
                .remove(KEY_IDLE_OPACITY)
                .apply();
    }

    public static Config read(Context context) {
        SharedPreferences p = preferences(context);
        return new Config(
                finiteFloat(p, KEY_SIZE, DEFAULT_SIZE, 24f, 128f),
                finiteFloat(p, KEY_THREAD, DEFAULT_THREAD, 0f, 120f),
                finiteFloat(p, KEY_STRENGTH, DEFAULT_STRENGTH, 0f, 2f),
                finiteFloat(p, KEY_DAMPING, DEFAULT_DAMPING, 2f, 16f),
                finiteFloat(p, KEY_SPEED, DEFAULT_SPEED, .25f, 3f),
                boundedLong(p, KEY_IDLE, DEFAULT_IDLE, 1000L, 60000L),
                getBoolean(p, KEY_SLEEP, true),
                getBoolean(p, KEY_THREAD_VISIBLE, true),
                boundedReaction(p),
                packId(p),
                getBoolean(p, KEY_REALISTIC_PHYSICS, DEFAULT_REALISTIC_PHYSICS),
                getBoolean(p, KEY_FADE_IDLE, DEFAULT_FADE_IDLE),
                finiteFloat(p, KEY_ACTIVE_OPACITY, DEFAULT_ACTIVE_OPACITY, 0f, 1f),
                finiteFloat(p, KEY_IDLE_OPACITY, DEFAULT_IDLE_OPACITY, 0f, 1f));
    }

    private static float finiteFloat(SharedPreferences p, String key, float fallback, float min, float max) {
        float value;
        try {
            value = p.getFloat(key, fallback);
        } catch (ClassCastException ignored) {
            return fallback;
        }
        if (!Float.isFinite(value)) return fallback;
        return Math.max(min, Math.min(max, value));
    }

    private static long boundedLong(SharedPreferences p, String key, long fallback, long min, long max) {
        long value;
        try {
            value = p.getLong(key, fallback);
        } catch (ClassCastException ignored) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value));
    }

    private static boolean getBoolean(SharedPreferences p, String key, boolean fallback) {
        try {
            return p.getBoolean(key, fallback);
        } catch (ClassCastException ignored) {
            return fallback;
        }
    }

    private static int boundedReaction(SharedPreferences p) {
        int value;
        try {
            value = p.getInt(KEY_REACTION, -1);
        } catch (ClassCastException ignored) {
            return -1;
        }
        return value == -1 || value == 4 || value == 0 ? value : -1;
    }

    private static String packId(SharedPreferences p) {
        String value;
        try {
            value = p.getString(KEY_PACK, DEFAULT_PACK);
        } catch (ClassCastException ignored) {
            return DEFAULT_PACK;
        }
        return value == null || value.isEmpty() ? DEFAULT_PACK : value;
    }

    public static final class Config {
        public final float sizeDp;
        public final float threadDp;
        public final float swingStrength;
        public final float damping;
        public final float animationSpeed;
        public final long idleDelayMs;
        public final boolean sleepEnabled;
        public final boolean showThread;
        public final int reactionMask;
        public final String packId;
        public final boolean realisticPhysics;
        public final boolean fadeWhenIdle;
        public final float activeOpacity;
        public final float idleOpacity;

        public Config(float sizeDp, float threadDp, float swingStrength, float damping,
                      float animationSpeed, long idleDelayMs, boolean sleepEnabled,
                      boolean showThread, int reactionMask, String packId,
                      boolean realisticPhysics, boolean fadeWhenIdle,
                      float activeOpacity, float idleOpacity) {
            this.sizeDp = clampFinite(sizeDp, DEFAULT_SIZE, 24f, 128f);
            this.threadDp = clampFinite(threadDp, DEFAULT_THREAD, 0f, 120f);
            this.swingStrength = clampFinite(swingStrength, DEFAULT_STRENGTH, 0f, 2f);
            this.damping = clampFinite(damping, DEFAULT_DAMPING, 2f, 16f);
            this.animationSpeed = clampFinite(animationSpeed, DEFAULT_SPEED, .25f, 3f);
            this.idleDelayMs = Math.max(1000L, Math.min(60000L, idleDelayMs));
            this.sleepEnabled = sleepEnabled;
            this.showThread = showThread;
            this.reactionMask = reactionMask == -1 || reactionMask == 4 || reactionMask == 0 ? reactionMask : -1;
            this.packId = packId == null || packId.isEmpty() ? DEFAULT_PACK : packId;
            this.realisticPhysics = realisticPhysics;
            this.fadeWhenIdle = fadeWhenIdle;
            this.activeOpacity = clampFinite(activeOpacity, DEFAULT_ACTIVE_OPACITY, 0f, 1f);
            this.idleOpacity = clampFinite(idleOpacity, DEFAULT_IDLE_OPACITY, 0f, 1f);
        }

        private static float clampFinite(float value, float fallback, float min, float max) {
            if (!Float.isFinite(value)) return fallback;
            return Math.max(min, Math.min(max, value));
        }
    }
}
