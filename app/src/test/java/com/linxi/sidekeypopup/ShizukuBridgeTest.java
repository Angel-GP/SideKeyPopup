package com.linxi.sidekeypopup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.pm.PackageInfo;

import androidx.test.core.app.ApplicationProvider;

import com.linxi.sidekeypopup.shizuku.ShizukuBridge;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import rikka.shizuku.ShizukuProvider;

/**
 * Shizuku 这条路的状态判定。
 *
 * 测试环境里没有真的 Shizuku，所以这里验证的是「拿不到 binder」时的行为——
 * 也就是真机上「Shizuku 明明在运行却用不了」那类情况的代码侧表现。
 */
@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class ShizukuBridgeTest {

    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
    }

    /**
     * 回归测试：包名必须是 `moe.shizuku.privileged.api`。
     *
     * 之前写成了 `moe.shizuku.manager`——那是**权限名**
     * （moe.shizuku.manager.permission.API_V23）的前缀，不是包名。
     * 结果 Shizuku 明明在运行，界面却一直显示「未安装」。
     */
    @Test
    public void usesTheRealShizukuPackageName() {
        assertEquals("moe.shizuku.privileged.api", ShizukuBridge.shizukuPackageName());
        assertEquals(ShizukuProvider.MANAGER_APPLICATION_ID, ShizukuBridge.shizukuPackageName());
        assertFalse("权限名前缀不是包名",
                ShizukuBridge.shizukuPackageName().equals("moe.shizuku.manager"));
    }

    /**
     * 装了 Shizuku 但还没拿到 binder 时，状态必须是「未连接」而不是「未安装」。
     * 这两者对用户来说要做的事情完全不一样。
     */
    @Test
    public void installedButNotConnectedIsNotReportedAsMissing() {
        installFakeShizuku();

        assertTrue(ShizukuBridge.isShizukuInstalled(context));
        assertEquals(ShizukuBridge.State.NOT_RUNNING, ShizukuBridge.state(context));
    }

    @Test
    public void reportsNotInstalledWhenNothingIsThere() {
        assertFalse(ShizukuBridge.isShizukuInstalled(context));
        assertEquals(ShizukuBridge.State.NOT_INSTALLED, ShizukuBridge.state(context));
    }

    /**
     * 回归测试：binder 没到的时候 requestPermission 会抛，
     * 之前异常被吞掉了，界面照样弹「已发起授权请求」，用户就一直等一个不会出现的弹窗。
     */
    @Test
    public void neverClaimsSuccessWithoutABinder() {
        assertFalse("没有 binder 时不能报告请求成功", ShizukuBridge.requestPermission());
    }

    @Test
    public void binderIsDeadWithoutShizuku() {
        assertFalse(ShizukuBridge.isBinderAlive());
        assertFalse(ShizukuBridge.hasPermission());
        assertFalse(ShizukuBridge.isAvailable(context));
    }

    @Test
    public void versionIsUnknownWithoutABinder() {
        assertEquals(-1, ShizukuBridge.serverVersion());
    }

    @Test
    public void stateIsNeverGrantedWithoutShizuku() {
        for (int i = 0; i < 3; i++) {
            assertFalse(ShizukuBridge.state(context) == ShizukuBridge.State.GRANTED);
        }
    }

    private void installFakeShizuku() {
        PackageInfo info = new PackageInfo();
        info.packageName = ShizukuBridge.shizukuPackageName();
        shadowOf(context.getPackageManager()).installPackage(info);
    }
}
