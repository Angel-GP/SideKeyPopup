package com.linxi.sidekeypopup.input;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;
import android.view.KeyEvent;

import com.linxi.sidekeypopup.Config;
import com.linxi.sidekeypopup.overlay.OverlayService;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 侧键识别的唯一入口。无障碍、Root、Shizuku 三条路都把按键丢进这里。
 *
 * 这里只认「单击」：按下到松手短于 {@link Config#TAP_MAX_MS} 才算一次单击。
 * 长按一律不处理，也从不消费按键（无障碍的 onKeyEvent 永远返回 false），
 * 所以系统自己的长按功能完全不受影响。
 */
public final class SideKeyEngine {

    public static final int SOURCE_NONE = 0;
    public static final int SOURCE_ACCESSIBILITY = 1;
    public static final int SOURCE_ROOT = 2;
    public static final int SOURCE_SHIZUKU = 3;

    private static final String TAG = "SideKeyPopup";
    private static final SideKeyEngine INSTANCE = new SideKeyEngine();

    /**
     * 真正「执行单击动作」的地方。抽成接口是为了能在单元测试里换成假的。
     *
     * 动作是固定的：把图片弹出来。不再有「打开某个 App」那个分支。
     */
    public interface Action {
        void perform(Context context, Config config);
    }

    private final AtomicLong lastCapturedAt = new AtomicLong(0L);
    private final List<Runnable> observers = new CopyOnWriteArrayList<>();

    private volatile int activeSource = SOURCE_NONE;
    private volatile String sourceDetail = "";
    // 字段叫 actionRunner 而不是 action：onKey 的第一个参数就叫 action，
    // 同名的话字段会被参数遮住。
    private volatile Action actionRunner = SideKeyEngine::performAction;
    private Context appContext;

    private boolean pressed;
    private long downAt;

    private SideKeyEngine() {
    }

    public static SideKeyEngine get() {
        return INSTANCE;
    }

    /** 由 Application 调用一次，之后各处都不用再传 Context。 */
    public void attach(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** 换掉执行动作。传 null 恢复成真实实现（单元测试里验证真实分发路径时用）。 */
    public void setAction(Action action) {
        this.actionRunner = action == null ? SideKeyEngine::performAction : action;
    }

    // ---------------------------------------------------------------- 状态

    public int getActiveSource() {
        return activeSource;
    }

    public void setActiveSource(int source) {
        if (activeSource != source) {
            activeSource = source;
            // 换了来源，上一条来源的「在盯哪个设备」就作废了。
            sourceDetail = "";
            notifyObservers();
        }
    }

    /**
     * 当前来源正在盯的设备之类的细节，用于界面显示。
     *
     * Shizuku 那条路的设备发现发生在用户服务进程里，应用进程看不到，
     * 只能靠回传。没有这个的话，「来源显示 Shizuku 但按了没反应」就没法查。
     */
    public String getSourceDetail() {
        return sourceDetail;
    }

    public void setSourceDetail(String detail) {
        String value = detail == null ? "" : detail;
        if (!value.equals(sourceDetail)) {
            sourceDetail = value;
            notifyObservers();
        }
    }

    /** 最近一次抓到侧键的时间（毫秒时间戳），0 表示还没抓到过。 */
    public long getLastCapturedAt() {
        return lastCapturedAt.get();
    }

    public void addObserver(Runnable observer) {
        observers.add(observer);
    }

    public void removeObserver(Runnable observer) {
        observers.remove(observer);
    }

    /** 丢掉正在跟踪的那次按键。切换来源、改配置时调用，免得把半截按压状态带到新配置里。 */
    public void resetPress() {
        pressed = false;
        downAt = 0L;
    }

    /** 回到初始状态。单元测试每个用例前调用。 */
    public void reset() {
        resetPress();
        lastCapturedAt.set(0L);
        activeSource = SOURCE_NONE;
        sourceDetail = "";
        observers.clear();
    }

    private void notifyObservers() {
        for (Runnable observer : observers) {
            try {
                observer.run();
            } catch (Throwable error) {
                Log.w(TAG, "observer failed", error);
            }
        }
    }

    // ---------------------------------------------------------------- 按键

    /**
     * 所有来源统一走这里。
     *
     * @param action      {@link KeyEvent#ACTION_DOWN} 或 {@link KeyEvent#ACTION_UP}
     * @param keyCode     拿得到就传，拿不到传 0
     * @param scanCode    拿得到就传，拿不到传 0
     * @param eventTimeMs 事件自带的时间戳；传 0 表示用当前时间
     */
    public void onKey(int action, int keyCode, int scanCode, long eventTimeMs) {
        Context context = appContext;
        if (context == null) {
            return;
        }
        if (!isSideKey(keyCode, scanCode)) {
            return;
        }

        Config config = Config.load(context);
        if (!config.enabled) {
            return;
        }

        lastCapturedAt.set(System.currentTimeMillis());

        long now = eventTimeMs > 0 ? eventTimeMs : SystemClock.uptimeMillis();
        if (action == KeyEvent.ACTION_DOWN) {
            if (!pressed) {
                pressed = true;
                downAt = now;
            }
            notifyObservers();
            return;
        }

        if (action == KeyEvent.ACTION_UP) {
            if (!pressed) {
                notifyObservers();
                return;
            }
            pressed = false;
            if (isTap(downAt, now)) {
                actionRunner.perform(context, config);
            } else {
                // 长按：什么都不做，留给系统。
                Log.i(TAG, "long press (" + (now - downAt) + "ms), left to the system");
            }
            notifyObservers();
        }
    }

    /** 这个键是不是侧键。keyCode 和 scanCode 都要看：有些 ROM 只给其中一个。 */
    public static boolean isSideKey(int keyCode, int scanCode) {
        return keyCode == Config.KEY_CODE || scanCode == Config.KEY_CODE;
    }

    /** 按下到松手够不够短，够短才算单击。 */
    public static boolean isTap(long downAt, long upAt) {
        return upAt - downAt < Config.TAP_MAX_MS;
    }

    /**
     * 默认动作：弹出图片。
     *
     * 这里就是一次普通的同进程调用。合并成一个应用之后，
     * 原来那套「另一个应用 + 导出接口 + 后台起前台服务被 Android 12 拦掉再退 Activity」
     * 的东西全都不需要了。
     */
    private static void performAction(Context context, Config config) {
        OverlayService.show(context);
    }
}
