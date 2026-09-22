package io.github.kuuminkochi.bigfatfish;

/**
 * Pure animation state machine and pointer-driven motion model.
 *
 * <p>Callers provide monotonic milliseconds to {@link #onPointer(float, float, int, boolean, long)}
 * and {@link #advance(long)}. Realistic motion uses a small fixed particle rope; classic motion
 * retains the original damped spring angle.</p>
 */
public final class AnimationModel {
    public static final int ACTIVE = 0;
    public static final int SLEEP = 1;
    public static final int REACT = 2;

    private static final long DEFAULT_IDLE_DELAY_MS = 5_000L;
    private static final long MAX_REACTION_DURATION_MS = 120L * 2_000L * 4L;
    private static final long MAX_CLOCK_STEP_MS = 4_000L;
    private static final long OPACITY_FADE_MS = 350L;

    private static final float MAX_ANGLE = 0.68f;
    private static final float SPRING_STIFFNESS = 28f;
    private static final float REST_ANGLE_EPSILON = 0.0025f;
    private static final float REST_VELOCITY_EPSILON = 0.0025f;

    private static final int ROPE_SEGMENTS = 8;
    private static final int ROPE_POINTS = ROPE_SEGMENTS + 1;
    private static final int CONSTRAINT_ITERATIONS = 6;
    private static final float FIXED_STEP_SECONDS = 1f / 120f;
    private static final float GRAVITY_DP_PER_SECOND_SQUARED = 980f;
    private static final float MAX_THREAD_DP = 20_000f;
    private static final float MAX_BODY_DP = 4_000f;
    private static final float MAX_POINTER_DELTA_DP = 4_000f;
    private static final float MAX_PARTICLE_DP = MAX_THREAD_DP + MAX_BODY_DP + MAX_POINTER_DELTA_DP;
    private static final float PARTICLE_EPSILON = 0.0001f;

    private float strength = 1f;
    private float damping = 7f;
    private long idleDelayMs = DEFAULT_IDLE_DELAY_MS;
    private boolean sleepEnabled = true;
    private int reactionMask = -1;
    private long reactionDurationMs = 1L;

    private boolean realisticPhysics;
    private float threadLengthDp = 1f;
    private float bodyLengthDp = 1f;
    private final float[] ropeX = new float[ROPE_POINTS];
    private final float[] ropeY = new float[ROPE_POINTS];
    private final float[] previousRopeX = new float[ROPE_POINTS];
    private final float[] previousRopeY = new float[ROPE_POINTS];
    private float bodyX;
    private float bodyY;
    private float previousBodyX;
    private float previousBodyY;
    private float simulationRemainder;
    private boolean ropeInitialized;

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

    /** Selects classic spring motion or the bounded particle-rope simulation. */
    public void configurePhysics(boolean realistic, float threadLengthDp, float bodyLengthDp) {
        float nextThread = clamp(finiteOr(threadLengthDp, 1f), 0f, MAX_THREAD_DP);
        float nextBody = finiteOr(bodyLengthDp, 1f);
        if (!(nextBody > 0f)) {
            nextBody = 1f;
        }
        nextBody = Math.min(nextBody, MAX_BODY_DP);
        boolean changed = realisticPhysics != realistic
                || Math.abs(this.threadLengthDp - nextThread) > 0.0001f
                || Math.abs(this.bodyLengthDp - nextBody) > 0.0001f;
        realisticPhysics = realistic;
        this.threadLengthDp = nextThread;
        this.bodyLengthDp = nextBody;
        if (changed || !ropeInitialized) {
            resetRope();
        }
        if (!realisticPhysics) {
            angle = clamp(angle, -MAX_ANGLE, MAX_ANGLE);
            angularVelocity = clamp(angularVelocity, -4.5f, 4.5f);
        }
    }

    /** Changes fading without resetting its current progress or sleep state. */
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

