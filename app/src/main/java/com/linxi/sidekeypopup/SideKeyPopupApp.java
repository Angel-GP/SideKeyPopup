package com.linxi.sidekeypopup;

import android.app.Application;
import android.util.Log;

import com.linxi.sidekeypopup.input.SideKeyEngine;
import com.linxi.sidekeypopup.input.SourceManager;

import rikka.shizuku.Shizuku;

/**
 * 进程一起来就把引擎准备好，并按当前权限选一条读侧键的路。
 * 这样即使界面没打开（比如无障碍服务被系统拉起来），侧键也是工作的。
 */
public class SideKeyPopupApp extends Application {

    private static final String TAG = "SideKeyPopup";

    @Override
    public void onCreate() {
        super.onCreate();
        SideKeyEngine.get().attach(this);
        SourceManager.get().attach(this);
        watchShizuku();
        SourceManager.get().sync(this);
    }

    /**
     * Shizuku 的 binder 是它主动推过来的，可能比进程启动晚很多
     * （比如用户去把 Shizuku 启动起来）。所以这里挂上监听，
     * 一旦收到就重新选一次来源，不用等用户回主界面。
     */
    private void watchShizuku() {
        try {
            Shizuku.addBinderReceivedListenerSticky(() -> {
                Log.i(TAG, "Shizuku binder received");
                SourceManager.get().sync(SideKeyPopupApp.this);
            });
            Shizuku.addBinderDeadListener(() -> {
                Log.i(TAG, "Shizuku binder died");
                SourceManager.get().sync(SideKeyPopupApp.this);
            });
        } catch (Throwable error) {
            Log.w(TAG, "cannot watch Shizuku binder", error);
        }
    }
}
