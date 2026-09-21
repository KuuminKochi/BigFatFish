package io.github.kuuminkochi.bigfatfish;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import java.io.File;

/** Narrow command endpoint for the app and the authorized ADB shell helper. */
public final class ControlProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }

    @Override public Bundle call(String method, String arg, Bundle extras) {
        android.content.Context context = getContext();
        if (context == null) throw new IllegalStateException("Provider is not attached");
        int uid = Binder.getCallingUid();
        if (uid != 2000 && uid != Process.myUid()) {
            throw new SecurityException("Only the app or ADB shell may use BigFatFish controls");
        }
        if (method == null) throw new IllegalArgumentException("Missing control method");
        switch (method) {
            case "status":
                return state(context);
            case "enable":
                CompanionService.setDesiredEnabled(context, true);
                return state(context);
            case "disable":
                CompanionService.setDesiredEnabled(context, false);
                return state(context);
            case "register-helper":
                if (uid != 2000 || extras == null) {
                    throw new SecurityException("Helper registration requires ADB shell");
                }
                try {
                    CompanionService.registerHelper(extras.getBinder("helper"));
                } catch (android.os.RemoteException e) {
                    throw new IllegalStateException("Helper binder disconnected", e);
                }
                return state(context);
            case "shutdown-helper":
                if (uid != 2000) throw new SecurityException("Helper shutdown requires ADB shell");
                shutdownHelper();
                return state(context);
            case "import-private-pack":
                if (uid != 2000) throw new SecurityException("Private pack import requires ADB shell");
                File pending = new File(new File(context.getFilesDir(), "imports"), "pending.zip");
                if (!pending.isFile()) throw new IllegalArgumentException("Missing files/imports/pending.zip");
                try {
                    String id = PackImporter.importFile(context, pending);
                    Bundle result = state(context);
                    result.putString("packId", id);
                    return result;
                } catch (java.io.IOException e) {
                    throw new IllegalArgumentException("Private pack rejected", e);
                }
            default:
                throw new IllegalArgumentException("Unknown BigFatFish control method");
        }
    }

    private static Bundle state(android.content.Context context) {
        CompanionService service = CompanionService.current;
        Bundle result = new Bundle();
        result.putBoolean("enabled", SettingsStore.enabled(context));
        result.putBoolean("serviceBound", service != null);
        result.putBoolean("helperReady", CompanionService.helper != null
                && CompanionService.helper.isBinderAlive());
        result.putBoolean("running", CompanionService.running);
        result.putString("status", CompanionService.status);
        result.putLong("observedEvents", CompanionService.observedCount);
        result.putString("mode", service == null ? "offline" : service.modeName());
        return result;
    }

    private static void shutdownHelper() {
        IBinder bridge = CompanionService.helper;
        if (bridge == null || !bridge.isBinderAlive()) {
            throw new IllegalStateException("Helper is not connected");
        }
        Parcel request = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            request.writeInterfaceToken(AppIdentity.DESCRIPTOR);
            if (!bridge.transact(2, request, reply, 0)) {
                throw new IllegalStateException("Helper rejected shutdown");
            }
            reply.readException();
        } catch (android.os.RemoteException e) {
            throw new IllegalStateException("Helper disconnected", e);
        } finally {
            reply.recycle();
            request.recycle();
        }
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Use the shell-only call endpoint");
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw unsupported(); }
    @Override public String getType(Uri uri) { throw unsupported(); }
    @Override public Uri insert(Uri uri, ContentValues values) { throw unsupported(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw unsupported(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw unsupported(); }
}
