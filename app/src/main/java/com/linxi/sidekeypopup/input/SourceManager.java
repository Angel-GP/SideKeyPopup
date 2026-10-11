package com.linxi.sidekeypopup.input;

import android.content.Context;
import android.util.Log;

import com.linxi.sidekeypopup.Config;
import com.linxi.sidekeypopup.shizuku.ShizukuBridge;
import com.linxi.sidekeypopup.shizuku.ShizukuKeySource;

/**
 * 决定当前用哪条路读侧键，并在切换时把用不上的那条停掉。
 *
 * 自动时的优先级是 **Shizuku > Root > 无障碍**。
 *
 * 这里踩过一个坑：一开始把无障碍排在最前面，结果用户明明已经给了 Shizuku 权限，
 * 程序却一直在用无障碍——而无障碍在 OPPO / 一加 上根本看不到侧键
 * （厂商逻辑在 interceptKeyBeforeQueueing 阶段就把这个键消化了，
 * 事件到不了无障碍层），表现就是「权限都给了但按了没反应」。
 *
 * 读 /dev/input 是绕过整条输入管线的，所以更可靠，排在前面。
 * 用户也可以在界面上手动指定用哪条路。
 */
public final class SourceManager {

    private static final String TAG = "SideKeyPopup";
    private static final SourceManager INSTANCE = new SourceManager();

    private Context appContext;
    private boolean accessibilityAvailable;

    private ShellKeySource rootSource;
    private ShizukuKeySource shizukuSource;

    private SourceManager() {
    }

    public static SourceManager get() {
        return INSTANCE;
    }

    public void attach(Context context) {
        this.appContext = context.getApplicationContext();
    }

    public boolean isAccessibilityAvailable() {
        return accessibilityAvailable;
    }

    /** 无障碍服务连上 / 断开时调用。 */
    public void setAccessibilityAvailable(Context context, boolean available) {
        this.accessibilityAvailable = available;
        sync(context);
    }

    /** 设置或权限变化之后重新选一次路。 */
    public synchronized void sync(Context context) {
        Context ctx = context != null ? context.getApplicationContext() : appContext;
        if (ctx == null) {
            return;
        }
        appContext = ctx;

        SideKeyEngine engine = SideKeyEngine.get();
        Config config = Config.load(ctx);

        // 配置或来源要变了，正在跟踪的那次按压作废，免得按一半换了判定规则。
        engine.resetPress();

        if (!config.enabled) {
            stopShellSources();
            engine.setActiveSource(SideKeyEngine.SOURCE_NONE);
            SideKeyService.stop(ctx);
            return;
        }

        int chosen = chooseSource(config.sourceMode,
                accessibilityAvailable, RootShell.isGranted(ctx), ShizukuBridge.isAvailable(ctx));

        // 先把用不上的停掉，再起要用的，避免两条路同时往引擎里塞事件。
        if (chosen != SideKeyEngine.SOURCE_ROOT) {
            stopRootSource();
        }
        if (chosen != SideKeyEngine.SOURCE_SHIZUKU) {
            stopShizukuSource();
        }

        switch (chosen) {
            case SideKeyEngine.SOURCE_ROOT:
                if (rootSource == null) {
                    rootSource = new ShellKeySource(RootShell.RUNNER, Config.KEY_CODE,
                            this::forward, SideKeyEngine.get()::setSourceDetail);
                    rootSource.start();
                }
                break;
            case SideKeyEngine.SOURCE_SHIZUKU:
                if (shizukuSource == null) {
                    shizukuSource = new ShizukuKeySource(ctx);
                    shizukuSource.start();
                }
                break;
            default:
                break;
        }

        engine.setActiveSource(chosen);
        if (chosen == SideKeyEngine.SOURCE_ACCESSIBILITY) {
            // 无障碍由系统保活，不需要前台服务。
            SideKeyService.stop(ctx);
        } else if (chosen == SideKeyEngine.SOURCE_NONE) {
            SideKeyService.stop(ctx);
            Log.i(TAG, "no usable side-key source; leaving the side key to the system");
        } else {
            // 读 /dev/input 的那条路要在应用进程里一直挂着，得靠前台服务保命。
            SideKeyService.start(ctx);
        }
    }

    /**
     * 挑一条路。
     *
     * 指定的那条没权限时不硬撑，退回到自动——否则用户选错一次就彻底没反应了，
     * 而且界面上会显示成「未就绪」，看不出是选择的问题。
     *
     * 自动时 **Shizuku > Root > 无障碍**。无障碍排最后是有原因的：它只能看到
     * 走到无障碍层的按键，而 OPPO / 一加 的侧键在系统策略里就被厂商逻辑消化了，
     * 根本到不了无障碍。把无障碍放前面会导致「权限都给了但按了没反应」。
     */
    public static int chooseSource(int mode, boolean accessibility, boolean root, boolean shizuku) {
        switch (mode) {
            case Config.SOURCE_ACCESSIBILITY:
                if (accessibility) {
                    return SideKeyEngine.SOURCE_ACCESSIBILITY;
                }
                break;
            case Config.SOURCE_SHIZUKU:
                if (shizuku) {
                    return SideKeyEngine.SOURCE_SHIZUKU;
                }
                break;
            case Config.SOURCE_ROOT:
                if (root) {
                    return SideKeyEngine.SOURCE_ROOT;
                }
                break;
            default:
                break;
        }
        if (shizuku) {
            return SideKeyEngine.SOURCE_SHIZUKU;
        }
        if (root) {
            return SideKeyEngine.SOURCE_ROOT;
        }
        if (accessibility) {
            return SideKeyEngine.SOURCE_ACCESSIBILITY;
        }
        return SideKeyEngine.SOURCE_NONE;
    }

    /**
     * 把 {@link Config} 里的读取方式映射成引擎的来源常量。
     *
     * 两套常量**数值不一样**（比如 Config.SOURCE_SHIZUKU 是 2，而引擎的 2 是 Root），
     * 所以必须走这个函数，不能直接拿来比。放在这里是为了只有一处映射。
     */
    public static int engineSourceFor(int configMode) {
        switch (configMode) {
            case Config.SOURCE_ACCESSIBILITY:
                return SideKeyEngine.SOURCE_ACCESSIBILITY;
            case Config.SOURCE_SHIZUKU:
                return SideKeyEngine.SOURCE_SHIZUKU;
            case Config.SOURCE_ROOT:
                return SideKeyEngine.SOURCE_ROOT;
            default:
                return SideKeyEngine.SOURCE_NONE;
        }
    }

    private void forward(int action, int code, long eventTimeMs) {
        SideKeyEngine.get().onKey(action, 0, code, eventTimeMs);
    }

    private void stopShellSources() {
        stopRootSource();
        stopShizukuSource();
    }

    private void stopRootSource() {
        if (rootSource != null) {
            rootSource.stop();
            rootSource = null;
        }
    }

    private void stopShizukuSource() {
        if (shizukuSource != null) {
            shizukuSource.stop();
            shizukuSource = null;
        }
    }
}
