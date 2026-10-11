package com.linxi.sidekeypopup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import com.linxi.sidekeypopup.input.SideKeyEngine;
import com.linxi.sidekeypopup.input.SourceManager;

import org.junit.Test;

/**
 * 读取方式的选择逻辑。
 *
 * 这块出过一次真问题：一开始自动模式把无障碍排在最前面，
 * 结果用户给了 Shizuku 权限却一直走无障碍，而一加的侧键根本到不了无障碍层，
 * 表现就是「权限都给了、来源也显示正常，但按了没反应」。
 */
public class SourceChoiceTest {

    private static final boolean YES = true;
    private static final boolean NO = false;

    // ------------------------------------------------------------ 自动

    /**
     * 回归测试：自动模式下已经授权的 Shizuku 必须压过无障碍。
     * 这正是「权限正常了但单击没反应」的成因。
     */
    @Test
    public void autoPrefersShizukuOverAccessibility() {
        assertEquals(SideKeyEngine.SOURCE_SHIZUKU,
                SourceManager.chooseSource(Config.SOURCE_AUTO, YES, NO, YES));
        assertEquals(SideKeyEngine.SOURCE_SHIZUKU,
                SourceManager.chooseSource(Config.SOURCE_AUTO, YES, YES, YES));
    }

    @Test
    public void autoPrefersRootOverAccessibility() {
        assertEquals(SideKeyEngine.SOURCE_ROOT,
                SourceManager.chooseSource(Config.SOURCE_AUTO, YES, YES, NO));
    }

    @Test
    public void autoStillUsesAccessibilityWhenNothingElseIsAvailable() {
        assertEquals(SideKeyEngine.SOURCE_ACCESSIBILITY,
                SourceManager.chooseSource(Config.SOURCE_AUTO, YES, NO, NO));
    }

    @Test
    public void autoGivesUpWhenNothingIsAvailable() {
        assertEquals(SideKeyEngine.SOURCE_NONE,
                SourceManager.chooseSource(Config.SOURCE_AUTO, NO, NO, NO));
    }

    // ------------------------------------------------------------ 手动

    @Test
    public void forcedAccessibilityIsHonoured() {
        assertEquals(SideKeyEngine.SOURCE_ACCESSIBILITY,
                SourceManager.chooseSource(Config.SOURCE_ACCESSIBILITY, YES, YES, YES));
    }

    @Test
    public void forcedShizukuIsHonoured() {
        assertEquals(SideKeyEngine.SOURCE_SHIZUKU,
                SourceManager.chooseSource(Config.SOURCE_SHIZUKU, YES, YES, YES));
    }

    /** 选的那条没权限时退回自动，不能直接摆烂成「未就绪」。 */
    @Test
    public void unavailableChoiceFallsBackInsteadOfDoingNothing() {
        assertEquals(SideKeyEngine.SOURCE_SHIZUKU,
                SourceManager.chooseSource(Config.SOURCE_ROOT, NO, NO, YES));
        assertEquals(SideKeyEngine.SOURCE_ACCESSIBILITY,
                SourceManager.chooseSource(Config.SOURCE_SHIZUKU, YES, NO, NO));
    }

    @Test
    public void unavailableChoiceWithNothingElseStillGivesUp() {
        assertEquals(SideKeyEngine.SOURCE_NONE,
                SourceManager.chooseSource(Config.SOURCE_SHIZUKU, NO, NO, NO));
    }

    // ------------------------------------------------------------ 常量映射

    /**
     * Config 和引擎的常量是两套独立编号，数值会撞车
     * （Config.SOURCE_SHIZUKU 是 2，而引擎的 2 是 Root）。
     * 所以必须走 engineSourceFor 映射，不能直接拿来比。
     */
    @Test
    public void configAndEngineConstantsAreMappedNotCompared() {
        assertNotEquals(Config.SOURCE_SHIZUKU, SideKeyEngine.SOURCE_SHIZUKU);
        assertEquals(SideKeyEngine.SOURCE_SHIZUKU,
                SourceManager.engineSourceFor(Config.SOURCE_SHIZUKU));
        assertEquals(SideKeyEngine.SOURCE_ROOT,
                SourceManager.engineSourceFor(Config.SOURCE_ROOT));
        assertEquals(SideKeyEngine.SOURCE_ACCESSIBILITY,
                SourceManager.engineSourceFor(Config.SOURCE_ACCESSIBILITY));
        assertEquals(SideKeyEngine.SOURCE_NONE,
                SourceManager.engineSourceFor(Config.SOURCE_AUTO));
    }
}
