package rikka.sui.shell;

import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.system.Os;
import android.text.TextUtils;

import java.io.File;
import java.util.Objects;

import rikka.rish.Rish;
import rikka.rish.RishConfig;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuApiConstants;
import rikka.sui.Sui;

/**
 * Sui shell entrypoint with dual backend support.
 *
 * <p>When the Sui daemon is available (root / Zygisk setup), the binder is acquired via
 * {@link Sui#init(String)}. Otherwise, fall back to the classic Shizuku path: request the
 * binder from {@code moe.shizuku.privileged.api} through the {@code REQUEST_BINDER}
 * broadcast. Both backends speak the same Shizuku binder protocol
 * ({@link ShizukuApiConstants#BINDER_DESCRIPTOR}), so a single Rish instance works with
 * whichever binder arrives first.
 */
public class Shell extends Rish {

    private static String[] args;
    private static String packageName;
    private static Handler handler;

    private static final Binder receiverBinder = new Binder() {

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code == 1) {
                IBinder binder = data.readStrongBinder();

                if (binder != null) {
                    handler.post(() -> onBinderReceived(binder));
                } else {
                    System.err.println("Server is not running");
                    System.err.flush();
                    System.exit(1);
                }
                return true;
            }
            return super.onTransact(code, data, reply, flags);
        }
    };

    private static void onBinderReceived(IBinder binder) {
        RishConfig.init(binder, ShizukuApiConstants.BINDER_DESCRIPTOR, 30000);
        Shizuku.onBinderReceived(binder, packageName);
        Shizuku.addBinderReceivedListenerSticky(() -> {
            int version = Shizuku.getVersion();
            if (version < 12) {
                System.err.println("Rish requires server 12 (running " + version + ")");
                System.err.flush();
                System.exit(1);
            }
            new Shell().start(args);
        });
    }

    private static void requestBinderFromShizuku() throws RemoteException {
        Bundle data = new Bundle();
        data.putBinder("binder", receiverBinder);

        Intent intent = new Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
                .setPackage("moe.shizuku.privileged.api")
                .addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                .putExtra("data", data);

        IBinder amBinder = ServiceManager.getService("activity");
        IActivityManager am;
        if (Build.VERSION.SDK_INT >= 26) {
            am = IActivityManager.Stub.asInterface(amBinder);
        } else {
            am = ActivityManagerNative.asInterface(amBinder);
        }

        // broadcastIntent will fail on Android 8.x
        //  com.android.server.am.ActivityManagerService.isInstantApp(ActivityManagerService.java:18547)
        //  com.android.server.am.ActivityManagerService.broadcastIntentLocked(ActivityManagerService.java:18972)
        //  com.android.server.am.ActivityManagerService.broadcastIntent(ActivityManagerService.java:19703)
        //
        try {
            am.broadcastIntent(null, intent, null, null, 0, null, null,
                    null, -1, null, true, false, 0);
        } catch (Throwable e) {
            if ((Build.VERSION.SDK_INT != Build.VERSION_CODES.O && Build.VERSION.SDK_INT != Build.VERSION_CODES.O_MR1)
                    || !Objects.equals(e.getMessage(), "Calling application did not provide package name")) {
                throw new RemoteException("broadcastIntent failed: " + e.getMessage());
            }

            System.err.println("broadcastIntent fails on Android 8.0 or 8.1, fallback to startActivity");
            System.err.flush();

            Intent activityIntent = Intent.createChooser(
                    new Intent("rikka.shizuku.intent.action.REQUEST_BINDER")
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT)
                            .putExtra("data", data),
                    "Request binder from Shizuku"
            );

            am.startActivityAsUser(null, packageName, activityIntent, null, null, null, 0, 0, null, null, Os.getuid() / 100000);
        }
    }

    @Override
    public void requestPermission(Runnable onGrantedRunnable) {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            onGrantedRunnable.run();
        } else if (Shizuku.shouldShowRequestPermissionRationale()) {
            System.err.println("Permission denied");
            System.err.flush();
            System.exit(1);
        } else {
            Shizuku.addRequestPermissionResultListener(new Shizuku.OnRequestPermissionResultListener() {
                @Override
                public void onRequestPermissionResult(int requestCode, int grantResult) {
                    Shizuku.removeRequestPermissionResultListener(this);

                    if (grantResult == PackageManager.PERMISSION_GRANTED) {
                        onGrantedRunnable.run();
                    } else {
                        System.err.println("Permission denied");
                        System.err.flush();
                        System.exit(1);
                    }
                }
            });
            Shizuku.requestPermission(0);
        }
    }

    public static void main(String[] args) {
        Shell.args = args;

        if (Os.getuid() == 2000) {
            packageName = "com.android.shell";
        } else {
            packageName = System.getenv("RISH_APPLICATION_ID");
            if (TextUtils.isEmpty(packageName) || "PKG".equals(packageName)) {
                System.err.println("RISH_APPLICATION_ID is not set, set this environment variable to the id of current application (package name)");
                System.err.flush();
                System.exit(1);
            }
        }

        String libPath = System.getProperty("sui.library.path");
        if (libPath != null) {
            RishConfig.setLibraryPath(new File(libPath).getAbsolutePath());
        }

        if (Looper.getMainLooper() == null) {
            Looper.prepareMainLooper();
        }

        handler = new Handler(Looper.getMainLooper());

        // Primary backend: Sui daemon (root / Zygisk).
        boolean suiOk = false;
        try {
            suiOk = Sui.init(packageName);
        } catch (Throwable tr) {
            System.err.println("Sui init failed: " + tr.getMessage());
            System.err.flush();
        }

        if (suiOk) {
            IBinder binder = Shizuku.getBinder();
            handler.post(() -> onBinderReceived(binder));
            Looper.loop();
            System.exit(0);
            return;
        }

        // Fallback backend: classic Shizuku service.
        System.err.println("Sui unavailable, requesting binder from Shizuku...");
        System.err.flush();
        try {
            requestBinderFromShizuku();
        } catch (Throwable tr) {
            System.err.println("Unable to acquire the binder of Sui or Shizuku. Make sure Sui is installed / Shizuku is running.");
            tr.printStackTrace(System.err);
            System.err.flush();
            System.exit(1);
        }

        handler.postDelayed(() -> {
            System.err.println("Request timeout. The connection between the current app (" + packageName + ") and Shizuku app may be blocked by your system. " +
                    "Please disable all battery optimization features for both current app (" + packageName + ") and Shizuku app.");
            System.err.flush();
            System.exit(1);
        }, 5000);

        Looper.loop();
        System.exit(0);
    }
}
