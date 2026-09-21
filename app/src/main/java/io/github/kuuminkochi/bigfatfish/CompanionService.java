package io.github.kuuminkochi.bigfatfish;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.IBinder;
import android.os.IInterface;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import java.lang.reflect.Field;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.lsposed.hiddenapibypass.HiddenApiBypass;

/** Passive mouse companion. The ADB helper is the only caller allowed to configure observation. */
public final class CompanionService extends AccessibilityService {
    public static volatile CompanionService current;
    public static volatile IBinder helper;
    public static volatile boolean running;
    public static volatile String status = "Enable BigFatFish in Accessibility settings.";
    public static volatile long observedCount;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "bigfatfish-pack-loader");
        thread.setDaemon(true);
        return thread;
    });
    private WindowManager windows;
    private WindowManager.LayoutParams layout;
    private CompanionView companion;
    private SpritePack pack;
    private SettingsStore.Config config;
    private String loadingPackId;
    private boolean framePending;
    private boolean hasPosition;
    private float pointerX;
    private float pointerY;
    private int buttonState;
    private boolean resumeFailed;

    private final Runnable configPoll = new Runnable() {
        @Override public void run() {
            if (current != CompanionService.this) return;
            applyCurrentSettings();
            main.postDelayed(this, 500L);
        }
    };

    private final Choreographer.FrameCallback frame = timeNanos -> {
        framePending = false;
        if (!running || companion == null || !hasPosition) return;
        layout.x = Math.round(pointerX - dp(companion.anchorXDp()));
        layout.y = Math.round(pointerY - dp(companion.anchorYDp()));
        companion.setVisibility(View.VISIBLE);
        try {
            windows.updateViewLayout(companion, layout);
        } catch (RuntimeException e) {
            fail("Overlay positioning failed", e);
        }
    };

    public static void setDesiredEnabled(android.content.Context context, boolean enabled) {
        SettingsStore.setEnabled(context, enabled);
        CompanionService service = current;
        if (service != null) {
            service.main.post(() -> {
                if (enabled) service.resumeIfReady();
                else service.stopObservation();
            });
        }
    }

    public static void registerHelper(IBinder incoming) throws RemoteException {
        if (incoming == null) throw new IllegalArgumentException("Missing helper binder");
        incoming.linkToDeath(() -> {
            if (helper == incoming) {
                helper = null;
                CompanionService service = current;
                if (service != null) {
                    service.status = service.running
                            ? "Running; helper disconnected. Restart the helper before observation can resume."
                            : "ADB helper disconnected.";
                }
            }
        }, 0);
        helper = incoming;
        CompanionService service = current;
        if (service != null) service.main.post(service::resumeIfReady);
    }

    private void resumeIfReady() {
        if (current != this || running || resumeFailed || !SettingsStore.enabled(this)) return;
        IBinder bridge = helper;
        if (bridge == null || !bridge.isBinderAlive()) {
            status = "Enabled; start the provided ADB helper to begin passive observation.";
            return;
        }
        beginObservation(true);
    }

    @Override protected void onServiceConnected() {
        current = this;
        windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        config = SettingsStore.read(this);
        status = SettingsStore.enabled(this)
                ? "Enabled; waiting for the ADB helper."
                : "Accessibility service ready; enable BigFatFish to start.";
        main.removeCallbacks(configPoll);
        main.post(configPoll);
        main.post(this::resumeIfReady);
    }

    private void beginObservation(boolean persistent) {
        if (running) return;
        IBinder bridge = helper;
        if (bridge == null || !bridge.isBinderAlive()) {
            status = "Start the provided ADB helper before enabling observation.";
            return;
        }
        SettingsStore.Config wanted = SettingsStore.read(this);
        config = wanted;
        if (pack == null || !pack.id.equals(wanted.packId)) {
            loadPack(wanted.packId, persistent);
            return;
        }
        try {
            if (!HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/accessibilityservice/AccessibilityService;",
                    "Landroid/view/accessibility/AccessibilityInteractionClient;")) {
                throw new IllegalStateException("Accessibility connection access unavailable");
            }
            int connectionId = (Integer) AccessibilityService.class
                    .getMethod("getConnectionId").invoke(this);
            Class<?> client = Class.forName("android.view.accessibility.AccessibilityInteractionClient");
            IInterface connection = (IInterface) client.getMethod("getConnection", int.class)
                    .invoke(null, connectionId);
            if (connection == null) throw new IllegalStateException("Accessibility service is not bound");
            Parcel request = Parcel.obtain();
            Parcel reply = Parcel.obtain();
            try {
                request.writeInterfaceToken(AppIdentity.DESCRIPTOR);
                request.writeStrongBinder(connection.asBinder());
                if (!bridge.transact(1, request, reply, 0)) {
                    throw new IllegalStateException("ADB helper rejected observation request");
                }
                reply.readException();
                if (reply.readInt() != InputDevice.SOURCE_MOUSE) {
                    throw new IllegalStateException("System did not accept mouse-only observation");
                }
            } finally {
                reply.recycle();
                request.recycle();
            }
            Field sources = AccessibilityService.class.getDeclaredField("mMotionEventSources");
            sources.setAccessible(true);
            sources.setInt(this, InputDevice.SOURCE_MOUSE);
            observedCount = 0L;
            buttonState = 0;
            hasPosition = false;
            companion = new CompanionView(this, pack, config);
            companion.setVisibility(View.INVISIBLE);
            layout = new WindowManager.LayoutParams(dp(companion.designWidthDp()),
                    dp(companion.designHeightDp()),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            layout.gravity = Gravity.TOP | Gravity.LEFT;
            layout.setFitInsetsTypes(0);
            layout.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
            layout.setTitle("BigFatFish passive pointer companion");
            windows.addView(companion, layout);
            running = true;
            resumeFailed = false;
            status = "Passive mouse observation is running; keyboard and native input are untouched.";
        } catch (ReflectiveOperationException | RemoteException | RuntimeException | LinkageError e) {
            if (persistent) resumeFailed = true;
            fail("Could not start passive mouse observation", e);
        }
    }

    private void loadPack(String id, boolean persistent) {
        if (id == null || id.equals(loadingPackId)) {
            status = "Loading the selected artwork pack.";
            return;
        }
        loadingPackId = id;
        status = "Loading the selected artwork pack.";
        android.content.Context appContext = getApplicationContext();
        loader.execute(() -> {
            try {
                SpritePack loaded = SpritePack.load(appContext, id);
                main.post(() -> {
                    if (current != this) return;
                    SettingsStore.Config latest = SettingsStore.read(this);
                    if (!loaded.id.equals(latest.packId)) {
                        loadingPackId = null;
                        loadPack(latest.packId, persistent);
                        return;
                    }
                    loadingPackId = null;
                    pack = loaded;
                    config = latest;
                    if (companion != null) {
                        companion.setPack(loaded);
                        companion.setConfig(latest);
                        resizeOverlay();
                    }
                    if (running) {
                        status = "Passive mouse observation is running; keyboard and native input are untouched.";
                    } else if (SettingsStore.enabled(this)) {
                        beginObservation(persistent);
                    } else {
                        status = "Stopped. Accessibility observation is disabled.";
                    }
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (current != this) return;
                    String selected = SettingsStore.read(this).packId;
                    if (!id.equals(selected)) {
                        loadingPackId = null;
                        loadPack(selected, persistent);
                        return;
                    }
                    loadingPackId = null;
                    resumeFailed = persistent;
                    status = "Artwork pack could not be loaded: " + e.getClass().getSimpleName();
                    Log.e("BigFatFish", status, e);
                });
            }
        });
    }

    private void applyCurrentSettings() {
        SettingsStore.Config latest = SettingsStore.read(this);
        if (config == null || !sameConfig(config, latest)) {
            String oldPack = config == null ? null : config.packId;
            config = latest;
            if (companion != null && latest.packId.equals(oldPack)) {
                companion.setConfig(latest);
                resizeOverlay();
            } else if (!latest.packId.equals(oldPack)) {
                loadPack(latest.packId, SettingsStore.enabled(this));
            }
        }
        if (SettingsStore.enabled(this) && !running) resumeIfReady();
    }

    private static boolean sameConfig(SettingsStore.Config a, SettingsStore.Config b) {
        return a.packId.equals(b.packId) && Float.compare(a.sizeDp, b.sizeDp) == 0
                && Float.compare(a.threadDp, b.threadDp) == 0
                && Float.compare(a.swingStrength, b.swingStrength) == 0
                && Float.compare(a.damping, b.damping) == 0
                && Float.compare(a.animationSpeed, b.animationSpeed) == 0
                && a.idleDelayMs == b.idleDelayMs && a.sleepEnabled == b.sleepEnabled
                && a.showThread == b.showThread && a.reactionMask == b.reactionMask;
    }

    private void resizeOverlay() {
        if (companion == null || layout == null || windows == null) return;
        layout.width = dp(companion.designWidthDp());
        layout.height = dp(companion.designHeightDp());
        if (hasPosition) {
            layout.x = Math.round(pointerX - dp(companion.anchorXDp()));
            layout.y = Math.round(pointerY - dp(companion.anchorYDp()));
        }
        try {
            windows.updateViewLayout(companion, layout);
        } catch (RuntimeException e) {
            fail("Overlay resize failed", e);
        }
    }

    public void stopObservation() {
        resumeFailed = false;
        stopObservationInternal();
        status = "Stopped. Accessibility observation is disabled.";
    }

    private void stopObservationInternal() {
        running = false;
        if (framePending) Choreographer.getInstance().removeFrameCallback(frame);
        framePending = false;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.setMotionEventSources(0);
                setServiceInfo(info);
            }
        } catch (RuntimeException e) {
            Log.e("BigFatFish", "Could not clear passive source mask", e);
            disableSelf();
        }
        if (companion != null) {
            companion.stopAnimation();
            try { windows.removeViewImmediate(companion); } catch (RuntimeException ignored) { }
            companion = null;
        }
        layout = null;
    }

    private void fail(String message, Throwable error) {
        stopObservationInternal();
        Throwable cause = error.getCause() == null ? error : error.getCause();
        status = message + ": " + cause.getClass().getSimpleName();
        Log.e("BigFatFish", status, cause);
    }

    @Override public void onMotionEvent(MotionEvent event) {
        if (!running || !event.isFromSource(InputDevice.SOURCE_MOUSE)) return;
        float x = event.getRawX();
        float y = event.getRawY();
        float dx = hasPosition ? x - pointerX : 0f;
        float dy = hasPosition ? y - pointerY : 0f;
        int oldButtons = buttonState;
        pointerX = x;
        pointerY = y;
        buttonState = event.getButtonState();
        boolean wheel = event.getAxisValue(MotionEvent.AXIS_VSCROLL) != 0f
                || event.getAxisValue(MotionEvent.AXIS_HSCROLL) != 0f;
        boolean activity = dx != 0f || dy != 0f || oldButtons != buttonState || wheel;
        if (companion != null) {
            companion.onPointer(dx / density(), dy / density(), buttonState, activity,
                    android.os.SystemClock.uptimeMillis());
        }
        observedCount++;
        hasPosition = true;
        if (!framePending) {
            framePending = true;
            Choreographer.getInstance().postFrameCallback(frame);
        }
    }
    public String modeName() {
        return companion == null ? "offline" : companion.modeName();
    }


    @Override public void onAccessibilityEvent(AccessibilityEvent event) { }
    @Override public void onInterrupt() {
        stopObservationInternal();
        status = SettingsStore.enabled(this)
                ? "Accessibility observation interrupted; waiting for service recovery."
                : "Accessibility observation interrupted.";
    }

    @Override public void onDestroy() {
        main.removeCallbacks(configPoll);
        stopObservationInternal();
        loader.shutdownNow();
        if (current == this) current = null;
        super.onDestroy();
    }

    private float density() { return getResources().getDisplayMetrics().density; }
    private int dp(float value) { return Math.round(value * density()); }
}
