package io.github.kuuminkochi.bigfatfish;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.View;

/** A passive, non-interactive cursor companion rendered in its own overlay. */
public final class CompanionView extends View {
    private static final float MAX_SWING_RADIANS = 0.68f;
    private static final float MIN_SIZE_DP = 1f;

    private final float density;
    private final Paint threadPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint spritePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF spriteBounds = new RectF();
    private final Path ropePath = new Path();
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Choreographer.FrameCallback frameTick = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!animating || !attached || !isRenderable()) {
                animating = false;
                return;
            }
            model.advance(frameTimeNanos / 1_000_000L);
            invalidate();
            choreographer.postFrameCallback(this);
        }
    };

    private AnimationModel model;
    private SpritePack pack;
    private SettingsStore.Config config;
    private boolean attached;
    private boolean animating;
    private float designWidthDp;
    private float designHeightDp;
    private float anchorXDp;
    private float anchorYDp;
    private float spriteWidthDp;
    private float spriteHeightDp;
    private float threadDp;
    private float bodyRestAngle;

    public CompanionView(Context context, SpritePack pack, SettingsStore.Config config) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        this.pack = pack;
        this.config = config;
        model = new AnimationModel(SystemClock.uptimeMillis());
        applyModelConfig();
        threadPaint.setColor(0xCC0B2430);
        threadPaint.setStrokeCap(Paint.Cap.ROUND);
        threadPaint.setStrokeWidth(Math.max(1f, density));
        threadPaint.setStyle(Paint.Style.STROKE);
        threadPaint.setStrokeJoin(Paint.Join.ROUND);
        spritePaint.setFilterBitmap(false);
        setClickable(false);
        setFocusable(false);
        recomputeGeometry();
    }

    public void setPack(SpritePack pack) {
        if (this.pack == pack) {
            return;
        }
        this.pack = pack;
        applyModelConfig();
        recomputeGeometry();
        requestLayout();
        invalidate();
    }


    public void setConfig(SettingsStore.Config config) {
        if (config == null) {
            return;
        }
        this.config = config;
        applyModelConfig();
        recomputeGeometry();
        requestLayout();
        invalidate();
        ensureAnimationStarted();
    }

    public void onPointer(float dxDp, float dyDp, int buttons, boolean activity, long nowMs) {
        model.onPointer(dxDp, dyDp, buttons, activity, nowMs);
        invalidate();
        ensureAnimationStarted();
    }

    public float designWidthDp() {
        return designWidthDp;
    }

    public float designHeightDp() {
        return designHeightDp;
    }

    public float anchorXDp() {
        return anchorXDp;
    }

    public float anchorYDp() {
        return anchorYDp;
    }

    public void stopAnimation() {
        animating = false;
        choreographer.removeFrameCallback(frameTick);
    }

    public String modeName() {
        switch (model.mode()) {
            case AnimationModel.SLEEP:
                return "sleep";
            case AnimationModel.REACT:
                return "react";
            default:
                return "active";
        }
    }

    public float angleRadians() {
        return model.renderAngleRadians();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int desiredWidth = Math.max(1, Math.round(designWidthDp * density));
        int desiredHeight = Math.max(1, Math.round(designHeightDp * density));
        setMeasuredDimension(resolveSize(desiredWidth, widthMeasureSpec),
                resolveSize(desiredHeight, heightMeasureSpec));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        ensureAnimationStarted();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimation();
        attached = false;
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        if (visibility == VISIBLE) {
            ensureAnimationStarted();
        } else {
            stopAnimation();
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        if (changedView == this && visibility == VISIBLE) {
            ensureAnimationStarted();
        } else if (changedView == this) {
            stopAnimation();
        }
    }

    @Override
    public void onScreenStateChanged(int screenState) {
        super.onScreenStateChanged(screenState);
        if (screenState == SCREEN_STATE_ON) {
            ensureAnimationStarted();
        } else {
            stopAnimation();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pack == null || config == null) {
            return;
        }
        long now = SystemClock.uptimeMillis();
        int mode = model.mode();
        long elapsed = model.frameElapsedMs(now);
        Bitmap currentFrame = pack.frameFor(mode, elapsed, config.animationSpeed);
        if (currentFrame == null) {
            return;
        }

        float scale = density;
        float anchorX = anchorXDp * scale;
        float anchorY = anchorYDp * scale;
        float thread = threadDp * scale;
        float spriteWidth = spriteWidthDp * scale;
        float spriteHeight = spriteHeightDp * scale;
        float attachX = clamp(pack.attachmentX, 0f, 1f);
        float attachY = clamp(pack.attachmentY, 0f, 1f);

        canvas.save();
        int opacityAlpha = Math.round(clamp(model.opacity(), 0f, 1f) * 255f);
        int oldThreadAlpha = threadPaint.getAlpha();
        int oldSpriteAlpha = spritePaint.getAlpha();
        threadPaint.setAlpha(oldThreadAlpha * opacityAlpha / 255);
        spritePaint.setAlpha(oldSpriteAlpha * opacityAlpha / 255);
        if (config.realisticPhysics) {
            if (config.showThread) {
                ropePath.reset();
                int pointCount = model.ropePointCount();
                if (pointCount > 0) {
                    ropePath.moveTo(anchorX + model.renderRopePointX(0) * scale,
                            anchorY + model.renderRopePointY(0) * scale);
                    for (int i = 1; i < pointCount; i++) {
                        ropePath.lineTo(anchorX + model.renderRopePointX(i) * scale,
                                anchorY + model.renderRopePointY(i) * scale);
                    }
                    canvas.drawPath(ropePath, threadPaint);
                }
            }
            int end = model.ropePointCount() - 1;
            float pendantX = anchorX + model.renderRopePointX(end) * scale;
            float pendantY = anchorY + model.renderRopePointY(end) * scale;
            canvas.save();
            canvas.rotate((float) Math.toDegrees(model.renderAngleRadians() - bodyRestAngle),
                    pendantX, pendantY);
            float left = pendantX - attachX * spriteWidth;
            float top = pendantY - attachY * spriteHeight;
            spriteBounds.set(left, top, left + spriteWidth, top + spriteHeight);
            canvas.drawBitmap(currentFrame, null, spriteBounds, spritePaint);
            canvas.restore();
        } else {
            canvas.rotate((float) Math.toDegrees(model.angleRadians()), anchorX, anchorY);
            if (config.showThread) {
                canvas.drawLine(anchorX, anchorY, anchorX, anchorY + thread, threadPaint);
            }
            float left = anchorX - attachX * spriteWidth;
            float top = anchorY + thread - attachY * spriteHeight;
            spriteBounds.set(left, top, left + spriteWidth, top + spriteHeight);
            canvas.drawBitmap(currentFrame, null, spriteBounds, spritePaint);
        }
        threadPaint.setAlpha(oldThreadAlpha);
        spritePaint.setAlpha(oldSpriteAlpha);
        canvas.restore();

    }
    private void applyModelConfig() {
        if (config == null) {
            return;
        }
        long reactionDurationMs = 1L;
        if (pack != null) {
            long clipDurationMs = pack.durationMs(SpritePack.REACT);
            float speed = Float.isFinite(config.animationSpeed) && config.animationSpeed > 0f
                    ? config.animationSpeed : 1f;
            reactionDurationMs = Math.max(1L,
                    (long) Math.ceil(clipDurationMs / (double) speed));
        }
        model.configure(config.realisticPhysics ? config.stringStrength : config.swingStrength,
                config.realisticPhysics ? config.stringDamping : config.damping, config.idleDelayMs,
                config.sleepEnabled, config.reactionMask, reactionDurationMs);
        model.configurePhysics(config.realisticPhysics, threadLengthDp(), bodyOffsetDp());
        model.configureGravity(config.gravityStrength);
        model.configureOpacity(config.fadeWhenIdle, config.activeOpacity, config.idleOpacity);
    }

    private float threadLengthDp() {
        return config == null ? 32f : Math.max(0f, config.threadDp);
    }

    private float bodyOffsetDp() {
        float size = config == null ? 56f : Math.max(MIN_SIZE_DP, config.sizeDp);
        float width = size;
        float height = size;
        if (pack != null && pack.width > 0 && pack.height > 0) {
            height = size * ((float) pack.height / (float) pack.width);
        }
        float attachX = pack == null ? 0.5f : clamp(pack.attachmentX, 0f, 1f);
        float attachY = pack == null ? 0.5f : clamp(pack.attachmentY, 0f, 1f);
        float offset = (float) Math.hypot((0.5f - attachX) * width,
                (0.5f - attachY) * height);
        return offset > 0f && Float.isFinite(offset) ? offset : MIN_SIZE_DP;
    }

    private void recomputeGeometry() {
        float size = config == null ? 56f : Math.max(MIN_SIZE_DP, config.sizeDp);
        float thread = config == null ? 32f : Math.max(0f, config.threadDp);
        float spriteWidth = size;
        float spriteHeight = size;
        if (pack != null && pack.width > 0 && pack.height > 0) {
            spriteHeight = size * ((float) pack.height / (float) pack.width);
        }
        float attachX = pack == null ? 0.5f : clamp(pack.attachmentX, 0f, 1f);
        float attachY = pack == null ? 0.5f : clamp(pack.attachmentY, 0f, 1f);
        bodyRestAngle = (float) Math.atan2(-(0.5f - attachX) * spriteWidth,
                (0.5f - attachY) * spriteHeight);
        GeometryBounds bounds = new GeometryBounds();
        if (config != null && config.realisticPhysics) {
            float left = -attachX * spriteWidth;
            float right = (1f - attachX) * spriteWidth;
            float top = -attachY * spriteHeight;
            float bottom = (1f - attachY) * spriteHeight;
            float cornerRadius = Math.max(Math.max((float) Math.hypot(left, top),
                    (float) Math.hypot(right, top)),
                    Math.max((float) Math.hypot(left, bottom),
                            (float) Math.hypot(right, bottom)));
            float radius = thread + cornerRadius;
            bounds.include(-radius, -radius);
            bounds.include(radius, radius);
        } else {
            includeRotatingPoint(bounds, 0f, 0f);
            includeRotatingPoint(bounds, 0f, thread);
            includeRotatingPoint(bounds, -attachX * spriteWidth,
                    thread - attachY * spriteHeight);
            includeRotatingPoint(bounds, (1f - attachX) * spriteWidth,
                    thread - attachY * spriteHeight);
            includeRotatingPoint(bounds, -attachX * spriteWidth,
                    thread + (1f - attachY) * spriteHeight);
            includeRotatingPoint(bounds, (1f - attachX) * spriteWidth,
                    thread + (1f - attachY) * spriteHeight);
        }

        float pad = 2f;
        spriteWidthDp = spriteWidth;
        spriteHeightDp = spriteHeight;
        threadDp = thread;
        designWidthDp = Math.max(MIN_SIZE_DP, bounds.maxX - bounds.minX + pad * 2f);
        designHeightDp = Math.max(MIN_SIZE_DP, bounds.maxY - bounds.minY + pad * 2f);
        anchorXDp = pad - bounds.minX;
        anchorYDp = pad - bounds.minY;
    }


    private static void includeRotatingPoint(GeometryBounds bounds, float x, float y) {
        includeAtAngle(bounds, x, y, -MAX_SWING_RADIANS);
        includeAtAngle(bounds, x, y, 0f);
        includeAtAngle(bounds, x, y, MAX_SWING_RADIANS);
        float xCritical = (float) Math.atan2(-y, x);
        includeAtAngle(bounds, x, y, xCritical - (float) Math.PI);
        includeAtAngle(bounds, x, y, xCritical);
        includeAtAngle(bounds, x, y, xCritical + (float) Math.PI);
        float yCritical = (float) Math.atan2(x, y);
        includeAtAngle(bounds, x, y, yCritical - (float) Math.PI);
        includeAtAngle(bounds, x, y, yCritical);
        includeAtAngle(bounds, x, y, yCritical + (float) Math.PI);
    }

    private static void includeAtAngle(GeometryBounds bounds, float x, float y, float angle) {
        if (angle < -MAX_SWING_RADIANS || angle > MAX_SWING_RADIANS) {
            return;
        }
        float cosine = (float) Math.cos(angle);
        float sine = (float) Math.sin(angle);
        bounds.include(x * cosine - y * sine, x * sine + y * cosine);
    }

    private static final class GeometryBounds {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;

        void include(float x, float y) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
    }

    private boolean isRenderable() {
        return attached && getVisibility() == VISIBLE
                && getWindowVisibility() == VISIBLE && getWindowToken() != null;
    }

    private void ensureAnimationStarted() {
        if (!attached || !isRenderable() || animating) {
            return;
        }
        animating = true;
        choreographer.removeFrameCallback(frameTick);
        choreographer.postFrameCallback(frameTick);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
