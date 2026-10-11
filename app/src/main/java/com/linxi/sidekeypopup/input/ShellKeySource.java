package com.linxi.sidekeypopup.input;

import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 通过读 /dev/input/eventN 来识别侧键。
 *
 * Root 和 Shizuku 两条路用的是同一份实现，区别只在 {@link CommandRunner}：
 * Root 每条命令套一层 su，Shizuku 则是因为代码本身就跑在 shell 进程里。
 *
 * 可能同时盯好几个设备：按键码没被任何设备声明时，我们退回到「所有会报 KEY 的设备」，
 * 谁先报出来算谁的（同一个键只会从一个设备出来，不会重复触发）。
 */
public final class ShellKeySource {

    private static final String TAG = "SideKeyPopup";

    public interface Listener {
        void onKey(int action, int code, long eventTimeMs);
    }

    /** 设备定下来之后回报一次，给界面显示用。可以为 null。 */
    public interface DetailListener {
        void onWatching(String devices);
    }

    private final CommandRunner runner;
    private final int keyCode;
    private final Listener listener;
    private final DetailListener detailListener;

    private final List<Process> processes = new ArrayList<>();

    private volatile boolean running;
    private Thread thread;
    private volatile String watching = "";

    public ShellKeySource(CommandRunner runner, int keyCode, Listener listener) {
        this(runner, keyCode, listener, null);
    }

    public ShellKeySource(CommandRunner runner, int keyCode, Listener listener, DetailListener detailListener) {
        this.runner = runner;
        this.keyCode = keyCode;
        this.listener = listener;
        this.detailListener = detailListener;
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        thread = new Thread(this::loop, "sidekey-shell");
        thread.setDaemon(true);
        thread.start();
    }

    public synchronized void stop() {
        running = false;
        for (Process process : processes) {
            try {
                process.destroy();
            } catch (Throwable ignored) {
                // ignore
            }
        }
        processes.clear();
    }

    public boolean isRunning() {
        return running;
    }

    /** 正在盯的设备，用逗号分隔；还没确定时是空串。 */
    public String getWatching() {
        return watching;
    }

    private void loop() {
        try {
            List<String> devices = InputDeviceFinder.findAll(runner, keyCode);
            if (devices.isEmpty()) {
                log("no input device reports keys; giving up");
                return;
            }
            watching = join(devices);
            log("watching " + watching);
            if (detailListener != null) {
                detailListener.onWatching(watching);
            }

            List<Thread> readers = new ArrayList<>();
            for (String device : devices) {
                Thread reader = new Thread(() -> watch(device), "sidekey-" + device);
                reader.setDaemon(true);
                readers.add(reader);
                reader.start();
            }
            for (Thread reader : readers) {
                reader.join();
            }
        } catch (Throwable error) {
            log("shell source stopped: " + error);
        } finally {
            synchronized (this) {
                running = false;
                processes.clear();
            }
        }
    }

    private void watch(String device) {
        Process started;
        try {
            started = runner.start("cat " + device);
        } catch (Throwable error) {
            log("cannot read " + device + ": " + error);
            return;
        }

        synchronized (this) {
            if (!running) {
                started.destroy();
                return;
            }
            processes.add(started);
        }

        try {
            InputEventStream.read(
                    started.getInputStream(),
                    (action, code, eventTimeMs) -> {
                        if (code == keyCode) {
                            listener.onKey(action, code, eventTimeMs);
                        }
                    },
                    () -> running);
        } catch (Throwable error) {
            log("stopped reading " + device + ": " + error);
        }
    }

    private static String join(List<String> devices) {
        StringBuilder builder = new StringBuilder();
        for (String device : devices) {
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(device);
        }
        return builder.toString();
    }

    /** 这个类也会跑在 Shizuku 的用户服务进程里，那里日志不一定可用，所以全部兜住。 */
    private static void log(String message) {
        try {
            Log.i(TAG, message);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
