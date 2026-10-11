package com.linxi.sidekeypopup;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;

import androidx.core.content.ContextCompat;

import com.linxi.sidekeypopup.input.SideKeyAccessibilityService;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** 各项权限的查询与跳转。 */
public final class Permissions {

    public static final int REQUEST_NOTIFICATIONS = 5001;
    public static final int REQUEST_OVERLAY = 5002;

    private Permissions() {
    }

    // ------------------------------------------------------------ 悬浮窗

    /**
     * 「显示在其他应用上层」开了没。
     *
     * 这是整个应用最基础的一项：没有它图片弹不出来，
     * 而失败方式是「按了侧键什么都没发生」，最难查，所以要能随时确认。
     */
    public static boolean isOverlayAllowed(Context context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M) {
            return true;
        }
        try {
            return Settings.canDrawOverlays(context);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 跳到悬浮窗权限页面。 */
    public static void requestOverlay(Activity activity) {
        try {
            activity.startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())), REQUEST_OVERLAY);
        } catch (Throwable ignored) {
            openAppDetails(activity);
        }
    }

    // ------------------------------------------------------------ 无障碍

    /** 无障碍服务是不是真的开着。 */
    public static boolean isAccessibilityEnabled(Context context) {
        try {
            AccessibilityManager manager =
                    (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager == null) {
                return false;
            }
            ComponentName me = new ComponentName(context, SideKeyAccessibilityService.class);
            List<android.accessibilityservice.AccessibilityServiceInfo> enabled =
                    manager.getEnabledAccessibilityServiceList(
                            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (android.accessibilityservice.AccessibilityServiceInfo info : enabled) {
                if (info.getResolveInfo() == null || info.getResolveInfo().serviceInfo == null) {
                    continue;
                }
                ComponentName other = new ComponentName(
                        info.getResolveInfo().serviceInfo.packageName,
                        info.getResolveInfo().serviceInfo.name);
                if (me.equals(other)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void openAccessibilitySettings(Context context) {
        try {
            context.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
            openAppDetails(context);
        }
    }

    // ------------------------------------------------------------ 电池优化

    public static boolean isIgnoringBatteryOptimizations(Context context) {
        try {
            PowerManager manager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            return manager != null && manager.isIgnoringBatteryOptimizations(context.getPackageName());
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static void requestIgnoreBatteryOptimizations(Context context) {
        try {
            context.startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                    .setData(Uri.parse("package:" + context.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
            openBatteryOptimizationSettings(context);
        }
    }

    private static void openBatteryOptimizationSettings(Context context) {
        try {
            context.startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
            openAppDetails(context);
        }
    }

    // ------------------------------------------------------------ 通知

    public static boolean hasNotificationPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static void requestNotificationPermission(Activity activity) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            activity.requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQUEST_NOTIFICATIONS);
        }
    }

    public static void openNotificationSettings(Context context) {
        try {
            context.startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.getPackageName())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
            openAppDetails(context);
        }
    }

    // ------------------------------------------------------------ 厂商后台设置

    /**
     * ColorOS / OPPO / 一加 的「自启动管理」列表页候选。
     *
     * 为什么不直接用 {@link Settings#ACTION_APPLICATION_DETAILS_SETTINGS}：那是系统原生的
     * 应用信息页，而 ColorOS 把自启动开关收进了厂商自己的页面，跳过去用户在这一页里
     * 根本找不到「自启动」这一项——之前就是这么错的。
     *
     * 各家、各代 ColorOS 的包名和类名都不一样（coloros → oplus、safecenter → safe），
     * 所以这里按候选逐个试，谁能解析出来就用谁。
     */
    private static final String[][] AUTO_START_PAGES = {
            // ColorOS 12 及以前
            {"com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"},
            {"com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"},
            // ColorOS 13 起改用 oplus 命名
            {"com.oplus.safecenter", "com.oplus.safecenter.permission.startup.StartupAppListActivity"},
            {"com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity"},
            // 更早的 OPPO
            {"com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"},
            // 老款一加（氢 / 氧 OS 时代）
            {"com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"},
    };

    /**
     * 可能承载「自启动管理」的厂商包。
     *
     * 写死的候选表厂商改个类名就全废——ColorOS 17 上就是这样。所以显式候选全没命中时，
     * 会到这几个包里按类名扫一遍（见 {@link #discoverAutoStartPages}）。
     * 这些包同样必须在清单 {@code <queries>} 里声明，否则 Android 11+ 连包都看不见。
     */
    private static final String[] VENDOR_PACKAGES = {
            "com.coloros.safecenter",
            "com.oplus.safecenter",
            "com.oppo.safe",
            "com.oneplus.security",
            "com.coloros.phonemanager",
            "com.oplus.phonemanager",
            "com.coloros.oppoguardelf",
            "com.oplus.battery",
    };

    /** 类名里带这些词就当成自启动相关。越靠前越像，用来排序。 */
    private static final String[] AUTO_START_HINTS = {
            "startupapplist", "autostartapplist", "startupapp", "startup", "autostart", "bootstart",
    };

    /**
     * 打开厂商的「自启动管理」列表页。
     *
     * @return 有没有跳成功。返回 false 说明这台机器不是 ColorOS、或者厂商改了包名又改了类名，
     *         调用方应该把手动路径告诉用户，而不是默默跳到没用的页面。
     */
    public static boolean openAutoStartSettings(Context context) {
        for (ComponentName page : autoStartCandidates(context)) {
            if (!exists(context, page)) {
                continue;
            }
            try {
                context.startActivity(new Intent()
                        .setComponent(page)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return true;
            } catch (Throwable ignored) {
                // 组件在、但拉不起来（没导出、被签名级权限挡住），换下一个
            }
        }
        return false;
    }

    /** 先试写死的候选，再试运行时扫出来的。 */
    static List<ComponentName> autoStartCandidates(Context context) {
        List<ComponentName> pages = new ArrayList<>();
        for (String[] page : AUTO_START_PAGES) {
            pages.add(new ComponentName(page[0], page[1]));
        }
        pages.addAll(discoverAutoStartPages(context));
        return pages;
    }

    /**
     * 到厂商包里按类名找自启动页。
     *
     * 写死的候选表是「包名 + 类名」两段都猜对才行；厂商换一代 ROM 就可能全废。
     * 这一层只要求包名对得上，类名靠扫——猜中的概率高得多。
     */
    private static List<ComponentName> discoverAutoStartPages(Context context) {
        List<ComponentName> found = new ArrayList<>();
        PackageManager manager = context.getPackageManager();
        for (String pkg : VENDOR_PACKAGES) {
            ActivityInfo[] activities;
            try {
                PackageInfo info = manager.getPackageInfo(pkg, PackageManager.GET_ACTIVITIES);
                activities = info.activities;
            } catch (Throwable ignored) {
                continue;   // 这台机器上没装这个包
            }
            if (activities == null) {
                continue;
            }
            for (ActivityInfo activity : activities) {
                if (activity.name != null && hintRank(activity.name) >= 0) {
                    found.add(new ComponentName(pkg, activity.name));
                }
            }
        }
        found.sort(Comparator.comparingInt(page -> hintRank(page.getClassName())));
        return found;
    }

    /** 类名像不像自启动页；不像返回 -1。 */
    private static int hintRank(String className) {
        String lower = className.toLowerCase(Locale.ROOT);
        for (int i = 0; i < AUTO_START_HINTS.length; i++) {
            if (lower.contains(AUTO_START_HINTS[i])) {
                return i;
            }
        }
        return -1;
    }

    /**
     * 这个组件在不在。
     *
     * 不能用 resolveActivity：它按 intent-filter 匹配，而厂商这些设置页基本都没有
     * CATEGORY_DEFAULT，会被判成「不存在」——候选一个都命中不了，直接掉进「没找到」。
     * 这里直接查组件本身，不掺 intent-filter 那套。
     *
     * Android 11 起有包可见性限制：没在清单 {@code <queries>} 里声明过的包，
     * 这里会抛 NameNotFoundException，所以那几个厂商包必须在清单里声明。
     */
    private static boolean exists(Context context, ComponentName component) {
        try {
            context.getPackageManager().getActivityInfo(component, 0);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------ 应用详情

    /** 本应用的应用信息页。ColorOS 上「隐藏后台」「后台弹出界面」这类逐应用开关都在这里。 */
    public static void openAppDetails(Context context) {
        try {
            context.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:" + context.getPackageName()))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
            // 实在打不开就算了
        }
    }
}
