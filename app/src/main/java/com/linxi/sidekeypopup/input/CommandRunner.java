package com.linxi.sidekeypopup.input;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * 用某个特权身份执行命令的抽象。
 *
 * 两个实现：
 *  - Root：每条命令都套一层 su
 *  - Shizuku：代码本身就跑在 shell 进程里，直接 exec 就行
 */
public interface CommandRunner {

    /** 启动一条常驻命令，返回的 Process 用来读它的输出流。 */
    Process start(String command) throws IOException;

    /** 跑一条会结束的命令并把 stdout 全部读回来；失败返回 null。 */
    String output(String command, long timeoutMs);

    /** 把命令的 stdout 读完，超时就放弃。两条路都用这个。 */
    static String readAll(Process process, long timeoutMs) {
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            InputStream in = process.getInputStream();
            byte[] chunk = new byte[4096];
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                if (in.available() > 0) {
                    int n = in.read(chunk);
                    if (n < 0) {
                        break;
                    }
                    buffer.write(chunk, 0, n);
                } else if (!process.isAlive()) {
                    break;
                } else {
                    Thread.sleep(20L);
                }
            }
            return buffer.toString("UTF-8");
        } catch (Throwable error) {
            return null;
        } finally {
            try {
                process.waitFor(200, TimeUnit.MILLISECONDS);
            } catch (Throwable ignored) {
                // ignore
            }
            try {
                process.destroy();
            } catch (Throwable ignored) {
                // ignore
            }
        }
    }
}
