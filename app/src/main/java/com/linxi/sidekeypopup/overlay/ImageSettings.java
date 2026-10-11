package com.linxi.sidekeypopup.overlay;

import com.linxi.sidekeypopup.R;

import android.content.Context;
import android.content.SharedPreferences;

/** 悬浮图的所有可调参数，存在 SharedPreferences 里。 */
public final class ImageSettings {

    private static final String PREF = "sidekeypopup_image_settings";

    /** 图片形状 */
    public static final int SHAPE_NONE = 0;
    public static final int SHAPE_ROUND = 1;
    public static final int SHAPE_CIRCLE = 2;
    public static final int SHAPE_OVAL = 3;

    /** 图片大小，占屏幕宽/高的百分比 */
    public int scalePercent = 88;
    /** 图片整体不透明度 20~100 */
    public int alphaPercent = 100;
    /** 旋转角度 -180~180 */
    public int rotation = 0;
    /** 见 SHAPE_* */
    public int shape = SHAPE_NONE;
    /** 圆角半径（dp），仅 SHAPE_ROUND 生效 */
    public int cornerDp = 16;
    /** 边缘羽化（模糊边界）0~50，占短边半径的百分比 */
    public int featherPercent = 0;

    public static ImageSettings load(Context c) {
        SharedPreferences sp = c.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        ImageSettings s = new ImageSettings();
        s.scalePercent = sp.getInt("scale", s.scalePercent);
        s.alphaPercent = sp.getInt("alpha", s.alphaPercent);
        s.rotation = sp.getInt("rotation", s.rotation);
        s.shape = sp.getInt("shape", s.shape);
        s.cornerDp = sp.getInt("corner", s.cornerDp);
        s.featherPercent = sp.getInt("feather", s.featherPercent);
        return s;
    }

    /** 后台线程做图片变换时用，避免和 UI 线程读同一个可变对象。 */
    public ImageSettings copy() {
        ImageSettings o = new ImageSettings();
        o.scalePercent = scalePercent;
        o.alphaPercent = alphaPercent;
        o.rotation = rotation;
        o.shape = shape;
        o.cornerDp = cornerDp;
        o.featherPercent = featherPercent;
        return o;
    }

    public void save(Context c) {
        c.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putInt("scale", scalePercent)
                .putInt("alpha", alphaPercent)
                .putInt("rotation", rotation)
                .putInt("shape", shape)
                .putInt("corner", cornerDp)
                .putInt("feather", featherPercent)
                .apply();
    }

    /** 是否需要在渲染前把位图旋转 90 度（用于交换宽高上限）。 */
    public boolean swapsDimensions() {
        int r = ((rotation % 360) + 360) % 360;
        return (r > 45 && r < 135) || (r > 225 && r < 315);
    }
}
