package com.linxi.sidekeypopup.input;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.IOException;

/**
 * Root 那条路。
 *
 * 「申请 root」就是跑一次 `su -c id`——root 管理器（Magisk / KernelSU 等）会自己弹授权框。
 * 授权结果缓存在 SharedPreferences 里，避免每次启动都去戳 su。
 */
public final class RootShell {

    private static final String TAG = "SideKeyPopup";
    private static final String PREF = "sidekey";
    private static final String KEY_GRANTED = "root_granted";

    /** 申请 root 时给用户的反应时间。 */
    private static final long REQUEST_TIMEOUT_MS = 30000L;

    /** Root 身份的命令执行器。 */
    public static final CommandRunner RUNNER = new CommandRunner() {
        @Override
        public Process start(String command) throws IOException {
            return Runtime.getRuntime().exec(new String[]{"su", "-c", command});
        }

        @Override
        public String output(String command, long timeoutMs) {
            try {
                return CommandRunner.readAll(start(command), timeoutMs);
            } catch (Throwable error) {
                return null;
            }
        }
    };

    private RootShell() {
    }

    /** 弹 root 授权框并等结果。**必须在子线程调用**，最长会阻塞 30 秒。 */
    public static boolean request(Context context) {
        String output = RUNNER.output("id", REQUEST_TIMEOUT_MS);
        boolean granted = output != null && output.contains("uid=0");
        setGranted(context, granted);
        Log.i(TAG, "root request result: " + granted);
        return granted;
    }

    /** 上次申请的结果。没申请过就是 false。 */
    public static boolean isGranted(Context context) {
        return prefs(context).getBoolean(KEY_GRANTED, false);
    }

    public static void setGranted(Context context, boolean granted) {
        prefs(context).edit().putBoolean(KEY_GRANTED, granted).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
