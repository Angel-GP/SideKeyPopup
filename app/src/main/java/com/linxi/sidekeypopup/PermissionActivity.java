package com.linxi.sidekeypopup;

import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.linxi.sidekeypopup.input.RootShell;
import com.linxi.sidekeypopup.input.SourceManager;
import com.linxi.sidekeypopup.shizuku.ShizukuBridge;

import rikka.shizuku.Shizuku;

/**
 * 二级窗口：所有权限申请都收在这里。
 *
 * 第一行「显示在其他应用上层」是必须的——没有它图片根本弹不出来。
 * 读侧键则只要下面任意一条路走通就行，优先级 Shizuku &gt; Root &gt; 无障碍，
 * 所以那几项没有「必须开哪个」的说法，用户挑最方便的即可。
 */
public class PermissionActivity extends AppCompatActivity {

    private LinearLayout rows;

    private TextView overlayStatus;
    private TextView accessibilityStatus;
    private TextView rootStatus;
    private TextView shizukuStatus;
    private TextView shizukuDesc;
    private TextView notificationStatus;
    private TextView batteryStatus;
    private TextView hideBackgroundStatus;

    private MaterialButton overlayAction;
    private MaterialButton rootAction;
    private MaterialButton shizukuAction;
    private MaterialButton notificationAction;
    private MaterialButton hideBackgroundAction;

    private final Shizuku.OnRequestPermissionResultListener permissionListener =
            (requestCode, grantResult) -> runOnUiThread(() -> {
                // 授权结果出来了，立刻重选一次来源，不用等用户回主界面。
                SourceManager.get().sync(this);
                render();
            });

