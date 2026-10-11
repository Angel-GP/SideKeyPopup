package com.linxi.sidekeypopup;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.linxi.sidekeypopup.input.CommandRunner;
import com.linxi.sidekeypopup.input.InputDeviceFinder;

import org.junit.Test;

import java.util.Collections;
import java.util.List;

/**
 * `getevent -lp` 的输出解析。纯 JVM 测试，不碰 Android。
 *
 * 这段逻辑没法在真机上验证（手里没设备），所以至少把各种输出形状钉死。
 */
public class InputDeviceFinderTest {

    private static final String SAMPLE =
            "add device 1: /dev/input/event0\n"
                    + "  name:     \"qpnp_pon\"\n"
                    + "  events:\n"
                    + "    KEY (0001): 0074\n"
                    + "  input props:\n"
                    + "    INPUT_PROP_POINTER\n"
                    + "add device 2: /dev/input/event3\n"
                    + "  name:     \"gpio-keys\"\n"
                    + "  events:\n"
                    + "    KEY (0001): 0072 0073 0074 0075 0076 0077 0078 0079 007a 007b 007c 007d\n"
                    + "                02df 02e0\n"
                    + "    ABS (0003): 0000 0001\n"
                    + "                0002 0003\n"
                    + "add device 3: /dev/input/event5\n"
                    + "  name:     \"touchscreen\"\n"
                    + "  events:\n"
                    + "    ABS (0003): 002f 0030\n";

    @Test
    public void picksTheDeviceThatAdvertisesTheSideKey() {
        List<String> devices = InputDeviceFinder.parse(SAMPLE, 735);
        assertEquals(1, devices.size());
        assertEquals("/dev/input/event3", devices.get(0));
    }

    @Test
    public void findsCodesThatWrappedOntoTheNextLine() {
        // 0x2df 落在 KEY 列表的续行上，也得认出来
        assertTrue(InputDeviceFinder.parse(SAMPLE, 0x2df).contains("/dev/input/event3"));
        assertTrue(InputDeviceFinder.parse(SAMPLE, 0x2e0).contains("/dev/input/event3"));
    }

    @Test
    public void doesNotMatchCodesFromOtherEventTypes() {
        // 0x2f 只在 ABS 段里出现过，不能算
        assertTrue(InputDeviceFinder.parse(SAMPLE, 0x2f).isEmpty());
        // ABS 的续行 0002/0003 也不能被当成 KEY
        assertTrue(InputDeviceFinder.parse(SAMPLE, 0x0002).isEmpty());
    }

    @Test
    public void returnsNothingWhenNoDeviceHasTheKey() {
        assertTrue(InputDeviceFinder.parse(SAMPLE, 999).isEmpty());
    }

    @Test
    public void survivesGarbageInput() {
        assertTrue(InputDeviceFinder.parse("", 735).isEmpty());
        assertTrue(InputDeviceFinder.parse(null, 735).isEmpty());
        assertTrue(InputDeviceFinder.parse("add device 1: /dev/input/event0\n", 735).isEmpty());
        assertTrue(InputDeviceFinder.parse("add device 1: /dev/input/event0\n  KEY (0001): zz\n", 735).isEmpty());
    }

    @Test
    public void sideKeyConstantMatchesTheVendorKey() {
        // 735 = 0x2df = BTN_TRIGGER_HAPPY32
        assertEquals(0x2df, Config.KEY_CODE);
    }

    @Test
    public void tapThresholdStaysBelowTheSystemLongPress() {
        // 必须比系统的长按阈值（一般 500ms）短，否则会抢系统的长按
        assertTrue(Config.TAP_MAX_MS < 500);
        assertFalse(Config.TAP_MAX_MS <= 0);
    }

    // ------------------------------------------------------------ 兜底

    /**
     * 按键码没被任何设备声明时，退回到「所有会报 KEY 的设备」，
     * 而不是盲猜一个 /dev/input/event0（那多半是电源键，猜错了就永远读不到侧键）。
     */
    @Test
    public void fallsBackToEveryDeviceThatReportsKeys() {
        List<String> devices = InputDeviceFinder.parseAllWithKeys(SAMPLE);
        assertTrue(devices.contains("/dev/input/event0"));
        assertTrue(devices.contains("/dev/input/event3"));
        // 触摸屏只报 ABS，不该在里面
        assertFalse(devices.contains("/dev/input/event5"));
    }

    @Test
    public void fallbackIsEmptyWhenNothingReportsKeys() {
        assertTrue(InputDeviceFinder.parseAllWithKeys("").isEmpty());
        assertTrue(InputDeviceFinder.parseAllWithKeys(null).isEmpty());
        assertTrue(InputDeviceFinder.parseAllWithKeys(
                "add device 3: /dev/input/event5\n  ABS (0003): 002f\n").isEmpty());
    }

    /** 找到了精确匹配就不要再顺带盯一堆别的设备。 */
    @Test
    public void exactMatchWinsOverTheFallback() {
        assertEquals(Collections.singletonList("/dev/input/event3"),
                InputDeviceFinder.findAll(runner(SAMPLE), 735));
    }

    @Test
    public void usesTheFallbackWhenTheExactCodeIsMissing() {
        List<String> devices = InputDeviceFinder.findAll(runner(SAMPLE), 999);
        assertTrue(devices.contains("/dev/input/event3"));
        assertFalse(devices.contains("/dev/input/event5"));
    }

    @Test
    public void returnsNothingWhenGeteventFails() {
        assertTrue(InputDeviceFinder.findAll(runner(null), 735).isEmpty());
    }

    /** 假的 runner：只回固定的 getevent 输出，不会真的起进程。 */
    private static CommandRunner runner(final String output) {
        return new CommandRunner() {
            @Override
            public java.lang.Process start(String command) {
                throw new UnsupportedOperationException();
            }

            @Override
            public String output(String command, long timeoutMs) {
                return output;
            }
        };
    }
}
