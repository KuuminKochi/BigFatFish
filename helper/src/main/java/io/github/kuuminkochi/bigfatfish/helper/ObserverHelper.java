import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.AttributionSource;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.net.LocalServerSocket;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.InputDevice;
import io.github.kuuminkochi.bigfatfish.AppIdentity;
import java.lang.reflect.Method;

/** Shell-authorized bridge for passive mouse observation only. */
public final class ObserverHelper {
    private static final long RECONNECT_MS = 4_000L;
    private static LocalServerSocket singleton;

    private static Bundle callProvider(Class<?> activityManagerType, Object activityManager,
            String method, Bundle extras) throws Exception {
        IBinder providerToken = new Binder();
        Object holder = activityManagerType.getMethod("getContentProviderExternal",
                String.class, int.class, IBinder.class, String.class)
                .invoke(activityManager, AppIdentity.AUTHORITY, 0, providerToken, "*bigfatfish*");
        if (holder == null) throw new IllegalStateException("BigFatFish provider unavailable");
        try {
            Object provider = holder.getClass().getField("provider").get(holder);
            AttributionSource attribution = new AttributionSource.Builder(2000)
                    .setPackageName("com.android.shell").build();
            return (Bundle) Class.forName("android.content.IContentProvider")
                    .getMethod("call", AttributionSource.class, String.class, String.class,
                            String.class, Bundle.class)
                    .invoke(provider, attribution, AppIdentity.AUTHORITY, method, null, extras);
        } finally {
            activityManagerType.getMethod("removeContentProviderExternalAsUser",
                    String.class, IBinder.class, int.class)
                    .invoke(activityManager, AppIdentity.AUTHORITY, providerToken, 0);
        }
    }

    private static boolean stopped(Context shell) throws PackageManager.NameNotFoundException {
        ApplicationInfo info = shell.getPackageManager().getApplicationInfo(AppIdentity.APP_ID, 0);
        return (info.flags & ApplicationInfo.FLAG_STOPPED) != 0;
    }

    private static void fatal(String message, Throwable cause) {
        System.err.println("FATAL: " + message + (cause == null ? "" : ": " + cause));
        System.exit(1);
    }

    public static void main(String[] args) throws Exception {
        singleton = new LocalServerSocket(AppIdentity.APP_ID + ".helper.singleton");
        Looper.prepareMainLooper();
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        Object thread = activityThread.getMethod("systemMain").invoke(null);
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context shell = system.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY);
        if (shell.checkSelfPermission("android.permission.ACCESSIBILITY_MOTION_EVENT_OBSERVING")
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Shell lacks passive motion observation permission");
        }
        final ApplicationInfo appInfo = shell.getPackageManager()
                .getApplicationInfo(AppIdentity.APP_ID, 0);
        if ((appInfo.flags & ApplicationInfo.FLAG_STOPPED) != 0) {
            throw new IllegalStateException("BigFatFish is force-stopped; open it before starting helper");
        }
        final int appUid = appInfo.uid;
        final Class<?> connectionType = Class.forName(
                "android.accessibilityservice.IAccessibilityServiceConnection");
        final Method asInterface = Class.forName(
                "android.accessibilityservice.IAccessibilityServiceConnection$Stub")
                .getMethod("asInterface", IBinder.class);
        final Method getInfo = connectionType.getMethod("getServiceInfo");
        final Method setInfo = connectionType.getMethod("setServiceInfo", AccessibilityServiceInfo.class);
        final Method observe = AccessibilityServiceInfo.class.getMethod(
                "setObservedMotionEventSources", int.class);
        final Method getObserved = AccessibilityServiceInfo.class.getMethod(
                "getObservedMotionEventSources");
        final boolean supported = (Boolean) Class.forName("android.view.accessibility.Flags")
                .getMethod("motionEventObserving").invoke(null);
        if (!supported) throw new IllegalStateException(
                "This Android build has passive mouse observation disabled");

