package com.linxi.sidekeypopup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.linxi.sidekeypopup.input.SideKeyEngine;
import com.linxi.sidekeypopup.input.SideKeyService;
import com.linxi.sidekeypopup.overlay.OverlayService;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.List;

/**
 * 在 JVM 上把界面和「单击 / 长按」的判定真跑一遍。
 * 这台机器没有可用的模拟器 / 真机，所以这是唯一能自动验证的手段。
 *
 * 注意：这里不能 `import org.robolectric.annotation.Config`，
 * 它会把本项目的 com.linxi.sidekeypopup.Config 遮住，所以下面写全限定名。
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class MainActivityTest {

    private Context context;
    private SideKeyEngine engine;

    /** 假的单击动作，只记次数，不真的去弹窗。 */
    private final List<String> actions = new ArrayList<>();

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        engine = SideKeyEngine.get();
        engine.attach(context);
        engine.reset();
        engine.setAction((ctx, cfg) -> actions.add("popup"));
        actions.clear();

        Config config = new Config();
        config.enabled = true;
        config.sourceMode = Config.SOURCE_AUTO;
        config.save(context);
    }

    @After
    public void tearDown() {
        engine.reset();
        engine.setAction((ctx, cfg) -> {
        });
    }

    // ------------------------------------------------------------ 界面

    @Test
    public void screenInflatesWithDefaults() {
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();

            MaterialSwitch toggle = activity.findViewById(R.id.switch_enabled);
            assertNotNull(toggle);
            assertTrue("总开关默认应该是开的", toggle.isChecked());

            // Robolectric 里没有 Shizuku / Root / 无障碍，所以一条路都不通
            assertEquals(activity.getString(R.string.source_none),
                    text(activity, R.id.value_work_mode));
            assertEquals(activity.getString(R.string.value_never),
                    text(activity, R.id.status_last));
        }
    }

    /**
     * 选「自动」时不能只写「自动」——用户看不出自己实际在用哪条路。
     * 界面必须把正在跑的那条写出来。
     */
    @Test
    public void workModeShowsAutoPlusTheActiveSource() {
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();

            engine.setActiveSource(SideKeyEngine.SOURCE_SHIZUKU);
            shadowOf(Looper.getMainLooper()).idle();

            assertEquals("自动 · Shizuku", text(activity, R.id.value_work_mode));
        }
    }

    /** 手动选定了某条路、而且它真的在用，就只显示那一条。 */
    @Test
    public void workModeShowsTheForcedModeWhenItIsTheActiveOne() {
        Config config = Config.load(context);
        config.sourceMode = Config.SOURCE_SHIZUKU;
        config.save(context);

        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();

            engine.setActiveSource(SideKeyEngine.SOURCE_SHIZUKU);
            shadowOf(Looper.getMainLooper()).idle();

            assertEquals("Shizuku", text(activity, R.id.value_work_mode));
        }
    }

    /** 选定了某条路但没权限、实际走的是另一条时必须标出来，否则用户以为选择生效了。 */
    @Test
    public void workModeMarksAForcedModeThatFellBack() {
        Config config = Config.load(context);
        config.sourceMode = Config.SOURCE_SHIZUKU;
        config.save(context);

        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();

            engine.setActiveSource(SideKeyEngine.SOURCE_ACCESSIBILITY);
            shadowOf(Looper.getMainLooper()).idle();

            assertEquals("Shizuku · 未生效", text(activity, R.id.value_work_mode));
        }
    }

    /**
     * 无障碍这条路在一加 / OPPO 上根本收不到侧键，表现就是「按了没反应」。
     * 一直没抓到过按键时，界面得把结论直接写出来，不然根本查不出问题。
     */
    @Test
    public void workModeWarnsWhenAccessibilitySeesNothing() {
        Config config = Config.load(context);
        config.sourceMode = Config.SOURCE_ACCESSIBILITY;
        config.save(context);

        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();

            engine.setActiveSource(SideKeyEngine.SOURCE_ACCESSIBILITY);
            shadowOf(Looper.getMainLooper()).idle();

            assertEquals("无障碍服务 · 可能看不到侧键", text(activity, R.id.value_work_mode));
        }
    }

    /** 抓到过按键之后那句警告就该消失。 */
    @Test
    public void workModeStopsWarningOnceAKeyWasCaptured() {
        Config config = Config.load(context);
        config.sourceMode = Config.SOURCE_ACCESSIBILITY;
        config.save(context);

        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();

            engine.setActiveSource(SideKeyEngine.SOURCE_ACCESSIBILITY);
            engine.onKey(KeyEvent.ACTION_DOWN, Config.KEY_CODE, Config.KEY_CODE, 1000L);
            engine.onKey(KeyEvent.ACTION_UP, Config.KEY_CODE, Config.KEY_CODE, 1010L);
            shadowOf(Looper.getMainLooper()).idle();

            assertEquals("无障碍服务", text(activity, R.id.value_work_mode));
        }
    }

    /** 点「工作方式」这一行要弹出选择器，四个选项一个不少。 */
    @Test
    public void workModeRowOpensThePicker() {
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            activity.findViewById(R.id.row_work_mode).performClick();

            Dialog dialog = ShadowDialog.getLatestDialog();
            assertNotNull("点「工作方式」应该弹出选择器", dialog);

            // 不去猜列表控件的 id：Material 的对话框布局换过好几版，
            // 直接找里面那个 ListView 更稳。
            ListView list = findList(dialog.getWindow().getDecorView());
            assertNotNull("选择器里没有列表", list);
            assertEquals("自动 / Shizuku / Root / 无障碍", 4, list.getAdapter().getCount());

            list.performItemClick(null, 1, 1);

            Config saved = Config.load(context);
            assertEquals("第二项是 Shizuku", Config.SOURCE_SHIZUKU, saved.sourceMode);
        }
    }

    private static ListView findList(View view) {
        if (view instanceof ListView) {
            return (ListView) view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ListView found = findList(group.getChildAt(i));
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    public void permissionScreenListsEveryRow() {
        try (ActivityController<PermissionActivity> controller =
                     Robolectric.buildActivity(PermissionActivity.class).setup()) {
            LinearLayout rows = controller.get().findViewById(R.id.rows);
            assertEquals("悬浮窗 / 无障碍 / Root / Shizuku / 通知 / 电池 / 自启动 / 隐藏后台",
                    8, rows.getChildCount());

            for (int i = 0; i < rows.getChildCount(); i++) {
                TextView title = rows.getChildAt(i).findViewById(R.id.title);
                assertFalse("每一行都要有标题", title.getText().toString().isEmpty());
                assertNotNull(rows.getChildAt(i).findViewById(R.id.action));
            }

            // 悬浮窗排第一：没有它图片根本弹不出来，必须一眼就看到。
            TextView first = rows.getChildAt(0).findViewById(R.id.title);
            assertEquals(controller.get().getString(R.string.perm_overlay),
                    first.getText().toString());
        }
    }

    // ------------------------------------------------------------ 后台相关跳转

    @Test
    public void autoStartReportsFailureWhenNoVendorPageExists() {
        // 干净的 Robolectric 环境里没有 ColorOS 那几个包。这时必须老实返回 false，
        // 让调用方去提示用户并退回应用信息页——之前就是在这里默默跳错页面的。
        assertFalse(Permissions.openAutoStartSettings(context));
    }

    @Test
    public void autoStartPrefersTheColorOsPageOverAppDetails() {
        ComponentName page = new ComponentName("com.coloros.safecenter",
                "com.coloros.safecenter.permission.startup.StartupAppListActivity");
        shadowOf(context.getPackageManager()).addActivityIfNotPresent(page);

        assertTrue(Permissions.openAutoStartSettings(context));

        Intent started = shadowOf((Application) context).getNextStartedActivity();
        assertNotNull("应该真的跳出去了", started);
        assertEquals("要跳到厂商的自启动管理页，而不是系统原生应用信息页",
                page, started.getComponent());
    }

    @Test
    public void sideKeyServiceSurvivesTaskRemoval() throws Exception {
        ServiceInfo info = context.getPackageManager()
                .getServiceInfo(new ComponentName(context, SideKeyService.class), 0);

        assertEquals("从最近任务划掉本应用时，不能把侧键监听一起停掉",
                0, info.flags & ServiceInfo.FLAG_STOP_WITH_TASK);
    }

    // ------------------------------------------------------------ 单击 / 长按

    @Test
    public void shortPressRunsTheAction() {
        engine.onKey(KeyEvent.ACTION_DOWN, Config.KEY_CODE, Config.KEY_CODE, 1000L);
        engine.onKey(KeyEvent.ACTION_UP, Config.KEY_CODE, Config.KEY_CODE, 1000L + Config.TAP_MAX_MS - 1);

        assertEquals(1, actions.size());
    }

    @Test
    public void longPressIsLeftToTheSystem() {
        engine.onKey(KeyEvent.ACTION_DOWN, Config.KEY_CODE, Config.KEY_CODE, 1000L);
        engine.onKey(KeyEvent.ACTION_UP, Config.KEY_CODE, Config.KEY_CODE, 1000L + Config.TAP_MAX_MS + 500);

        assertTrue("长按不该被我们抢走", actions.isEmpty());
    }

    @Test
    public void tapBoundaryIsExclusive() {
        assertTrue(SideKeyEngine.isTap(0, Config.TAP_MAX_MS - 1));
        assertFalse(SideKeyEngine.isTap(0, Config.TAP_MAX_MS));
        assertFalse(SideKeyEngine.isTap(0, Config.TAP_MAX_MS + 1));
    }

    @Test
    public void otherKeysAreIgnored() {
        engine.onKey(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_UP, 115, 1000L);
        engine.onKey(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_VOLUME_UP, 115, 1010L);

        assertTrue(actions.isEmpty());
        assertEquals(0L, engine.getLastCapturedAt());
    }

    @Test
    public void masterSwitchOffMeansHandsOff() {
        Config config = Config.load(context);
        config.enabled = false;
        config.save(context);

        engine.onKey(KeyEvent.ACTION_DOWN, Config.KEY_CODE, Config.KEY_CODE, 1000L);
        engine.onKey(KeyEvent.ACTION_UP, Config.KEY_CODE, Config.KEY_CODE, 1010L);

        assertTrue(actions.isEmpty());
        assertEquals(0L, engine.getLastCapturedAt());
    }

    @Test
    public void onlyOneActionPerPhysicalClick() {
        engine.onKey(KeyEvent.ACTION_DOWN, Config.KEY_CODE, Config.KEY_CODE, 1000L);
        // 长按时的自动重复事件
        engine.onKey(KeyEvent.ACTION_DOWN, Config.KEY_CODE, Config.KEY_CODE, 1100L);
        engine.onKey(KeyEvent.ACTION_DOWN, Config.KEY_CODE, Config.KEY_CODE, 1200L);
        engine.onKey(KeyEvent.ACTION_UP, Config.KEY_CODE, Config.KEY_CODE, 1210L);

        assertEquals(1, actions.size());
    }

    // ------------------------------------------------------------ 弹出图片

    /**
     * 单击动作是固定的「弹出图片」，按侧键要真的把悬浮图服务拉起来。
     *
     * 合并成一个应用之后这是一次普通的同进程调用——原来那套跨应用接口、
     * Android 12+ 后台起前台服务被拦、再退透明 Activity 的东西全都不需要了。
     */
    @Test
    public void shortPressShowsTheOverlay() {
        engine.setAction(null); // 回到真实实现，验证分发确实走 OverlayService
        engine.onKey(KeyEvent.ACTION_DOWN, Config.KEY_CODE, Config.KEY_CODE, 1000L);
        engine.onKey(KeyEvent.ACTION_UP, Config.KEY_CODE, Config.KEY_CODE, 1010L);

        Intent started = shadowOf((Application) context).getNextStartedService();
        assertNotNull("按侧键没有拉起悬浮图服务", started);
        assertEquals(OverlayService.ACTION_SHOW, started.getAction());
        assertNotNull("必须用显式组件名", started.getComponent());
        assertEquals(OverlayService.class.getName(), started.getComponent().getClassName());
        assertEquals(context.getPackageName(), started.getComponent().getPackageName());
    }

    /**
     * 界面里不该再有「单击做什么」和单独的「读取方式」——动作写死是弹图片，
     * 读取方式并进了「工作方式」那一行。
     *
     * 这里用 getIdentifier 而不是 R.id.*：那些 id 已经不在 R 里了，写 R.id 根本编译不过。
     */
    @Test
    public void theRemovedSectionsAreGone() {
        try (ActivityController<MainActivity> controller =
                     Robolectric.buildActivity(MainActivity.class).setup()) {
            MainActivity activity = controller.get();
            for (String gone : new String[]{"row_single", "row_long", "row_read_mode",
                    "status_source", "status_hint", "value_single", "value_read_mode"}) {
                int id = activity.getResources().getIdentifier(gone, "id", activity.getPackageName());
                assertEquals("布局里不该还有 " + gone, 0, id);
            }
        }
    }

    // ------------------------------------------------------------ 配置

    @Test
    public void configRoundTripsThroughPreferences() {
        Config written = new Config();
        written.enabled = false;
        written.sourceMode = Config.SOURCE_ROOT;
        written.hideFromRecents = true;
        written.save(context);

        Config read = Config.load(context);
        assertFalse(read.enabled);
        assertEquals(Config.SOURCE_ROOT, read.sourceMode);
        assertTrue(read.hideFromRecents);
    }

    @Test
    public void defaultConfigIsEnabledAndAutomatic() {
        Config config = new Config();
        assertTrue(config.enabled);
        assertEquals(Config.SOURCE_AUTO, config.sourceMode);
        assertFalse("隐藏后台默认必须是关的，开了就没法从最近任务切回来了", config.hideFromRecents);
    }

    // ------------------------------------------------------------ 隐藏后台

    @Test
    public void hideFromRecentsIsNotBakedIntoTheManifest() throws Exception {
        ActivityInfo info = context.getPackageManager()
                .getActivityInfo(new ComponentName(context, MainActivity.class), 0);

        assertEquals("「隐藏后台」必须由用户开关决定，不能写死在清单里——"
                        + "写死了就是常开，默认不隐藏这条就废了",
                0, info.flags & ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS);
    }

    @Test
    public void hideBackgroundRowTogglesTheSetting() {
        try (ActivityController<PermissionActivity> controller =
                     Robolectric.buildActivity(PermissionActivity.class).setup()) {
            PermissionActivity activity = controller.get();
            LinearLayout rows = activity.findViewById(R.id.rows);
            View row = rows.getChildAt(rows.getChildCount() - 1);

            assertEquals("最后一行应该是隐藏后台",
                    activity.getString(R.string.perm_hide_background),
                    ((TextView) row.findViewById(R.id.title)).getText().toString());

            TextView status = row.findViewById(R.id.status);
            MaterialButton action = row.findViewById(R.id.action);
            assertEquals("默认不隐藏",
                    activity.getString(R.string.hide_background_off), status.getText().toString());

            action.performClick();
            assertTrue("点一下要真的存下来", Config.load(activity).hideFromRecents);
            assertEquals(activity.getString(R.string.hide_background_on), status.getText().toString());

            action.performClick();
            assertFalse("再点一下要能取消", Config.load(activity).hideFromRecents);
            assertEquals(activity.getString(R.string.hide_background_off), status.getText().toString());

            assertNull("「隐藏后台」是应用自己的开关，不该跳去任何设置页",
                    shadowOf(activity).getNextStartedActivity());
        }
    }

    @Test
    public void applyingHideFromRecentsNeverThrows() {
        // 这里只能验到「不炸」：AppTask 的构造器是包内可见，本工程也没引 Mockito，
        // 所以拿不到 AppTask 实例去断言 setExcludeFromRecents 真的被调了。
        // 真正生效与否只能上真机看。
        Recents.apply(context, true);
        Recents.apply(context, false);
        Recents.apply(context);
    }

    @Test
    public void applicationWiresUpTheEngineOnStartup() {
        Application app = ApplicationProvider.getApplicationContext();
        assertTrue("进程一起来就要把引擎准备好，否则无障碍服务被系统拉起时侧键不工作",
                app instanceof SideKeyPopupApp);
    }

    private static String text(MainActivity activity, int id) {
        return ((TextView) activity.findViewById(id)).getText().toString();
    }
}
