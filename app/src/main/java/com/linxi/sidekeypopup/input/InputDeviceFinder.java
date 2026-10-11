package com.linxi.sidekeypopup.input;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 找出侧键挂在哪个 /dev/input/eventN 上。
 *
 * 办法是跑 `getevent -lp`，它会列出每个设备支持的按键能力，例如：
 * <pre>
 * add device 1: /dev/input/event3
 *   name:     "gpio-keys"
 *   events:
 *     KEY (0001): 0072 0073 02df
 * </pre>
 * 按键能力里含 0x2df（= 735）的那个设备就是我们要读的。
 */
public final class InputDeviceFinder {

    private static final String KEY_PREFIX = "KEY (0001):";
    private static final long COMMAND_TIMEOUT_MS = 6000L;

    private InputDeviceFinder() {
    }

    /**
     * 找出该读哪些设备。
     *
     * 先找声明了 keyCode 的那个设备；如果一个都没有（有些 ROM 的 getevent 输出
     * 看不到厂商按键，或者按键码不是我们猜的那个），就退回到「所有会报 KEY 事件的设备」。
     *
     * 这里不用「猜一个 /dev/input/event0」的兜底：event0 多半是电源键，
     * 猜错了会安静地读一个永远不会有侧键的设备，比读多个设备更糟。
     */
    public static List<String> findAll(CommandRunner runner, int keyCode) {
        String output = run(runner);
        if (output == null || output.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> exact = parse(output, keyCode);
        if (!exact.isEmpty()) {
            return exact;
        }
        return parseAllWithKeys(output);
    }

    /** 返回第一个支持 keyCode 的设备路径；找不到返回 null。 */
    public static String find(CommandRunner runner, int keyCode) {
        List<String> devices = findAll(runner, keyCode);
        return devices.isEmpty() ? null : devices.get(0);
    }

    private static String run(CommandRunner runner) {
        // -l 打名字，-p 打能力；老一点的 toybox 只认 -p，所以两条都试。
        String output = runner.output("getevent -lp", COMMAND_TIMEOUT_MS);
        if (output == null || output.isEmpty()) {
            output = runner.output("getevent -p", COMMAND_TIMEOUT_MS);
        }
        return output;
    }

    /** 从 `getevent -lp` 的文本里挑出按键能力包含 keyCode 的设备路径。 */
    public static List<String> parse(CharSequence output, int keyCode) {
        List<String> hits = new ArrayList<>();
        scan(output, keyCode, hits, null);
        return hits;
    }

    /** 所有报 KEY 事件的设备——不知道按键码时用它。 */
    public static List<String> parseAllWithKeys(CharSequence output) {
        List<String> hits = new ArrayList<>();
        scan(output, -1, null, hits);
        return hits;
    }

    /**
     * 扫一遍 getevent 的输出。
     *
     * @param keyCode 要精确匹配的按键码；负数表示不做精确匹配
     * @param exact   匹配到 keyCode 的设备往这里放
     * @param anyKey  只要设备有 KEY 能力就往这里放；可以为 null
     */
    private static void scan(CharSequence output, int keyCode, List<String> exact, List<String> anyKey) {
        Set<String> exactHits = exact == null ? null : new LinkedHashSet<>();
        Set<String> keyHits = anyKey == null ? null : new LinkedHashSet<>();

        String currentDevice = null;
        boolean inKeySection = false;

        String text = output == null ? "" : output.toString();
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }

            if (line.startsWith("add device")) {
                currentDevice = extractDevicePath(line);
                inKeySection = false;
                continue;
            }

            if (line.startsWith(KEY_PREFIX)) {
                inKeySection = true;
                if (keyHits != null && currentDevice != null) {
                    keyHits.add(currentDevice);
                }
                line = line.substring(KEY_PREFIX.length()).trim();
            } else if (inKeySection && isHexList(line)) {
                // KEY 能力列表太长时会折行，续行只有一串十六进制。
            } else {
                inKeySection = false;
                continue;
            }

            if (exactHits != null && currentDevice != null && containsCode(line, keyCode)) {
                exactHits.add(currentDevice);
            }
        }

        if (exact != null && exactHits != null) {
            exact.addAll(exactHits);
        }
        if (anyKey != null && keyHits != null) {
            anyKey.addAll(keyHits);
        }
    }

    private static String extractDevicePath(String line) {
        int slash = line.indexOf("/dev/");
        if (slash < 0) {
            return null;
        }
        String path = line.substring(slash).trim();
        int space = path.indexOf(' ');
        return space < 0 ? path : path.substring(0, space);
    }

    private static boolean isHexList(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            boolean ok = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F') || c == ' ';
            if (!ok) {
                return false;
            }
        }
        return line.trim().length() > 0;
    }

    private static boolean containsCode(String line, int keyCode) {
        for (String token : line.split("\\s+")) {
            if (token.isEmpty()) {
                continue;
            }
            try {
                if (Integer.parseInt(token, 16) == keyCode) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // 不是十六进制就跳过
            }
        }
        return false;
    }
}