        final Handler handler = new Handler(Looper.getMainLooper());
        final Binder helper = new Binder() {
            @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                    throws RemoteException {
                if (code == INTERFACE_TRANSACTION) {
                    reply.writeString(AppIdentity.DESCRIPTOR);
                    return true;
                }
                if (code == 2) {
                    if (Binder.getCallingUid() != appUid) {
                        throw new SecurityException("Only BigFatFish may stop this helper");
                    }
                    data.enforceInterface(AppIdentity.DESCRIPTOR);
                    if (data.dataAvail() != 0) throw new IllegalArgumentException("Invalid shutdown request");
                    reply.writeNoException();
                    handler.postDelayed(() -> System.exit(0), 100L);
                    return true;
                }
                if (code != 1) return super.onTransact(code, data, reply, flags);
                if (Binder.getCallingUid() != appUid) {
                    throw new SecurityException("Only BigFatFish may request observation");
                }
                data.enforceInterface(AppIdentity.DESCRIPTOR);
                IBinder connectionBinder = data.readStrongBinder();
                if (connectionBinder == null || data.dataAvail() != 0) {
                    throw new IllegalArgumentException("Invalid observation connection");
                }
                long identity = Binder.clearCallingIdentity();
                try {
                    Object connection = asInterface.invoke(null, connectionBinder);
                    AccessibilityServiceInfo info = (AccessibilityServiceInfo) getInfo.invoke(connection);
                    if (info == null || info.getResolveInfo() == null
                            || !AppIdentity.APP_ID.equals(info.getResolveInfo().serviceInfo.packageName)
                            || !AppIdentity.SERVICE_CLASS.equals(info.getResolveInfo().serviceInfo.name)) {
                        throw new SecurityException("Connection does not belong to BigFatFish service");
                    }
                    info.eventTypes = 0;
                    info.flags = 0;
                    info.notificationTimeout = 0;
                    info.setMotionEventSources(InputDevice.SOURCE_MOUSE);
                    observe.invoke(info, InputDevice.SOURCE_MOUSE);
                    setInfo.invoke(connection, info);
                    AccessibilityServiceInfo accepted = (AccessibilityServiceInfo) getInfo.invoke(connection);
                    int mask = accepted == null ? 0 : (Integer) getObserved.invoke(accepted);
                    if (mask != InputDevice.SOURCE_MOUSE) {
                        info.setMotionEventSources(0);
                        setInfo.invoke(connection, info);
                        throw new IllegalStateException("System rejected passive mouse observation");
                    }
                    reply.writeNoException();
                    reply.writeInt(mask);
                    System.out.println("CONFIGURED passive mouse observation; mask=" + mask);
                    return true;
                } catch (Exception e) {
                    Throwable reason = e.getCause() == null ? e : e.getCause();
                    reply.writeException(new IllegalStateException(reason.toString()));
                    return true;
                } finally {
                    Binder.restoreCallingIdentity(identity);
                }
            }
        };
        final Class<?> activityManagerType = Class.forName("android.app.IActivityManager");
        final Object activityManager = Class.forName("android.app.ActivityManager")
                .getMethod("getService").invoke(null);
        final Bundle extras = new Bundle();
        extras.putBinder("helper", helper);
        Bundle response = callProvider(activityManagerType, activityManager, "register-helper", extras);
        if (response == null || !response.getBoolean("helperReady")) {
            throw new IllegalStateException("Helper registration was rejected");
        }
        System.out.println("READY: shell-authorized passive mouse helper registered; app UID=" + appUid);
        System.out.println("Run this launcher again after a full reboot; Android does not persist the shell helper.");

        final Runnable reconnect = new Runnable() {
            @Override public void run() {
                try {
                    android.content.pm.ApplicationInfo currentApp = shell.getPackageManager()
                            .getApplicationInfo(AppIdentity.APP_ID, 0);
                    int currentUid = currentApp.uid;
                    if (currentUid != appUid) {
                        fatal("BigFatFish UID changed; refusing to authorize another app", null);
                        return;
                    }
                    if ((currentApp.flags & android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0) {
                        fatal("BigFatFish is force-stopped; helper will not restart its provider", null);
                        return;
                    }
                    Bundle state;
                    try {
                        state = callProvider(activityManagerType, activityManager, "status", null);
                    } catch (Exception unavailable) {
                        if (stopped(shell)) {
                            fatal("BigFatFish is force-stopped; helper will not restart its provider", null);
                            return;
                        }
                        throw unavailable;
                    }
                    if (state == null || !state.getBoolean("helperReady")) {
                        if (stopped(shell)) {
                            fatal("BigFatFish is force-stopped; helper will not restart its provider", null);
                            return;
                        }
                        Bundle registration = callProvider(activityManagerType, activityManager,
                                "register-helper", extras);
                        if (registration == null || !registration.getBoolean("helperReady")) {
                            fatal("Helper registration was rejected", null);
                            return;
                        }
                        System.out.println("RECONNECTED: helper registered after app-process restart");
                    }
                    handler.postDelayed(this, RECONNECT_MS);
                } catch (SecurityException | LinkageError e) {
                    fatal("Helper stopped", e);
                } catch (Exception e) {
                    try {
                        if (stopped(shell)) {
                            fatal("BigFatFish is force-stopped; helper will not restart its provider", e);
                            return;
                        }
                    } catch (android.content.pm.PackageManager.NameNotFoundException removed) {
                        fatal("BigFatFish was uninstalled", removed);
                        return;
                    }
                    System.err.println("Waiting for BigFatFish provider: " + e);
                    handler.postDelayed(this, RECONNECT_MS);
                }
            }
        };
        handler.postDelayed(reconnect, RECONNECT_MS);
        Looper.loop();
    }
}
