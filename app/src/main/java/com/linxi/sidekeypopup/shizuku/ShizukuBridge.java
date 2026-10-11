package com.linxi.sidekeypopup.shizuku;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Log;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuProvider;
import rikka.sui.Sui;

/**
 * 对 Shizuku / Sui 的一层薄封装。
 *
 * 这里要特别说明 binder 是怎么来的，因为搞错了就会出现「明明 Shizuku 在运行，却申请不了权限」：
 *
 * Shizuku 的 binder **不是我们主动去拿的，是 Shizuku 主动推过来的**。
 * 它的服务端启动时（以及应用被安装时）会扫描所有声明了
 * `moe.shizuku.manager.permission.API_V23` 的应用，然后调用这些应用的
 * `ShizukuProvider.call("sendBinder", ...)`，把 binder 塞进那个进程。
 * 只有收到 binder 之后，`Shizuku.requestPermission()` 才有东西可以调。
 *
 * 所以「装了 Shizuku 且在运行」和「本应用拿到了 binder」是两件事。
 * 前者看得到，后者只能等推送；如果本应用是在 Shizuku 启动之后才装的，
 * 就得把 Shizuku 停掉再启动一次（或者重启手机）。
 *
 * 这也是为什么下面把状态拆得这么细——不然用户只会看到一个「未授权」，
 * 完全不知道卡在哪一步。
 */
public final class ShizukuBridge {

    /** 申请权限用的请求码，回调里会原样带回来。 */
    public static final int REQUEST_CODE = 4001;

    /** 读 /dev/input 用的是用户服务（UserService），需要 Shizuku 13 以上。 */
    public static final int REQUIRED_VERSION = 13;

    private static final String TAG = "SideKeyPopup";

    /**
     * Shizuku 应用的包名。
     *
     * 注意别写成 `moe.shizuku.manager`——那是**权限名**的前缀
     * （`moe.shizuku.manager.permission.API_V23`），不是包名。
     * Shizuku 从 v11 起把代码包名改成了 `rikka.shizuku`，
     * 但 applicationId 一直保留着 `moe.shizuku.privileged.api`。
     *
     * 这里直接读库里的常量，省得以后再写错。
     */
    private static final String SHIZUKU_PACKAGE = ShizukuProvider.MANAGER_APPLICATION_ID;

    private static final String DOWNLOAD_URL = "https://shizuku.rikka.app/download/";

    public enum State {
        /** 设备上没装 Shizuku。 */
        NOT_INSTALLED,
        /** 装了，但本应用没拿到它的 binder——Shizuku 没运行，或者还没推送过来。 */
        NOT_RUNNING,
        /** 拿到了 binder，但服务端版本太旧。 */
        OUTDATED,
        /** 在运行，但没授权给本应用。 */
        DENIED,
        /** 可以用。 */
        GRANTED
    }

    private ShizukuBridge() {
    }

    /**
     * 把「装没装 / 跑没跑 / 授权没有」合成一个状态。
     *
     * 判断顺序很重要：**先看 binder，再看包名**。
     * 反过来的话，只要包名查不到（包可见性、Sui 没有管理器应用、以后包名又改了…），
     * 就会在 Shizuku 明明能用的情况下报「未安装」。
     */
    public static State state(Context context) {
        if (isBinderAlive()) {
            if (serverVersion() < 11) {
                return State.OUTDATED;
            }
            return hasPermission() ? State.GRANTED : State.DENIED;
        }
        // binder 还没到：是压根没有 Shizuku，还是装了但没跑起来 / 还没推送过来？
        return hasProvider(context) ? State.NOT_RUNNING : State.NOT_INSTALLED;
    }

    /** 这条路现在能不能用来读侧键。 */
    public static boolean isAvailable(Context context) {
        return state(context) == State.GRANTED;
    }

    /** Shizuku 应用的包名。 */
    public static String shizukuPackageName() {
        return SHIZUKU_PACKAGE;
    }

    /** 设备上有没有 Shizuku 或者 Sui。 */
    public static boolean hasProvider(Context context) {
        return isSui() || isShizukuInstalled(context);
    }

    /** 是不是跑在 Sui 上（Magisk 模块形态，没有管理器应用）。 */
    public static boolean isSui() {
        try {
            return Sui.isSui();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 设备上有没有装 Shizuku 应用。 */
    public static boolean isShizukuInstalled(Context context) {
        try {
            context.getPackageManager().getPackageInfo(SHIZUKU_PACKAGE, 0);
            return true;
        } catch (Throwable error) {
            Log.i(TAG, "Shizuku package " + SHIZUKU_PACKAGE + " not visible: " + error);
            return false;
        }
    }

    /** 本进程有没有收到 Shizuku 的 binder。这是能不能申请权限的前提。 */
    public static boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Shizuku 服务端版本；拿不到返回 -1。**必须先确认 binder 在**，否则会抛。 */
    public static int serverVersion() {
        try {
            return Shizuku.getVersion();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    /** 本应用有没有拿到 Shizuku 授权。 */
    public static boolean hasPermission() {
        try {
            return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 之前是不是被拒绝过——这种情况下 requestPermission 不会再弹窗。 */
    public static boolean wasDeniedBefore() {
        try {
            return Shizuku.shouldShowRequestPermissionRationale();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * 发起授权请求。
     *
     * @return true 表示请求真的发出去了。返回 false 时调用方必须如实告诉用户，
     *         不能想当然地弹「已发起」——之前就是这里吞掉了异常，
     *         结果 binder 没到或者服务端太旧时，界面上显示成功但什么都不会发生。
     */
    public static boolean requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE);
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Shizuku requestPermission failed", error);
            return false;
        }
    }

    /** 打开 Shizuku 应用本身。 */
    public static void openShizukuApp(Context context) {
        try {
            Intent intent = context.getPackageManager().getLaunchIntentForPackage(SHIZUKU_PACKAGE);
            if (intent == null) {
                openDownloadPage(context);
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Throwable error) {
            Log.w(TAG, "cannot open Shizuku", error);
        }
    }

    /** 没装 Shizuku 时给个下载页。 */
    public static void openDownloadPage(Context context) {
        try {
            context.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable error) {
            Log.w(TAG, "cannot open download page", error);
        }
    }
}