    private final Shizuku.OnBinderReceivedListener binderListener =
            () -> runOnUiThread(this::render);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_permission);

        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        rows = findViewById(R.id.rows);
        buildRows();

        try {
            Shizuku.addRequestPermissionResultListener(permissionListener);
            Shizuku.addBinderReceivedListenerSticky(binderListener);
        } catch (Throwable ignored) {
            // 没装 Shizuku 就算了
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            Shizuku.removeRequestPermissionResultListener(permissionListener);
            Shizuku.removeBinderReceivedListener(binderListener);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        render();
    }

    // ------------------------------------------------------------ 列表

    private void buildRows() {
        // 悬浮窗排第一：它是弹图片的前提，其他几项都是"读侧键"的备选路线。
        View overlay = addRow(R.string.perm_overlay, R.string.perm_overlay_desc);
        overlayStatus = overlay.findViewById(R.id.status);
        overlayAction = overlay.findViewById(R.id.action);
        overlayAction.setOnClickListener(view -> Permissions.requestOverlay(this));

        View accessibility = addRow(R.string.perm_accessibility, R.string.perm_accessibility_desc);
        accessibilityStatus = accessibility.findViewById(R.id.status);
        ((MaterialButton) accessibility.findViewById(R.id.action)).setText(R.string.perm_go);
        accessibility.findViewById(R.id.action).setOnClickListener(view -> {
            Permissions.openAccessibilitySettings(this);
        });

        View root = addRow(R.string.perm_root, R.string.perm_root_desc);
        rootStatus = root.findViewById(R.id.status);
        rootAction = root.findViewById(R.id.action);
        rootAction.setOnClickListener(view -> requestRoot());

        View shizuku = addRow(R.string.perm_shizuku, R.string.perm_shizuku_desc);
        shizukuStatus = shizuku.findViewById(R.id.status);
        shizukuDesc = shizuku.findViewById(R.id.desc);
        shizukuAction = shizuku.findViewById(R.id.action);
        shizukuAction.setOnClickListener(view -> requestShizuku());

        View notification = addRow(R.string.perm_notification, R.string.perm_notification_desc);
        notificationStatus = notification.findViewById(R.id.status);
        notificationAction = notification.findViewById(R.id.action);
        notificationAction.setOnClickListener(view -> {
            if (Permissions.hasNotificationPermission(this)) {
                Permissions.openNotificationSettings(this);
            } else {
                Permissions.requestNotificationPermission(this);
            }
        });

        View battery = addRow(R.string.perm_battery, R.string.perm_battery_desc);
        batteryStatus = battery.findViewById(R.id.status);
        MaterialButton batteryAction = battery.findViewById(R.id.action);
        batteryAction.setText(R.string.perm_go);
        batteryAction.setOnClickListener(view -> Permissions.requestIgnoreBatteryOptimizations(this));

        View background = addRow(R.string.perm_background, R.string.perm_background_desc);
        background.findViewById(R.id.status).setVisibility(View.GONE);
        MaterialButton backgroundAction = background.findViewById(R.id.action);
        backgroundAction.setText(R.string.perm_open);
        backgroundAction.setOnClickListener(view -> openAutoStart());

        // 「隐藏后台」不是跳转，是本应用自己的开关：开了就不把自己放进最近任务，
        // 没卡片可划，也就不会被不小心划掉。
        View hideBackground = addRow(R.string.perm_hide_background, R.string.perm_hide_background_desc);
        hideBackgroundStatus = hideBackground.findViewById(R.id.status);
        hideBackgroundAction = hideBackground.findViewById(R.id.action);
        hideBackgroundAction.setOnClickListener(view -> toggleHideFromRecents());
    }

    /**
     * 跳厂商的自启动管理页。
     *
     * 候选全都没命中时，跳应用信息页是没用的——那一页里本来就没有自启动开关，
     * 之前就是这么错的。所以这里老实把手动路径写出来。
     */
    private void openAutoStart() {
        if (Permissions.openAutoStartSettings(this)) {
            return;
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.autostart_not_found_title)
                .setMessage(R.string.autostart_not_found_message)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    /**
     * 切换「隐藏后台」。
     *
     * 权限页和主界面在同一个任务里，所以这里改的就是那个任务，不用等回主界面。
     */
    private void toggleHideFromRecents() {
        Config config = Config.load(this);
        config.hideFromRecents = !config.hideFromRecents;
        config.save(this);
        Recents.apply(this, config.hideFromRecents);
        render();
    }

    private View addRow(int titleRes, int descRes) {
        View row = getLayoutInflater().inflate(R.layout.item_permission, rows, false);
        ((TextView) row.findViewById(R.id.title)).setText(titleRes);
        ((TextView) row.findViewById(R.id.desc)).setText(descRes);
        rows.addView(row);
        return row;
    }

    // ------------------------------------------------------------ 状态

    private void render() {
        boolean overlay = Permissions.isOverlayAllowed(this);
        overlayStatus.setText(overlay ? R.string.perm_granted : R.string.perm_denied);
        overlayStatus.setTextColor(getColor(overlay ? R.color.ok : R.color.warn));
        overlayAction.setText(overlay ? R.string.perm_open : R.string.perm_go);

        boolean accessibility = Permissions.isAccessibilityEnabled(this);
        accessibilityStatus.setText(accessibility ? R.string.perm_granted : R.string.perm_denied);
        accessibilityStatus.setTextColor(getColor(accessibility ? R.color.ok : R.color.muted));

        boolean root = RootShell.isGranted(this);
        rootStatus.setText(root ? R.string.perm_granted : R.string.perm_denied);
        rootStatus.setTextColor(getColor(root ? R.color.ok : R.color.muted));
        rootAction.setText(root ? R.string.perm_retry : R.string.perm_request);

        renderShizuku();

        boolean notification = Permissions.hasNotificationPermission(this);
        notificationStatus.setText(notification ? R.string.perm_granted : R.string.perm_denied);
        notificationStatus.setTextColor(getColor(notification ? R.color.ok : R.color.muted));
        notificationAction.setText(notification ? R.string.perm_open : R.string.perm_request);

        boolean battery = Permissions.isIgnoringBatteryOptimizations(this);
        batteryStatus.setText(battery ? R.string.perm_granted : R.string.perm_denied);
        batteryStatus.setTextColor(getColor(battery ? R.color.ok : R.color.muted));

        boolean hidden = Config.load(this).hideFromRecents;
        hideBackgroundStatus.setText(hidden ? R.string.hide_background_on : R.string.hide_background_off);
        hideBackgroundStatus.setTextColor(getColor(hidden ? R.color.ok : R.color.muted));
        hideBackgroundAction.setText(hidden
                ? R.string.hide_background_disable : R.string.hide_background_enable);
    }

    // ------------------------------------------------------------ 申请

    /**
     * Shizuku 的状态要分得很细，因为「申请不了权限」有好几种完全不同的原因，
     * 混在一起用户根本不知道该怎么办。
     */
    private void renderShizuku() {
        switch (ShizukuBridge.state(this)) {
            case GRANTED:
                shizukuStatus.setText(R.string.perm_granted);
                shizukuStatus.setTextColor(getColor(R.color.ok));
                shizukuDesc.setText(R.string.perm_shizuku_granted_desc);
                shizukuAction.setText(R.string.perm_shizuku_request);
                break;
            case DENIED:
                shizukuStatus.setText(R.string.perm_denied);
                shizukuStatus.setTextColor(getColor(R.color.warn));
                if (ShizukuBridge.wasDeniedBefore()) {
                    shizukuDesc.setText(R.string.perm_shizuku_denied_before_desc);
                    shizukuAction.setText(R.string.perm_shizuku_open);
                } else {
                    shizukuDesc.setText(R.string.perm_shizuku_denied_desc);
                    shizukuAction.setText(R.string.perm_shizuku_request);
                }
                break;
            case OUTDATED:
                shizukuStatus.setText(R.string.perm_shizuku_outdated);
                shizukuStatus.setTextColor(getColor(R.color.warn));
                shizukuDesc.setText(R.string.perm_shizuku_outdated_desc);
                shizukuAction.setText(R.string.perm_shizuku_open);
                break;
            case NOT_RUNNING:
                shizukuStatus.setText(R.string.perm_shizuku_not_running);
                shizukuStatus.setTextColor(getColor(R.color.muted));
                shizukuDesc.setText(R.string.perm_shizuku_not_running_desc);
                shizukuAction.setText(R.string.perm_shizuku_open);
                break;
            case NOT_INSTALLED:
            default:
                shizukuStatus.setText(R.string.perm_shizuku_not_installed);
                shizukuStatus.setTextColor(getColor(R.color.muted));
                shizukuDesc.setText(R.string.perm_shizuku_not_installed_desc);
                shizukuAction.setText(R.string.perm_shizuku_download);
                break;
        }
    }

    private void requestShizuku() {
        switch (ShizukuBridge.state(this)) {
            case NOT_INSTALLED:
                ShizukuBridge.openDownloadPage(this);
                return;
            case NOT_RUNNING:
            case OUTDATED:
                ShizukuBridge.openShizukuApp(this);
                return;
            case GRANTED:
            case DENIED:
            default:
                break;
        }

        if (ShizukuBridge.wasDeniedBefore()) {
            ShizukuBridge.openShizukuApp(this);
            return;
        }

        // 只有请求真的发出去了才说「已发起」，否则用户会一直等一个不会出现的弹窗。
        boolean sent = ShizukuBridge.requestPermission();
        Toast.makeText(this,
                sent ? R.string.toast_shizuku_requested : R.string.toast_shizuku_request_failed,
                Toast.LENGTH_LONG).show();
        render();
    }

    private void requestRoot() {
        // su 会弹授权框，可能等很久，必须离开主线程。
        rootStatus.setText(R.string.perm_requesting);
        new Thread(() -> {
            boolean granted = RootShell.request(getApplicationContext());
            runOnUiThread(() -> {
                Toast.makeText(this,
                        granted ? R.string.toast_root_granted : R.string.toast_root_denied,
                        Toast.LENGTH_SHORT).show();
                SourceManager.get().sync(this);
                render();
            });
        }, "root-request").start();
    }
}