    /** Feeds one pointer observation. Deltas are in dp and relative to the previous packet. */
    public void onPointer(float dxDp, float dyDp, int buttons, boolean activity, long nowMs) {
        long now = safeTime(nowMs);
        advance(now);

        int previousButtons = this.buttons;
        this.buttons = buttons;
        int newlyPressed = buttons & ~previousButtons;
        boolean moved = finite(dxDp) && dxDp != 0f || finite(dyDp) && dyDp != 0f;
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
            translatePivot(safeDelta(dxDp), safeDelta(dyDp));
        } else if (finite(dxDp) && dxDp != 0f && strength > 0f) {
            float impulse = clamp(dxDp, -160f, 160f) * 0.0045f * strength;
            angularVelocity = clamp(angularVelocity + impulse, -4.5f, 4.5f);
            angle = clamp(angle + impulse * 0.016f, -MAX_ANGLE, MAX_ANGLE);
        }
        updateMode(now);
        updateOpacity(now, false);
    }

    /** Advances the state machine and physics to {@code nowMs}. */
    public void advance(long nowMs) {
        long now = safeTime(nowMs);
        if (now < lastAdvanceMs) {
            return;
        }
        long elapsed = now - lastAdvanceMs;
        long simulationMs = Math.min(elapsed, MAX_CLOCK_STEP_MS);
        if (realisticPhysics && simulationMs > 0L) {
            simulationRemainder += simulationMs / 1000f;
            while (simulationRemainder >= FIXED_STEP_SECONDS) {
                stepRope(FIXED_STEP_SECONDS);
                simulationRemainder -= FIXED_STEP_SECONDS;
            }
            updateRealisticAngle();
        } else if (!realisticPhysics) {
            while (simulationMs > 0L) {
                long stepMs = Math.min(simulationMs, 16L);
                float dt = stepMs / 1000f;
                float acceleration = -SPRING_STIFFNESS * angle - damping * angularVelocity;
                angularVelocity += acceleration * dt;
                angle = clamp(angle + angularVelocity * dt, -MAX_ANGLE, MAX_ANGLE);
                simulationMs -= stepMs;
            }
            if (Math.abs(angle) < REST_ANGLE_EPSILON
                    && Math.abs(angularVelocity) < REST_VELOCITY_EPSILON) {
                angle = 0f;
                angularVelocity = 0f;
            }
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

    /** Sprite rotation in radians; realistic mode uses the attachment-to-COM link. */
    public float angleRadians() {
        return angle;
    }

    public boolean isAtRest() {
        if (!realisticPhysics) {
            return angle == 0f && angularVelocity == 0f;
        }
        if (Math.abs(angle) >= REST_ANGLE_EPSILON) {
            return false;
        }
        for (int i = 1; i < ROPE_POINTS; i++) {
            if (Math.abs(ropeX[i] - previousRopeX[i]) >= REST_VELOCITY_EPSILON
                    || Math.abs(ropeY[i] - previousRopeY[i]) >= REST_VELOCITY_EPSILON) {
                return false;
            }
        }
        return Math.abs(bodyX - previousBodyX) < REST_VELOCITY_EPSILON
                && Math.abs(bodyY - previousBodyY) < REST_VELOCITY_EPSILON;
    }

    public float opacity() {
        return currentOpacity;
    }

    public int ropePointCount() {
        return ROPE_POINTS;
    }

    public float ropePointX(int index) {
        if (index <= 0) {
            return 0f;
        }
        if (index >= ROPE_POINTS) {
            index = ROPE_POINTS - 1;
        }
        return finiteOr(ropeX[index], 0f);
    }

    public float ropePointY(int index) {
        if (index <= 0) {
            return 0f;
        }
        if (index >= ROPE_POINTS) {
            index = ROPE_POINTS - 1;
        }
        return finiteOr(ropeY[index], 0f);
    }

    public float pendantX() {
        return ropePointX(ROPE_POINTS - 1);
    }

    public float pendantY() {
        return ropePointY(ROPE_POINTS - 1);
    }

    private void resetRope() {
        float segment = threadLengthDp / ROPE_SEGMENTS;
        float curvature = Math.min(1.5f, threadLengthDp * 0.08f);
        for (int i = 0; i < ROPE_POINTS; i++) {
            float t = i / (float) ROPE_SEGMENTS;
            ropeX[i] = i == 0 ? 0f : curvature * (float) Math.sin(Math.PI * t);
            ropeY[i] = segment * i;
            previousRopeX[i] = ropeX[i];
            previousRopeY[i] = ropeY[i];
        }
        bodyX = 0f;
        bodyY = threadLengthDp + bodyLengthDp;
        previousBodyX = bodyX;
        previousBodyY = bodyY;
        simulationRemainder = 0f;
        ropeInitialized = true;
        enforceRopeReach();
        System.arraycopy(ropeX, 0, previousRopeX, 0, ROPE_POINTS);
        System.arraycopy(ropeY, 0, previousRopeY, 0, ROPE_POINTS);
        previousBodyX = bodyX;
        previousBodyY = bodyY;
        if (realisticPhysics) updateRealisticAngle();
    }

    private void translatePivot(float dx, float dy) {
        dx *= strength;
        dy *= strength;
        if (dx == 0f && dy == 0f) {
            return;
        }
        for (int i = 1; threadLengthDp > PARTICLE_EPSILON && i < ROPE_POINTS; i++) {
            ropeX[i] = finiteOr(ropeX[i] - dx, 0f);
            ropeY[i] = finiteOr(ropeY[i] - dy, 0f);
            previousRopeX[i] = finiteOr(previousRopeX[i] - dx, 0f);
            previousRopeY[i] = finiteOr(previousRopeY[i] - dy, 0f);
        }
        bodyX = finiteOr(bodyX - dx, 0f);
        bodyY = finiteOr(bodyY - dy, 0f);
        previousBodyX = finiteOr(previousBodyX - dx, 0f);
        previousBodyY = finiteOr(previousBodyY - dy, 0f);
        enforceRopeReach();
        updateRealisticAngle();
    }

    private void stepRope(float dt) {
        float drag = (float) Math.exp(-damping * dt);
        float gravity = GRAVITY_DP_PER_SECOND_SQUARED;
        for (int i = 1; threadLengthDp > PARTICLE_EPSILON && i < ROPE_POINTS; i++) {
            float x = ropeX[i];
            float y = ropeY[i];
            float velocityX = (x - previousRopeX[i]) * drag;
            float velocityY = (y - previousRopeY[i]) * drag;
            previousRopeX[i] = x;
            previousRopeY[i] = y;
            ropeX[i] = x + velocityX;
            ropeY[i] = y + velocityY + gravity * dt * dt;
        }
        float bodyVelocityX = (bodyX - previousBodyX) * drag;
        float bodyVelocityY = (bodyY - previousBodyY) * drag;
        previousBodyX = bodyX;
        previousBodyY = bodyY;
        bodyX += bodyVelocityX;
        bodyY += bodyVelocityY + gravity * dt * dt;

        float segment = threadLengthDp / ROPE_SEGMENTS;
        for (int iteration = 0; iteration < CONSTRAINT_ITERATIONS; iteration++) {
            for (int i = 1; i < ROPE_POINTS; i++) {
                constrainMaximum(i - 1, i, segment);
            }
            constrainBodyLink();
        }
        ropeX[0] = 0f;
        ropeY[0] = 0f;
        previousRopeX[0] = 0f;
        previousRopeY[0] = 0f;
        sanitizeParticles();
        enforceRopeReach();
    }

    private void constrainMaximum(int first, int second, float maximum) {
        float dx = ropeX[second] - ropeX[first];
        float dy = ropeY[second] - ropeY[first];
        float distanceSquared = dx * dx + dy * dy;
        if (!finite(distanceSquared) || distanceSquared <= maximum * maximum || distanceSquared <= PARTICLE_EPSILON) {
            return;
        }
        float distance = (float) Math.sqrt(distanceSquared);
        float correction = (distance - maximum) / distance;
        if (first == 0) {
            ropeX[second] -= dx * correction;
            ropeY[second] -= dy * correction;
        } else {
            float half = correction * 0.5f;
            ropeX[first] += dx * half;
            ropeY[first] += dy * half;
            ropeX[second] -= dx * half;
            ropeY[second] -= dy * half;
        }
    }

    private void constrainBodyLink() {
        float dx = bodyX - ropeX[ROPE_POINTS - 1];
        float dy = bodyY - ropeY[ROPE_POINTS - 1];
        float distanceSquared = dx * dx + dy * dy;
        if (!finite(distanceSquared)) {
            bodyX = ropeX[ROPE_POINTS - 1];
            bodyY = ropeY[ROPE_POINTS - 1] + bodyLengthDp;
            return;
        }
        if (distanceSquared <= PARTICLE_EPSILON) {
            bodyY = ropeY[ROPE_POINTS - 1] + bodyLengthDp;
            return;
        }
        float distance = (float) Math.sqrt(distanceSquared);
        float correction = (distance - bodyLengthDp) / distance;
        // The body COM is heavy: most of the rigid-link correction moves the light endpoint.
        float endpointShare = threadLengthDp > PARTICLE_EPSILON ? 0.88f : 0f;
        ropeX[ROPE_POINTS - 1] += dx * correction * endpointShare;
        ropeY[ROPE_POINTS - 1] += dy * correction * endpointShare;
        bodyX -= dx * correction * (1f - endpointShare);
        bodyY -= dy * correction * (1f - endpointShare);
    }

    /** Final outward projection bounds even large cursor jumps before the next draw. */
    private void enforceRopeReach() {
        float oldEndX = ropeX[ROPE_POINTS - 1];
        float oldEndY = ropeY[ROPE_POINTS - 1];
        float segment = threadLengthDp / ROPE_SEGMENTS;
        for (int i = 1; i < ROPE_POINTS; i++) {
            float dx = ropeX[i] - ropeX[i - 1];
            float dy = ropeY[i] - ropeY[i - 1];
            float distance = (float) Math.hypot(dx, dy);
            if (distance > segment) {
                float scale = segment / distance;
                ropeX[i] = ropeX[i - 1] + dx * scale;
                ropeY[i] = ropeY[i - 1] + dy * scale;
            }
        }
        float endX = ropeX[ROPE_POINTS - 1];
        float endY = ropeY[ROPE_POINTS - 1];
        bodyX += endX - oldEndX;
        bodyY += endY - oldEndY;
        float dx = bodyX - endX;
        float dy = bodyY - endY;
        float distance = (float) Math.hypot(dx, dy);
        if (distance > PARTICLE_EPSILON) {
            bodyX = endX + dx * bodyLengthDp / distance;
            bodyY = endY + dy * bodyLengthDp / distance;
        } else {
            bodyX = endX;
            bodyY = endY + bodyLengthDp;
        }
    }

    private void sanitizeParticles() {
        for (int i = 1; i < ROPE_POINTS; i++) {
            ropeX[i] = clamp(finiteOr(ropeX[i], 0f), -MAX_PARTICLE_DP, MAX_PARTICLE_DP);
            ropeY[i] = clamp(finiteOr(ropeY[i], 0f), -MAX_PARTICLE_DP, MAX_PARTICLE_DP);
            previousRopeX[i] = clamp(finiteOr(previousRopeX[i], ropeX[i]), -MAX_PARTICLE_DP, MAX_PARTICLE_DP);
            previousRopeY[i] = clamp(finiteOr(previousRopeY[i], ropeY[i]), -MAX_PARTICLE_DP, MAX_PARTICLE_DP);
        }
        bodyX = clamp(finiteOr(bodyX, 0f), -MAX_PARTICLE_DP, MAX_PARTICLE_DP);
        bodyY = clamp(finiteOr(bodyY, bodyLengthDp), -MAX_PARTICLE_DP, MAX_PARTICLE_DP);
        previousBodyX = clamp(finiteOr(previousBodyX, bodyX), -MAX_PARTICLE_DP, MAX_PARTICLE_DP);
        previousBodyY = clamp(finiteOr(previousBodyY, bodyY), -MAX_PARTICLE_DP, MAX_PARTICLE_DP);
    }

    private void updateRealisticAngle() {
        float dx = bodyX - ropeX[ROPE_POINTS - 1];
        float dy = bodyY - ropeY[ROPE_POINTS - 1];
        if (!finite(dx) || !finite(dy) || dx * dx + dy * dy <= PARTICLE_EPSILON) {
            angle = 0f;
        } else {
            angle = (float) Math.atan2(-dx, dy);
        }
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
        if (now > lastAdvanceMs) {
            lastAdvanceMs = now;
        }
    }

    private static float safeDelta(float value) {
        return finite(value) ? clamp(value, -MAX_POINTER_DELTA_DP, MAX_POINTER_DELTA_DP) : 0f;
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
