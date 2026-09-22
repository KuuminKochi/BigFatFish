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
    private static final long MAX_CLOCK_STEP_MS = 4_000L;
    private static final long OPACITY_FADE_MS = 350L;
    private static final long PIVOT_STOP_GRACE_MS = 50L;
    private static final float MAX_ANGLE = 0.68f;
    private static final float SPRING_STIFFNESS = 28f;
    private static final float REST_ANGLE_EPSILON = 0.0025f;
    private static final float REST_VELOCITY_EPSILON = 0.0025f;
    private static final float GRAVITY_DP_PER_SECOND_SQUARED = 980f;
    private static final float MAX_PIVOT_VELOCITY_DP_PER_SECOND = 2_400f;
    private static final float MAX_REALISTIC_ANGULAR_VELOCITY = 40f;

    private float strength = 1f;
    private float damping = 7f;
    private long idleDelayMs = DEFAULT_IDLE_DELAY_MS;
    private boolean sleepEnabled = true;
    private int reactionMask = -1;
    private long reactionDurationMs = 1L;
    private boolean realisticPhysics;
    private float physicsLengthDp = 1f;
    private boolean fadeWhenIdle;
    private float activeOpacity = 1f;
    private float idleOpacity = 0.25f;
    private float opacityTarget = 1f;
    private float opacityTransitionFrom = 1f;
    private long opacityTransitionStartMs;
    private float currentOpacity = 1f;
    private boolean opacityIdle;
    private boolean opacityConfigured;

    private int mode;
    private int buttons;
    private long lastActivityMs;
    private long lastAdvanceMs;
    private long activeEpochMs;
    private long sleepEpochMs;
    private long reactionEpochMs;
    private long reactionUntilMs;
    private long pointerSampleMs;
    private long pivotStopMs;
    private boolean pivotMoving;
    private float pointerVelocityX;
    private float pointerVelocityY;
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
        pointerSampleMs = now;
        opacityTransitionStartMs = now;
    }

    /** Applies and bounds motion/sleep/reaction settings. */
    public void configure(float strength, float damping, long idleDelayMs,
            boolean sleepEnabled, int reactionMask, long reactionDurationMs) {
        this.strength = clamp(finiteOr(strength, 1f), 0f, 2f);
        this.damping = clamp(finiteOr(damping, 7f), 2f, 16f);
        this.idleDelayMs = clamp(idleDelayMs, 1_000L, 60_000L);
        this.sleepEnabled = sleepEnabled;
        this.reactionMask = reactionMask;
        this.reactionDurationMs = clamp(reactionDurationMs, 1L, MAX_REACTION_DURATION_MS);
        if (!sleepEnabled && mode == SLEEP) {
            enterActive(lastAdvanceMs);
        }
        updateMode(lastAdvanceMs);
        updateOpacity(lastAdvanceMs, false);
    }

    /** Selects the classic spring or a nonlinear, rigid-pendulum approximation. */
    public void configurePhysics(boolean realistic, float lengthDp) {
        if (realisticPhysics != realistic) {
            pointerVelocityX = 0f;
            pointerVelocityY = 0f;
            pivotMoving = false;
            pointerSampleMs = lastAdvanceMs;
            if (!realistic) {
                angle = clamp(angle, -MAX_ANGLE, MAX_ANGLE);
                angularVelocity = clamp(angularVelocity, -4.5f, 4.5f);
            }
        }
        realisticPhysics = realistic;
        float length = finiteOr(lengthDp, 1f);
        // A point pivot with zero thread still has a finite character-sized length.
        physicsLengthDp = Math.max(1f, length);
    }

    /** Changes fading without resetting its current progress or the sleep state. */
    public void configureOpacity(boolean fadeWhenIdle, float activeOpacity, float idleOpacity) {
        updateOpacity(lastAdvanceMs, false);
        this.fadeWhenIdle = fadeWhenIdle;
        this.activeOpacity = clamp(finiteOr(activeOpacity, 1f), 0f, 1f);
        this.idleOpacity = clamp(finiteOr(idleOpacity, 0.25f), 0f, 1f);
        if (!opacityConfigured) {
            opacityConfigured = true;
            opacityTarget = this.activeOpacity;
            opacityTransitionFrom = this.activeOpacity;
            currentOpacity = this.activeOpacity;
            opacityTransitionStartMs = lastAdvanceMs;
        }
        updateOpacity(lastAdvanceMs, false);
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

        if (realisticPhysics) {
            applyPivotVelocityChange(dxDp, dyDp, now);
        } else if (finite(dxDp) && dxDp != 0f && strength > 0f) {
            // A horizontal observation is an impulse, not a continuously driven
            // target. Consequently a stationary pointer always settles to rest.
            float impulse = clamp(dxDp, -160f, 160f) * 0.0045f * strength;
            angularVelocity = clamp(angularVelocity + impulse, -4.5f, 4.5f);
            angle = clamp(angle + impulse * 0.016f, -MAX_ANGLE, MAX_ANGLE);
        }
        updateMode(now);
        updateOpacity(now, false);
    }

    /** Advances the damped pendulum and state machine to {@code nowMs}. */
    public void advance(long nowMs) {
        long now = safeTime(nowMs);
        if (now < lastAdvanceMs) {
            return;
        }
        long elapsed = now - lastAdvanceMs;
        // Bound both callback gaps and each integration step. A long gap is
        // intentionally not replayed frame-for-frame.
        long simulationMs = Math.min(elapsed, MAX_CLOCK_STEP_MS);
        long simulatedAt = lastAdvanceMs;
        while (simulationMs > 0L) {
            if (realisticPhysics && pivotMoving && simulatedAt >= pivotStopMs) {
                stopPivotAt(simulatedAt);
            }
            long stepMs = Math.min(simulationMs, 16L);
            if (realisticPhysics && pivotMoving && pivotStopMs > simulatedAt
                    && pivotStopMs < simulatedAt + stepMs) {
                stepMs = pivotStopMs - simulatedAt;
            }
            float dt = stepMs / 1000f;
            if (realisticPhysics) {
                float acceleration = -(GRAVITY_DP_PER_SECOND_SQUARED / physicsLengthDp)
                        * (float) Math.sin(angle) - damping * angularVelocity;
                angularVelocity = clamp(angularVelocity + acceleration * dt,
                        -MAX_REALISTIC_ANGULAR_VELOCITY, MAX_REALISTIC_ANGULAR_VELOCITY);
                angle += angularVelocity * dt;
                angle %= (float) (Math.PI * 2.0);
                if (angle > Math.PI) {
                    angle -= (float) (Math.PI * 2.0);
                } else if (angle < -Math.PI) {
                    angle += (float) (Math.PI * 2.0);
                }
            } else {
                float acceleration = -SPRING_STIFFNESS * angle - damping * angularVelocity;
                angularVelocity += acceleration * dt;
                angle = clamp(angle + angularVelocity * dt, -MAX_ANGLE, MAX_ANGLE);
            }
            simulatedAt += stepMs;
            simulationMs -= stepMs;
        }
        if (realisticPhysics && pivotMoving && simulatedAt >= pivotStopMs) {
            stopPivotAt(simulatedAt);
        }
        if (Math.abs(angle) < REST_ANGLE_EPSILON
                && Math.abs(angularVelocity) < REST_VELOCITY_EPSILON) {
            angle = 0f;
            angularVelocity = 0f;
        }
        lastAdvanceMs = now;
        updateMode(now);
        updateOpacity(now, true);
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

    public float opacity() {
        return currentOpacity;
    }

    private void applyPivotVelocityChange(float dxDp, float dyDp, long now) {
        if ((!finite(dxDp) || dxDp == 0f) && (!finite(dyDp) || dyDp == 0f)) {
            return;
        }
        long sampleMs = now - pointerSampleMs;
        if (sampleMs <= 0L) {
            sampleMs = 16L;
        }
        sampleMs = Math.min(sampleMs, 1_000L);
        float nextX = finite(dxDp) ? clamp(dxDp * 1000f / sampleMs,
                -MAX_PIVOT_VELOCITY_DP_PER_SECOND, MAX_PIVOT_VELOCITY_DP_PER_SECOND) : 0f;
        float nextY = finite(dyDp) ? clamp(dyDp * 1000f / sampleMs,
                -MAX_PIVOT_VELOCITY_DP_PER_SECOND, MAX_PIVOT_VELOCITY_DP_PER_SECOND) : 0f;
        applyPivotVelocity(nextX, nextY);
        pointerSampleMs = now;
        pivotMoving = true;
        pivotStopMs = saturatingAdd(now, PIVOT_STOP_GRACE_MS);
    }

    private void applyPivotVelocity(float nextX, float nextY) {
        float deltaX = nextX - pointerVelocityX;
        float deltaY = nextY - pointerVelocityY;
        if (strength > 0f) {
            float tangentX = -(float) Math.cos(angle);
            float tangentY = -(float) Math.sin(angle);
            float kick = -(tangentX * deltaX + tangentY * deltaY) / physicsLengthDp;
            angularVelocity = clamp(angularVelocity + kick * strength,
                    -MAX_REALISTIC_ANGULAR_VELOCITY, MAX_REALISTIC_ANGULAR_VELOCITY);
        }
        pointerVelocityX = nextX;
        pointerVelocityY = nextY;
    }

    private void stopPivotAt(long now) {
        applyPivotVelocity(0f, 0f);
        pointerSampleMs = now;
        pivotMoving = false;
        pivotStopMs = 0L;
    }

    private void updateOpacity(long now, boolean advanceClock) {
        long fadeAt = saturatingAdd(lastActivityMs, idleDelayMs);
        boolean idle = fadeWhenIdle && buttons == 0 && now >= fadeAt;
        float desired = idle ? idleOpacity : activeOpacity;
        if (desired != opacityTarget) {
            long start = advanceClock && idle && !opacityIdle
                    ? Math.max(opacityTransitionStartMs, fadeAt) : now;
            opacityTransitionFrom = opacityAt(start);
            opacityTransitionStartMs = start;
            opacityTarget = desired;
        }
        opacityIdle = idle;
        currentOpacity = opacityAt(now);
    }

    private float opacityAt(long now) {
        long elapsed = now - opacityTransitionStartMs;
        if (elapsed <= 0L) {
            return opacityTransitionFrom;
        }
        float progress = clamp(elapsed / (float) OPACITY_FADE_MS, 0f, 1f);
        return opacityTransitionFrom + (opacityTarget - opacityTransitionFrom) * progress;
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
        } else if (mode == SLEEP && !sleepEnabled) {
            enterActive(now);
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
