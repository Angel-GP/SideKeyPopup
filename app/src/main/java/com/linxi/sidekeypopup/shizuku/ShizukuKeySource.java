package com.linxi.sidekeypopup.shizuku;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.ApplicationInfo;
import android.os.IBinder;
import android.util.Log;

import com.linxi.sidekeypopup.input.SideKeyEngine;

import rikka.shizuku.Shizuku;

/**
 * 走 Shizuku 时，真正读按键的代码在 {@link ShizukuUserService} 里，
 * 它以 shell 身份跑在另一个进程。这里负责把那个服务绑起来，
 * 并把它推回来的按键事件转交给 {@link SideKeyEngine}。
 */
public final class ShizukuKeySource {

    private static final String TAG = "SideKeyPopup";

    /**
     * 用户服务的实现版本。
     * 只要 {@link IUserService} 的接口或 {@link ShizukuUserService} 的行为变了就得加一，
     * 否则 Shizuku 会直接复用还在跑的旧进程，新代码根本不会生效。
     */
    private static final int USER_SERVICE_VERSION = 2;

    private final Context context;

    private Shizuku.UserServiceArgs args;
    private IUserService service;
    private volatile boolean bound;

    private final IKeyCallback callback = new IKeyCallback.Stub() {
        @Override
        public void onKey(int action, int code, long eventTimeMs) {
            SideKeyEngine.get().onKey(action, 0, code, eventTimeMs);
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IUserService.Stub.asInterface(binder);
            try {
                service.start("", callback);
                Log.i(TAG, "shizuku user service started: " + service.describe());
                pollWatching();
            } catch (Throwable error) {
                Log.w(TAG, "shizuku user service start failed", error);
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
        }
    };

    /**
     * 问用户服务在盯哪些设备。
     *
     * 设备发现是异步的（要跑 getevent），所以这里轮询几次；
     * 拿到结果就停，别一直占着 binder。
     */
    private void pollWatching() {
        Thread poller = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                try {
                    Thread.sleep(500L);
                } catch (InterruptedException interrupted) {
                    return;
                }
                IUserService current = service;
                if (!bound || current == null) {
                    return;
                }
                String status;
                try {
                    status = current.status();
                } catch (Throwable error) {
                    Log.w(TAG, "cannot read user service status", error);
                    return;
                }
                if (status != null && !status.isEmpty() && !"…".equals(status)) {
                    SideKeyEngine.get().setSourceDetail(status);
                    return;
                }
            }
        }, "shizuku-status");
        poller.setDaemon(true);
        poller.start();
    }

    public ShizukuKeySource(Context context) {
        this.context = context.getApplicationContext();
    }

    public void start() {
        if (bound) {
            return;
        }
        bound = true;
        try {
            args = new Shizuku.UserServiceArgs(
                    new ComponentName(context.getPackageName(), ShizukuUserService.class.getName()))
                    .daemon(false)
                    .processNameSuffix("sidekey")
                    .debuggable(isDebuggable())
                    .version(USER_SERVICE_VERSION);
            Shizuku.bindUserService(args, connection);
            Log.i(TAG, "binding shizuku user service");
        } catch (Throwable error) {
            bound = false;
            Log.w(TAG, "bind shizuku user service failed", error);
        }
    }

    /** 不用 BuildConfig：AGP 8 默认不生成它。 */
    private boolean isDebuggable() {
        return (context.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    public void stop() {
        if (!bound) {
            return;
        }
        bound = false;
        try {
            if (service != null) {
                service.stop();
            }
        } catch (Throwable ignored) {
            // ignore
        }
        service = null;
        try {
            if (args != null) {
                Shizuku.unbindUserService(args, connection, true);
            }
        } catch (Throwable ignored) {
            // ignore
        }
        args = null;
    }
}
