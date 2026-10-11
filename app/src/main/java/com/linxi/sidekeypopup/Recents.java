package com.linxi.sidekeypopup;

import android.app.ActivityManager;
import android.content.Context;

/**
 * 「隐藏后台」：把本应用从最近任务列表里摘掉。
 *
 * 最近任务里没有这张卡片，也就没得划——这是「防止被不小心划掉后台」最直接的做法。
 *
 * 为什么不用清单里的 {@code android:excludeFromRecents}：那是静态属性，装上就定死了，
 * 而这是个必须由用户随时切换的开关。改用 {@link ActivityManager.AppTask#setExcludeFromRecents}，
 * 它是运行时接口，而且**能设回 false**（{@code Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS}
 * 只能把标志加上，没法取消）。
 *
 * 注意 {@code Activity} 上没有 setExcludeFromRecents 这个方法，别找错地方——
 * 只有 {@code AppTask} 上有。
 *
 * 它只管任务卡片，不管服务：侧键监听另有 {@code SideKeyService} 的
 * {@code stopWithTask="false"} 兜底，两者互不依赖。
 */
public final class Recents {

    private Recents() {
    }

    /** 按当前设置应用一遍。主界面每次回到前台都会调，保证任务重建后依然生效。 */
    public static void apply(Context context) {
        apply(context, Config.load(context).hideFromRecents);
    }

    /** 把本应用的所有任务标记成「进 / 不进最近任务」。 */
    public static void apply(Context context, boolean exclude) {
        try {
            ActivityManager manager =
                    (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) {
                return;
            }
            for (ActivityManager.AppTask task : manager.getAppTasks()) {
                task.setExcludeFromRecents(exclude);
            }
        } catch (Throwable ignored) {
            // 个别 ROM 不支持，不影响主流程
        }
    }
}
