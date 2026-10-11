package com.linxi.sidekeypopup.overlay;

import com.linxi.sidekeypopup.R;

import android.content.Context;

import java.io.File;

/**
 * 记录"是否已经选过图片"。
 *
 * 图片本体会被复制到应用私有目录（files/overlay_image），
 * 这样就不依赖系统相册 URI 的持久化授权，重启、换机同步都不会丢。
 */
public final class Prefs {

    private static final String IMAGE_NAME = "overlay_image";

    private Prefs() {
    }

    public static File imageFile(Context c) {
        return new File(c.getFilesDir(), IMAGE_NAME);
    }

    public static boolean hasImage(Context c) {
        File f = imageFile(c);
        return f.exists() && f.length() > 0L;
    }

    public static void clearImage(Context c) {
        File f = imageFile(c);
        if (f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }
}
