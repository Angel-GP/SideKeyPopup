package com.linxi.sidekeypopup.overlay;

import com.linxi.sidekeypopup.MainActivity;
import com.linxi.sidekeypopup.R;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 用 WindowManager 添加一个系统级悬浮窗（TYPE_APPLICATION_OVERLAY）：
 * 图片显示在所有应用上层，点击图片以外的任意位置即可关闭。
 * 所有外观都由 {@link ImageSettings} 决定，设置页改完调用 {@link #refresh(Context)} 即时生效。
 *
 * 合并成一个应用之后，这里只由同进程调用（侧键引擎、主界面、通知栏按钮），
 * 所以服务在清单里是 {@code exported="false"}，外面调不到也不需要调。
 */
public class OverlayService extends Service {

    /** 弹出。同进程调用用，不对外暴露。 */
    public static final String ACTION_SHOW = "com.linxi.sidekeypopup.action.SHOW";
    /** 收起。通知栏那个「关闭」按钮走的就是它。 */
    public static final String ACTION_CLOSE = "com.linxi.sidekeypopup.action.CLOSE";

    private static final String CHANNEL_ID = "sidekeypopup_overlay";
    private static final int NOTIFICATION_ID = 0x1010;

    /** 悬浮图当前是否正在显示（进程内）。 */
    private static boolean showing;

    private WindowManager windowManager;
    private FrameLayout rootView;        // 全屏遮罩层，负责"点外面关闭"
    private FrameLayout contentWrapper;  // 只用于动画的包装层
    private Drawable drawable;

    private boolean appeared;
    private boolean closing;

    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    /** 弹出悬浮图（幂等：已经在显示就按最新设置刷新）。 */
    public static void show(Context c) {
        Intent i = new Intent(c, OverlayService.class).setAction(ACTION_SHOW);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                c.startForegroundService(i);
            } else {
                c.startService(i);
            }
        } catch (Exception e) {
            Toast.makeText(c, R.string.need_overlay_permission, Toast.LENGTH_LONG).show();
        }
    }

    /** 收起悬浮图并结束服务。 */
    public static void hide(Context c) {
        c.stopService(new Intent(c, OverlayService.class));
    }

    /** 设置页保存后调用：正在显示才刷新，没显示就不打扰。 */
    public static void refresh(Context c) {
        if (showing) {
            show(c);
        }
    }

    public static boolean isShowing() {
        return showing;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        createChannel();
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
        } catch (Exception ignored) {
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent != null ? intent.getAction() : null;

        if (ACTION_CLOSE.equals(action)) {
            closeWithAnimation();
            return START_NOT_STICKY;
        }

        if (!Prefs.hasImage(this)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        // 注意：本项目的 ImageSettings 和系统的 android.provider.Settings 名字像，
        // 系统那个必须写全名，否则会被同名类遮蔽。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && !android.provider.Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.need_overlay_permission, Toast.LENGTH_LONG).show();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (rootView == null) {
            addOverlay();
        } else {
            if (closing) {
                cancelClosing();
            }
            reload();
        }
        return START_NOT_STICKY;
    }

    // ------------------------------------------------------------------ 悬浮窗

    private void addOverlay() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0x66000000); // 半透明遮罩，点空白处关闭
        root.setAlpha(0f);                    // 等图片解码好再淡入
        root.setClickable(true);
        root.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                closeWithAnimation();
            }
        });

        // 返回键也能收起悬浮图
        root.setFocusableInTouchMode(true);
        root.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int keyCode, android.view.KeyEvent event) {
                if (keyCode == android.view.KeyEvent.KEYCODE_BACK
                        && event.getAction() == android.view.KeyEvent.ACTION_UP) {
                    closeWithAnimation();
                    return true;
                }
                return false;
            }
        });

        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;

        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        try {
            windowManager.addView(root, p);
        } catch (Exception e) {
            Toast.makeText(this, R.string.need_overlay_permission, Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        rootView = root;
        showing = true;
        reload();
    }

    /** 按最新设置重新解码、重新渲染内容。 */
    private void reload() {
        final File f = Prefs.imageFile(this);
        final ImageSettings s = ImageSettings.load(this);
        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final int screenH = getResources().getDisplayMetrics().heightPixels;
        final int maxW = Math.max(1, screenW * s.scalePercent / 100);
        final int maxH = Math.max(1, screenH * s.scalePercent / 100);

        io.execute(new Runnable() {
            @Override
            public void run() {
                final Drawable d;
                try {
                    d = ImageProcessor.loadDrawable(OverlayService.this, f, maxW, maxH);
                } catch (Throwable t) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            applyContent(s, null, maxW, maxH);
                        }
                    });
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        applyContent(s, d, maxW, maxH);
                    }
                });
            }
        });
    }

    private void applyContent(ImageSettings s, Drawable d, int maxW, int maxH) {
        if (rootView == null) {
            return;
        }
        if (d == null) {
            // 图片读不出来，别留一个看不见却挡触摸的窗口
            Toast.makeText(this, R.string.save_failed, Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        FrameLayout wrapper = new FrameLayout(this);
        wrapper.setClipChildren(false);
        wrapper.setClipToPadding(false);
        wrapper.addView(OverlayContent.create(this, s, d, maxW, maxH, 1f));
        wrapper.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));

        boolean first = (contentWrapper == null);
        if (!first) {
            rootView.removeView(contentWrapper);
            wrapper.setAlpha(0f); // 换内容时交叉淡入
        }
        rootView.addView(wrapper);
        contentWrapper = wrapper;
        drawable = d;

        if (first) {
            if (!appeared) {
                appeared = true;
                playAppearAnimation();
            }
        } else {
            wrapper.animate().alpha(1f).setDuration(180).start();
        }
    }

    /** 出现动画：遮罩淡入 + 内容由小放大。 */
    private void playAppearAnimation() {
        if (rootView == null) {
            return;
        }
        rootView.setAlpha(0f);
        if (contentWrapper != null) {
            contentWrapper.setScaleX(0.85f);
            contentWrapper.setScaleY(0.85f);
            contentWrapper.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220)
                    .setInterpolator(new DecelerateInterpolator())
                    .start();
        }
        rootView.animate()
                .alpha(1f)
                .setDuration(200)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    /** 消失动画：遮罩淡出 + 内容缩小，动画结束后再移除窗口。 */
    private void closeWithAnimation() {
        if (closing) {
            return;
        }
        closing = true;
        if (rootView == null) {
            stopSelf();
            return;
        }
        if (contentWrapper != null) {
            contentWrapper.animate()
                    .scaleX(0.9f)
                    .scaleY(0.9f)
                    .setDuration(150)
                    .setInterpolator(new AccelerateInterpolator())
                    .start();
        }
        rootView.animate()
                .alpha(0f)
                .setDuration(150)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        stopSelf();
                    }
                })
                .start();
    }

    private void cancelClosing() {
        closing = false;
        if (rootView != null) {
            rootView.animate().cancel();
            rootView.setAlpha(1f);
        }
        if (contentWrapper != null) {
            contentWrapper.animate().cancel();
            contentWrapper.setScaleX(1f);
            contentWrapper.setScaleY(1f);
            contentWrapper.setAlpha(1f);
        }
    }

    /** 长按图片 / 通知栏「更换图片」：把主界面拉起来走选图流程。 */
    private void repick() {
        try {
            Intent i = new Intent(this, MainActivity.class)
                    .setAction(MainActivity.ACTION_PICK_IMAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(i);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // 旋转屏幕后按新的屏幕尺寸重新解码
        if (rootView != null) {
            reload();
        }
    }

    @Override
    public void onDestroy() {
        if (rootView != null && windowManager != null) {
            try {
                windowManager.removeViewImmediate(rootView);
            } catch (Exception ignored) {
            }
        }
        rootView = null;
        contentWrapper = null;
        appeared = false;
        closing = false;
        showing = false;
        drawable = null;
        io.shutdownNow();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ------------------------------------------------------------------ 通知

    private void createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) {
            return;
        }
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildNotification() {
        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            piFlags |= PendingIntent.FLAG_IMMUTABLE;
        }

        // 点通知本体直接进设置页（点图标现在也是进设置页，两边保持一致）
        Intent open = new Intent(this, ImageSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open, piFlags);

        Intent repick = new Intent(this, MainActivity.class)
                .setAction(MainActivity.ACTION_PICK_IMAGE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent repickPi = PendingIntent.getActivity(this, 2, repick, piFlags);

        Intent ImageSettings = new Intent(this, ImageSettingsActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent settingsPi = PendingIntent.getActivity(this, 3, ImageSettings, piFlags);

        Intent close = new Intent(this, OverlayService.class).setAction(ACTION_CLOSE);
        PendingIntent closePi = PendingIntent.getService(this, 4, close, piFlags);

        Notification.Builder b = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);

        b.setSmallIcon(R.drawable.ic_notify)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setContentIntent(openPi)
                .setOngoing(true)
                .setShowWhen(false)
                .setVisibility(Notification.VISIBILITY_SECRET)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notify),
                        getString(R.string.action_change), repickPi).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notify),
                        getString(R.string.action_settings), settingsPi).build())
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, R.drawable.ic_notify),
                        getString(R.string.action_close), closePi).build());
        return b.build();
    }
}
