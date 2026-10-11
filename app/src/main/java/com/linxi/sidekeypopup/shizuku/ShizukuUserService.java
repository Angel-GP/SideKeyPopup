package com.linxi.sidekeypopup.shizuku;

import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.linxi.sidekeypopup.Config;
import com.linxi.sidekeypopup.input.CommandRunner;
import com.linxi.sidekeypopup.input.InputDeviceFinder;
import com.linxi.sidekeypopup.input.ShellKeySource;

import java.io.IOException;

/**
 * 这个类**不是**普通的 Android Service，而是 Shizuku 的用户服务：
 * Shizuku 会用我们的 APK 起一个 shell 身份的进程，然后 new 出这个对象。
 *
 * 所以这里不能用 Context 相关的 API，只能干「读 /dev/input」这种活。
 * 读到的按键通过 {@link IKeyCallback} 推回应用进程，由那边决定要不要拉起 App。
 */
public class ShizukuUserService extends IUserService.Stub {

    private static final String TAG = "SideKeyPopup";

    private ShellKeySource source;

    /** Shizuku 会用无参构造；带 Context 的构造是为了兼容。 */
    public ShizukuUserService() {
    }

    public ShizukuUserService(Context context) {
        // 用不上 Context，留着只是为了兼容两种构造方式。
    }

    @Override
    public void destroy() {
        stop();
        System.exit(0);
    }

    @Override
    public void exit() {
        stop();
        System.exit(0);
    }

    @Override
    public void start(String devicePath, IKeyCallback callback) {
        stop();

        // 这个进程已经是 shell 身份了，直接 exec 就行，不需要再套 su。
        CommandRunner runner = new CommandRunner() {
            @Override
            public java.lang.Process start(String command) throws IOException {
                return Runtime.getRuntime().exec(new String[]{"sh", "-c", command});
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

        source = new ShellKeySource(runner, Config.KEY_CODE, (action, code, eventTimeMs) -> {
            try {
                callback.onKey(action, code, eventTimeMs);
            } catch (Throwable error) {
                safeLog("callback failed: " + error);
            }
        });
        source.start();
        safeLog("user service watching, uid=" + Process.myUid());
    }

    @Override
    public void stop() {
        if (source != null) {
            source.stop();
            source = null;
        }
    }

    @Override
    public String describe() {
        return "uid=" + Process.myUid();
    }

    @Override
    public String status() {
        ShellKeySource current = source;
        if (current == null) {
            return "";
        }
        String watching = current.getWatching();
        return watching == null || watching.isEmpty() ? "…" : watching;
    }

    private static void safeLog(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
