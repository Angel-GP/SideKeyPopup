/*
 * 本文件的部分实现沿用自 SlimeNull/OppoCustomSideButtonFunctions
 *     https://github.com/SlimeNull/OppoCustomSideButtonFunctions
 *     Copyright (C) SlimeNull Issac
 *     以 GNU Lesser General Public License v3.0 授权
 *
 * 沿用内容：/dev/input/eventN 的 struct input_event 大小判断（64 位 ABI 下 24 字节、
 * 32 位下 16 字节）以及由此推出的 type / code / value 字段偏移，
 * 来自上游 app/src/main/java/.../xposed/RawInputEventReader.kt。
 *
 * 本项目的改动（2026-10-11）：改写为 Java；从自带线程的读取器拆成无状态的纯解析器；
 * 增加跨 read() 的半包拼装；64 位判断改用 Process.is64Bit()。
 *
 * 本文件以 GNU LGPL-3.0 授权，详见项目根目录 LICENSE 与 NOTICE.md。
 */
package com.linxi.sidekeypopup.input;

import android.os.Process;
import android.os.Build;
import android.view.KeyEvent;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.function.BooleanSupplier;

/**
 * 解析 /dev/input/eventN 的二进制流。
 *
 * 内核写出来的是 struct input_event：
 * <pre>
 * 64 位：timeval(8+8) + type(2) + code(2) + value(4) = 24 字节
 * 32 位：timeval(4+4) + type(2) + code(2) + value(4) = 16 字节
 * </pre>
 * 这个判断方式沿用自上游 OppoCustomSideButtonFunctions，见文件头的声明。
 */
public final class InputEventStream {

    private static final int EV_KEY = 0x01;
    private static final int VALUE_UP = 0;
    private static final int VALUE_DOWN = 1;

    public interface Callback {
        /**
         * @param action      KeyEvent.ACTION_DOWN / ACTION_UP
         * @param code        Linux 按键码
         * @param eventTimeMs 内核给的事件时间戳（毫秒），用来算按住多久
         */
        void onKey(int action, int code, long eventTimeMs);
    }

    private InputEventStream() {
    }

    /** 一个 input_event 结构体有多少字节。 */
    public static int eventSize() {
        try {
            return Process.is64Bit() ? 24 : 16;
        } catch (Throwable ignored) {
            return Build.SUPPORTED_64_BIT_ABIS.length > 0 ? 24 : 16;
        }
    }

    /**
     * 一直读到流结束或者 running 变成 false。
     * 内部按结构体大小切分，所以不用担心中间被截断。
     */
    public static void read(InputStream in, Callback callback, BooleanSupplier running) throws Exception {
        int size = eventSize();
        int typeOffset = size == 24 ? 16 : 8;
        byte[] chunk = new byte[size * 64];
        ByteBuffer buffer = ByteBuffer.wrap(chunk).order(ByteOrder.LITTLE_ENDIAN);
        int carry = 0;

        while (running.getAsBoolean()) {
            int read = in.read(chunk, carry, chunk.length - carry);
            if (read <= 0) {
                break;
            }
            int total = carry + read;
            int offset = 0;
            while (offset + size <= total) {
                dispatch(buffer, offset, typeOffset, callback);
                offset += size;
            }
            carry = total - offset;
            if (carry > 0) {
                System.arraycopy(chunk, offset, chunk, 0, carry);
            }
        }
    }

    private static void dispatch(ByteBuffer buffer, int offset, int typeOffset, Callback callback) {
        int type = buffer.getShort(offset + typeOffset) & 0xFFFF;
        if (type != EV_KEY) {
            return;
        }
        int code = buffer.getShort(offset + typeOffset + 2) & 0xFFFF;
        int value = buffer.getInt(offset + typeOffset + 4);
        if (value != VALUE_DOWN && value != VALUE_UP) {
            return; // 2 = 长按自动重复，忽略
        }

        long seconds = buffer.getLong(offset);
        long micros = buffer.getLong(offset + 8);
        long eventTimeMs = seconds * 1000L + micros / 1000L;

        callback.onKey(value == VALUE_DOWN ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP, code, eventTimeMs);
    }
}
