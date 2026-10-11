package com.linxi.sidekeypopup;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 侧键部分的设置。
 *
 * 只剩两项：总开关、用哪条路读侧键。
 *
 * 「单击做什么」已经不在这里了——本应用只有一个用途，按侧键就是弹图片，
 * 所以那个选项连同它的配置项一起删掉了，免得留一个永远不会变的设置。
 *
 * 注意别和 {@link com.linxi.sidekeypopup.overlay.ImageSettings} 搞混：
 * 那个是图片本身的外观参数（大小、形状、羽化…），这个是侧键的行为。
 */
public final class Config {

    /**
     * 侧键的 Linux 按键码。
     * 735 = 0x2df = BTN_TRIGGER_HAPPY32，一加 / OPPO 的侧键默认就报这个码。
     */
    public static final int KEY_CODE = 735;

    /**
     * 按下多久之内松手算「单击」。
     *
     * 必须比系统的长按阈值短：这样用户按住不放时我们什么都不做，
     * 系统自己的长按功能照常触发，两边不会同时响应。
     */
    public static final int TAP_MAX_MS = 300;

    // ------------------------------------------------------------ 工作方式

    /** 自动挑一条能用的。 */
    public static final int SOURCE_AUTO = 0;
    /** 强制用无障碍。 */
    public static final int SOURCE_ACCESSIBILITY = 1;
    /** 强制用 Shizuku。 */
    public static final int SOURCE_SHIZUKU = 2;
    /** 强制用 Root。 */
    public static final int SOURCE_ROOT = 3;

    private static final String PREF = "sidekey";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_SOURCE = "source_mode";
    private static final String KEY_HIDE_FROM_RECENTS = "hide_from_recents";

    /** 总开关。关掉之后模块完全不看侧键。 */
    public boolean enabled = true;

    /**
     * 隐藏后台：不让自己出现在最近任务列表里。
     *
     * 最近任务里根本没有这张卡片，也就没得划——这是「防止被不小心划掉后台」最直接的做法。
     * 它只影响任务卡片，不影响服务：侧键监听照常，悬浮图也照常。
     *
     * 默认关。开了之后用户就不能从最近任务切回本应用了，得走桌面图标或常驻通知，
     * 这改变了使用习惯，得让用户自己选。
     */
    public boolean hideFromRecents = false;

    /**
     * 工作方式，默认自动。
     *
     * 自动时的优先级是 **Shizuku > Root > 无障碍**，不是把无障碍放最前面。
     * 原因是无障碍只能看到「走到无障碍层」的按键，而 OPPO / 一加 的侧键
     * 在系统策略的 interceptKeyBeforeQueueing 阶段就被厂商逻辑消化掉了，
     * 根本到不了无障碍。读 /dev/input 是绕过整条输入管线的，更可靠。
     */
    public int sourceMode = SOURCE_AUTO;

    public static Config load(Context context) {
        Config config = new Config();
        SharedPreferences sp = prefs(context);
        config.enabled = sp.getBoolean(KEY_ENABLED, true);
        config.sourceMode = sp.getInt(KEY_SOURCE, SOURCE_AUTO);
        config.hideFromRecents = sp.getBoolean(KEY_HIDE_FROM_RECENTS, false);
        return config;
    }

    public void save(Context context) {
        prefs(context).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putInt(KEY_SOURCE, sourceMode)
                .putBoolean(KEY_HIDE_FROM_RECENTS, hideFromRecents)
                .apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
