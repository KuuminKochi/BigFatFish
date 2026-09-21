package io.github.kuuminkochi.bigfatfish;

/**
 * Pure state machine and damped pendulum for the pointer companion.
 *
 * <p>The model has no clock of its own: callers provide monotonic milliseconds to
 * {@link #onPointer(float, float, int, boolean, long)} and {@link #advance(long)}.
 * This keeps it deterministic and straightforward to exercise without Android.</p>
 */
public final class AnimationModel {
    public static final int ACTIVE = 0;
    public static final int SLEEP = 1;
    public static final int REACT = 2;

    private static final long DEFAULT_IDLE_DELAY_MS = 5_000L;
    private static final long MAX_REACTION_DURATION_MS = 120L * 2_000L * 4L;
    private static final long MAX_CLOCK_STEP_MS = 2_000L;
    private static final float MAX_ANGLE = 0.68f;
    private static final float SPRING_STIFFNESS = 28f;
    private static final float REST_ANGLE_EPSILON = 0.0025f;
    private static final float REST_VELOCITY_EPSILON = 0.0025f;

    private float strength = 1f;
    private float damping = 7f;
    private long idleDelayMs = DEFAULT_IDLE_DELAY_MS;
    private boolean sleepEnabled = true;
    private int reactionMask = -1;
    private long reactionDurationMs = 1L;

    private int mode;
    private int buttons;
    private long lastActivityMs;
    private long lastAdvanceMs;
    private long activeEpochMs;
    private long sleepEpochMs;
    private long reactionEpochMs;
    private long reactionUntilMs;
    private float angle;
    private float angularVelocity;

    public AnimationModel(long nowMs) {
        long now = safeTime(nowMs);
        mode = ACTIVE;
        lastActivityMs = now;
        lastAdvanceMs = now;
        activeEpochMs = now;
        sleepEpochMs = now;
        reactionEpochMs = now;
    }

    /** Applies and bounds motion/sleep/reaction settings. */
    public void configure(float strength, float damping, long idleDelayMs,
            boolean sleepEnabled, int reactionMask, long reactionDurationMs) {
        this.strength = finiteOr(strength, 1f);
        this.strength = clamp(this.strength, 0f, 2f);
        this.damping = finiteOr(damping, 7f);
        this.damping = clamp(this.damping, 2f, 16f);
        this.idleDelayMs = clamp(idleDelayMs, 1_000L, 60_000L);
        this.sleepEnabled = sleepEnabled;
        this.reactionMask = reactionMask;
        this.reactionDurationMs = clamp(reactionDurationMs, 1L, MAX_REACTION_DURATION_MS);
        if (!sleepEnabled && mode == SLEEP) {
            enterActive(lastAdvanceMs);
        }
        updateMode(lastAdvanceMs);
    }

    /**
     * Feeds one pointer observation. Activity means movement or wheel/button
     * activity as determined by the observer; button transitions are considered
     * activity as well so a missed activity flag cannot leave the model asleep.
     */
    public void onPointer(float dxDp, float dyDp, int buttons, boolean activity, long nowMs) {
        long now = safeTime(nowMs);
        advance(now);

        int previousButtons = this.buttons;
        this.buttons = buttons;
        int newlyPressed = buttons & ~previousButtons;
        boolean moved = finite(dxDp) && dxDp != 0f
                || finite(dyDp) && dyDp != 0f;
        boolean input = activity || moved || buttons != previousButtons;
        if (input) {
            lastActivityMs = now;
            if (mode == SLEEP) {
                enterActive(now);
            }
        }

        int reactionButtons = reactionMask == -1 ? newlyPressed : newlyPressed & reactionMask;
        if (reactionButtons != 0) {
            mode = REACT;
            reactionEpochMs = now;
            reactionUntilMs = saturatingAdd(now, reactionDurationMs);
        }

        // A horizontal observation is an impulse, not a continuously driven
        // target. Consequently a stationary pointer always settles to rest.
        if (finite(dxDp) && dxDp != 0f && strength > 0f) {
            float impulse = clamp(dxDp, -160f, 160f) * 0.0045f * strength;
            angularVelocity = clamp(angularVelocity + impulse, -4.5f, 4.5f);
            // Give the next draw an immediate, small displacement while the
            // velocity carries the physical swing through subsequent frames.
            angle = clamp(angle + impulse * 0.016f, -MAX_ANGLE, MAX_ANGLE);
        }
        updateMode(now);
    }

    /** Advances the damped pendulum and state machine to {@code nowMs}. */
    public void advance(long nowMs) {
        long now = safeTime(nowMs);
        if (now < lastAdvanceMs) {
            return;
        }
        long elapsed = now - lastAdvanceMs;
        // Huge callback gaps cannot produce an unstable leap. Simulate enough
        // physical time to settle, then let the exact rest snap below finish it.
        long simulationMs = Math.min(elapsed, MAX_CLOCK_STEP_MS);
        while (simulationMs > 0L) {
            float dt = Math.min(simulationMs, 16L) / 1000f;
            float acceleration = -SPRING_STIFFNESS * angle - damping * angularVelocity;
            angularVelocity += acceleration * dt;
            angle += angularVelocity * dt;
            angle = clamp(angle, -MAX_ANGLE, MAX_ANGLE);
            simulationMs -= Math.min(simulationMs, 16L);
        }
        if (Math.abs(angle) < REST_ANGLE_EPSILON
                && Math.abs(angularVelocity) < REST_VELOCITY_EPSILON) {
            angle = 0f;
            angularVelocity = 0f;
        }
        lastAdvanceMs = now;
        updateMode(now);
    }

    public int mode() {
        return mode;
    }

    /** Elapsed time in the current sprite clip, suitable for SpritePack.frameFor. */
    public long frameElapsedMs(long nowMs) {
        long now = safeTime(nowMs);
        long epoch = mode == REACT ? reactionEpochMs : mode == SLEEP ? sleepEpochMs : activeEpochMs;
        return Math.max(0L, now - epoch);
    }

    public float angleRadians() {
        return angle;
    }

    public boolean isAtRest() {
        return angle == 0f && angularVelocity == 0f;
    }

    private void updateMode(long now) {
        if (mode == REACT) {
            if (now < reactionUntilMs) {
                return;
            }
            enterActive(now);
        }
        if (buttons != 0) {
            if (mode == SLEEP) {
                enterActive(now);
            }
            return;
        }
        if (sleepEnabled && now - lastActivityMs >= idleDelayMs) {
            if (mode != SLEEP) {
                mode = SLEEP;
                sleepEpochMs = now;
            }
        } else if (mode == SLEEP) {
            // Only genuine input wakes sleep; this branch is reached when sleep
            // was disabled or settings changed, never from elapsed time alone.
            if (!sleepEnabled) {
                enterActive(now);
            }
        }
    }

    private void enterActive(long now) {
        mode = ACTIVE;
        // activeEpochMs intentionally remains unchanged: pointer movement must
        // not restart the active sprite loop.
        if (now > lastAdvanceMs) {
            lastAdvanceMs = now;
        }
    }

    private static long safeTime(long value) {
        return Math.max(0L, value);
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + Math.max(0L, right);
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static float finiteOr(float value, float fallback) {
        return finite(value) ? value : fallback;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
