package com.linxi.sidekeypopup;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.linxi.sidekeypopup.input.SideKeyEngine;
import com.linxi.sidekeypopup.input.SourceManager;
import com.linxi.sidekeypopup.overlay.ImageProcessor;
import com.linxi.sidekeypopup.overlay.ImageSettingsActivity;
import com.linxi.sidekeypopup.overlay.OverlayService;
import com.linxi.sidekeypopup.overlay.Prefs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 唯一的主界面。合并前这是两个应用，现在都收在这里：
 *
 * <ul>
 *   <li><b>图片</b>：当前图片（缩略图）/ 更换图片 / 图片与外观（{@link ImageSettingsActivity}）；</li>
 *   <li><b>侧键</b>：总开关 / 工作方式（用哪条路读侧键）/ 最近捕获；</li>
 *   <li><b>权限</b>：全部收在 {@link PermissionActivity}，这里只留一个入口。</li>
 * </ul>
 *
 * 单击做什么是固定的——就是弹图片，所以界面上没有这个选项。
 * 按侧键弹图片是同进程直接调 {@link OverlayService#show}，
 * 不再有跨应用接口，也没有 Android 12+ 后台起前台服务被拦的问题。
 */
public class MainActivity extends AppCompatActivity {

    /** 桌面长按菜单 / 通知栏「更换图片」/ 长按悬浮图，都发这个 action。 */
    public static final String ACTION_PICK_IMAGE = "com.linxi.sidekeypopup.action.PICK_IMAGE";
    /** 桌面长按菜单里的「图片设置」。 */
    public static final String ACTION_IMAGE_SETTINGS = "com.linxi.sidekeypopup.action.IMAGE_SETTINGS";

    private static final int REQ_PICK = 1001;
    private static final int REQ_OVERLAY = 1002;

    private static final int[] SOURCE_MODES = {
            Config.SOURCE_AUTO,
            Config.SOURCE_SHIZUKU,
            Config.SOURCE_ROOT,
            Config.SOURCE_ACCESSIBILITY,
    };

    private MaterialSwitch switchEnabled;
    private TextView valueWorkMode;
    private TextView valueImage;
    private TextView statusLast;
    private ImageView thumbImage;

    private boolean picking;
    private boolean waitingOverlay;

    private final ExecutorService thumbs = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.getDefault());

    /** 引擎状态变了（换了来源、抓到按键）就刷新界面。 */
    private final Runnable observer = () -> runOnUiThread(this::render);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        switchEnabled = findViewById(R.id.switch_enabled);
        valueWorkMode = findViewById(R.id.value_work_mode);
        valueImage = findViewById(R.id.value_image);
        statusLast = findViewById(R.id.status_last);
        thumbImage = findViewById(R.id.thumb_image);

        switchEnabled.setOnCheckedChangeListener((button, checked) -> {
            Config config = Config.load(this);
            config.enabled = checked;
            config.save(this);
            SourceManager.get().sync(this);
            render();
        });

        findViewById(R.id.row_work_mode).setOnClickListener(view -> showWorkModePicker());
        findViewById(R.id.row_change_image).setOnClickListener(view -> pickImage());
        findViewById(R.id.row_image_settings).setOnClickListener(view -> openImageSettings());
        findViewById(R.id.row_permission).setOnClickListener(view ->
                startActivity(new Intent(this, PermissionActivity.class)));

        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    /**
     * 从桌面长按菜单或通知栏进来时直接做对应的事，不用用户再点一次。
     *
     * action 用完就清掉：否则转屏重建时会把选图/开设置又跑一遍。
     */
    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String action = intent.getAction();
        if (ACTION_PICK_IMAGE.equals(action)) {
            intent.setAction(null);
            pickImage();
        } else if (ACTION_IMAGE_SETTINGS.equals(action)) {
            intent.setAction(null);
            openImageSettings();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        SideKeyEngine.get().addObserver(observer);
    }

    @Override
    protected void onStop() {
        super.onStop();
        SideKeyEngine.get().removeObserver(observer);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 「隐藏后台」在权限页里能改；任务重建后也要按当前设置重新标一遍
        Recents.apply(this);
        // 从权限页回来时权限可能变了，重新选一次来源。
        SourceManager.get().sync(this);
        // 部分机型从「悬浮窗权限」页面返回时不会回调 onActivityResult，这里兜底
        if (waitingOverlay && overlayAllowed()) {
            waitingOverlay = false;
            startPicker();
        }
        render();
        loadThumbnail();
    }

    @Override
    protected void onDestroy() {
        thumbs.shutdownNow();
        super.onDestroy();
    }

    // ------------------------------------------------------------ 图片

    private boolean overlayAllowed() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this);
    }

    /**
     * 换图片。没有悬浮窗权限就先要权限——不然选完也弹不出来，
     * 而且用户会以为是自己选错了。
     */
    private void pickImage() {
        if (picking) {
            return;
        }
        if (!overlayAllowed()) {
            waitingOverlay = true;
            toast(R.string.need_overlay_permission);
            try {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())), REQ_OVERLAY);
            } catch (ActivityNotFoundException e) {
                waitingOverlay = false;
                toast(R.string.overlay_permission_denied);
            }
            return;
        }
        startPicker();
    }

    private void startPicker() {
        if (picking) {
            return;
        }
        picking = true;

        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, REQ_PICK);
        } catch (ActivityNotFoundException e) {
            Intent g = new Intent(Intent.ACTION_GET_CONTENT);
            g.addCategory(Intent.CATEGORY_OPENABLE);
            g.setType("image/*");
            g.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivityForResult(Intent.createChooser(g, getString(R.string.pick_image)), REQ_PICK);
            } catch (ActivityNotFoundException e2) {
                picking = false;
                toast(R.string.no_picker);
            }
        }
    }

    private void openImageSettings() {
        try {
            startActivity(new Intent(this, ImageSettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQ_OVERLAY) {
            waitingOverlay = false;
            if (overlayAllowed()) {
                startPicker();
            } else {
                toast(R.string.overlay_permission_denied);
            }
            return;
        }

        if (requestCode == REQ_PICK) {
            picking = false;
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                saveImage(data.getData());
            }
        }
    }

    /** 存到私有目录再弹出来给用户看一眼，选完立刻有反馈。 */
    private void saveImage(final Uri uri) {
        toast(R.string.saving);
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean ok = copyToPrivate(uri);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (ok) {
                            render();
                            loadThumbnail();
                            OverlayService.show(MainActivity.this);
                        } else {
                            toast(R.string.save_failed);
                        }
                    }
                });
            }
        }, "save-image").start();
    }

    /** 把选中的图片复制到应用私有目录，避免后续读不到内容 URI。 */
    private boolean copyToPrivate(Uri uri) {
        File tmp = new File(getFilesDir(), "overlay_image.tmp");
        InputStream in = null;
        OutputStream out = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return false;
            }
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.flush();
            ((FileOutputStream) out).getFD().sync();
            out.close();
            out = null;
            in.close();
            in = null;

            File dst = Prefs.imageFile(this);
            // Linux 的 rename(2) 本身就会覆盖，所以先试一次原子替换：
            // 失败也不会把用户原来那张图弄丢。
            if (tmp.renameTo(dst)) {
                return dst.length() > 0L;
            }
            if (dst.exists() && !dst.delete()) {
                return false;
            }
            return tmp.renameTo(dst) && dst.length() > 0L;
        } catch (Exception e) {
            return false;
        } finally {
            closeQuietly(in);
            closeQuietly(out);
            if (tmp.exists() && !Prefs.imageFile(this).exists()) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
        }
    }

    /** 主界面上那个 48dp 的小缩略图。解码放到后台线程，别卡住滑动。 */
    private void loadThumbnail() {
        if (!Prefs.hasImage(this)) {
            thumbImage.setImageDrawable(null);
            return;
        }
        final File f = Prefs.imageFile(this);
        thumbs.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = ImageProcessor.decode(f, 128, 128);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (!isFinishing() && bmp != null) {
                            thumbImage.setImageBitmap(bmp);
                        }
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------ 读取方式

    /**
     * 让用户手动指定用哪条路。
     *
     * 这个选择不是可有可无的：无障碍和读 /dev/input 看到的按键范围不一样，
     * 厂商消化掉的键（一加侧键就是）只有后者能看到。自动模式虽然会挑更可靠的，
     * 但用户得有个地方能自己确认和切换。
     */
    private void showWorkModePicker() {
        Config config = Config.load(this);
        CharSequence[] labels = new CharSequence[SOURCE_MODES.length];
        int checked = 0;
        for (int i = 0; i < SOURCE_MODES.length; i++) {
            labels[i] = readModeLabel(SOURCE_MODES[i]) + "\n" + readModeDesc(SOURCE_MODES[i]);
            if (SOURCE_MODES[i] == config.sourceMode) {
                checked = i;
            }
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.work_mode)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    Config current = Config.load(this);
                    current.sourceMode = SOURCE_MODES[which];
                    current.save(this);
                    dialog.dismiss();
                    SourceManager.get().sync(this);
                    render();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String readModeLabel(int mode) {
        switch (mode) {
            case Config.SOURCE_ACCESSIBILITY:
                return getString(R.string.read_mode_accessibility);
            case Config.SOURCE_SHIZUKU:
                return getString(R.string.read_mode_shizuku);
            case Config.SOURCE_ROOT:
                return getString(R.string.read_mode_root);
            default:
                return getString(R.string.read_mode_auto);
        }
    }

    private String readModeDesc(int mode) {
        switch (mode) {
            case Config.SOURCE_ACCESSIBILITY:
                return getString(R.string.read_mode_accessibility_desc);
            case Config.SOURCE_SHIZUKU:
                return getString(R.string.read_mode_shizuku_desc);
            case Config.SOURCE_ROOT:
                return getString(R.string.read_mode_root_desc);
            default:
                return getString(R.string.read_mode_auto_desc);
        }
    }

    // ------------------------------------------------------------ 渲染

    private void render() {
        Config config = Config.load(this);

        if (switchEnabled.isChecked() != config.enabled) {
            switchEnabled.setChecked(config.enabled);
        }

        valueImage.setText(Prefs.hasImage(this)
                ? getString(R.string.entry_image_selected)
                : getString(R.string.entry_image_none));

        SideKeyEngine engine = SideKeyEngine.get();
        long captured = engine.getLastCapturedAt();

        valueWorkMode.setText(workModeText(config, engine.getActiveSource(), captured));
        statusLast.setText(captured > 0 ? timeFormat.format(new Date(captured))
                : getString(R.string.value_never));
    }

    /**
     * 「工作方式」那一行显示什么。
     *
     * 选了自动的话，用户看不见自己实际在用哪条路，所以这里直接把正在跑的那条写出来；
     * 选定了某一条但没权限、实际走的是另一条时也要标出来，不然用户会以为选择生效了。
     */
    private String workModeText(Config config, int active, long captured) {
        if (active == SideKeyEngine.SOURCE_NONE) {
            // 一条路都没通
            return config.sourceMode == Config.SOURCE_AUTO
                    ? getString(R.string.source_none)
                    : readModeLabel(config.sourceMode) + " · " + getString(R.string.work_mode_inactive);
        }

        if (config.sourceMode == Config.SOURCE_AUTO) {
            return getString(R.string.source_auto) + " · " + sourceLabel(active);
        }

        if (SourceManager.engineSourceFor(config.sourceMode) != active) {
            return readModeLabel(config.sourceMode) + " · " + getString(R.string.work_mode_inactive);
        }

        // 无障碍这条路在厂商消化掉侧键的机型上（一加 / OPPO）根本收不到按键，
        // 而表现就是「按了没反应」，最难查，所以直接把结论写在值里。
        if (active == SideKeyEngine.SOURCE_ACCESSIBILITY && captured == 0) {
            return getString(R.string.source_accessibility) + " · " + getString(R.string.work_mode_blind);
        }

        return readModeLabel(config.sourceMode);
    }

    private String sourceLabel(int source) {
        switch (source) {
            case SideKeyEngine.SOURCE_ACCESSIBILITY:
                return getString(R.string.source_accessibility);
            case SideKeyEngine.SOURCE_ROOT:
                return getString(R.string.source_root);
            case SideKeyEngine.SOURCE_SHIZUKU:
                return getString(R.string.source_shizuku);
            default:
                return getString(R.string.source_none);
        }
    }

    private void toast(int res) {
        Toast.makeText(this, res, Toast.LENGTH_LONG).show();
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
