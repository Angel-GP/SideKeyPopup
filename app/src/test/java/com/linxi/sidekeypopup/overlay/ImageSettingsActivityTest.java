package com.linxi.sidekeypopup.overlay;

import com.linxi.sidekeypopup.R;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 在 JVM 上真跑一遍 Activity 的创建流程：主题解析、所有 Material 控件的构造、
 * 预览重建都会被执行到，任何运行时异常都会让测试失败并打出完整堆栈。
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class ImageSettingsActivityTest {

    private void putImage(Context ctx, String asset) throws Exception {
        File dst = new File(ctx.getFilesDir(), "overlay_image");
        // 从 classpath 读，不要依赖工作目录：CI 上没有本机生成的 build/test
        InputStream in = ImageSettingsActivityTest.class.getResourceAsStream("/" + asset);
        assertTrue("missing test asset " + asset, in != null);
        try (InputStream src = in;
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = src.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }

    private static int countViews(View v) {
        int n = 1;
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                n += countViews(g.getChildAt(i));
            }
        }
        return n;
    }

    private static String dump(View v, int depth) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) {
            sb.append("  ");
        }
        sb.append(v.getClass().getSimpleName());
        if (v instanceof TextView) {
            sb.append(" text='").append(((TextView) v).getText()).append("'");
        }
        sb.append('\n');
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                sb.append(dump(g.getChildAt(i), depth + 1));
            }
        }
        return sb.toString();
    }

    @Test
    public void opensWithoutImage() {
        ActivityController<ImageSettingsActivity> controller =
                Robolectric.buildActivity(ImageSettingsActivity.class);
        ImageSettingsActivity activity = controller.setup().get();
        assertNotNull(activity);
        shadowOf(Looper.getMainLooper()).idle();
        View root = activity.findViewById(android.R.id.content);
        assertNotNull(root);
        System.out.println("view count = " + countViews(root));
    }

    @Test
    public void opensWithPng() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        putImage(ctx, "photo.png");

        ActivityController<ImageSettingsActivity> controller =
                Robolectric.buildActivity(ImageSettingsActivity.class);
        ImageSettingsActivity activity = controller.setup().get();
        assertNotNull(activity);

        // 等后台解码线程把 Drawable 交回主线程
        for (int i = 0; i < 40; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(50);
        }
        shadowOf(Looper.getMainLooper()).idle();

        View root = activity.findViewById(android.R.id.content);
        System.out.println("=== hierarchy with png ===");
        System.out.println(dump(root, 0));
    }

    @Test
    public void opensWithGif() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        putImage(ctx, "anim.gif");

        ActivityController<ImageSettingsActivity> controller =
                Robolectric.buildActivity(ImageSettingsActivity.class);
        ImageSettingsActivity activity = controller.setup().get();
        assertNotNull(activity);

        for (int i = 0; i < 40; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(50);
        }
        shadowOf(Looper.getMainLooper()).idle();

        View root = activity.findViewById(android.R.id.content);
        System.out.println("=== hierarchy with gif ===");
        System.out.println(dump(root, 0));
    }

    @Test
    public void imageProcessorHandlesBoth() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        putImage(ctx, "photo.png");
        assertNotNull(ImageProcessor.loadDrawable(ctx, Prefs.imageFile(ctx), 800, 800));
        putImage(ctx, "anim.gif");
        assertNotNull(ImageProcessor.loadDrawable(ctx, Prefs.imageFile(ctx), 800, 800));
    }

    /** 诊断：逐层看解码在哪一步失败（Robolectric 的 native graphics 支持有限）。 */
    private void diagnose(String asset) throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        putImage(ctx, asset);
        File f = Prefs.imageFile(ctx);
        System.out.println("DIAG[" + asset + "] file exists=" + f.exists() + " len=" + f.length());

        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
        System.out.println("DIAG[" + asset + "] bounds=" + o.outWidth + "x" + o.outHeight);

        android.graphics.Bitmap b = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath());
        System.out.println("DIAG[" + asset + "] bitmapFactory=" + b);

        try {
            android.graphics.Bitmap b2 = android.graphics.ImageDecoder.decodeBitmap(
                    android.graphics.ImageDecoder.createSource(f));
            System.out.println("DIAG[" + asset + "] imageDecoder=" + b2);
        } catch (Throwable t) {
            System.out.println("DIAG[" + asset + "] imageDecoder threw: " + t);
        }

        try {
            System.out.println("DIAG[" + asset + "] movie="
                    + android.graphics.Movie.decodeFile(f.getAbsolutePath()));
        } catch (Throwable t) {
            System.out.println("DIAG[" + asset + "] movie threw: " + t);
        }

        System.out.println("DIAG[" + asset + "] loadDrawable="
                + ImageProcessor.loadDrawable(ctx, f, 800, 800));
    }

    @Test
    public void diagnosePng() throws Exception {
        diagnose("photo.png");
    }

    @Test
    public void diagnoseGif() throws Exception {
        diagnose("anim.gif");
    }

    // ---------------------------------------------------------------- shapes

    /**
     * 量好、布局好一个 MaskedImageView。
     *
     * 注意：形状和羽化现在都在 onDraw 里用画布遮罩（saveLayer + DST_IN）实现，
     * 不再走 ViewOutlineProvider / setClipToOutline——那套东西只在硬件加速的
     * RenderNode 上生效，悬浮窗落到软件渲染时形状会完全失效。
     */
    private MaskedImageView laidOut(Context ctx, ImageSettings s, int w, int h) {
        MaskedImageView iv = new MaskedImageView(ctx);
        iv.apply(s, 2f);
        iv.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        iv.layout(0, 0, w, h);
        return iv;
    }

    /** 圆角半径要正确，并且超过短边一半时夹住；同时确认不再依赖 outline。 */
    @Test
    public void shapeMaskGeometryIsCorrect() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ImageSettings s = ImageSettings.load(ctx);
        s.featherPercent = 0;

        s.shape = ImageSettings.SHAPE_ROUND;
        s.cornerDp = 24;
        assertEquals("24dp * density(2) 应该是 48px",
                48f, laidOut(ctx, s, 400, 300).cornerRadiusPx(400, 300), 0.01f);

        s.cornerDp = 400;
        assertEquals("圆角半径要夹到短边的一半",
                150f, laidOut(ctx, s, 400, 300).cornerRadiusPx(400, 300), 0.01f);

        // 形状不再通过 outline 实现：ImageView 自带一个 BACKGROUND provider，
        // 关键是绝不能开 clipToOutline（那才是真正会裁剪的开关，且只在硬件加速下生效）
        for (int shape = ImageSettings.SHAPE_NONE; shape <= ImageSettings.SHAPE_OVAL; shape++) {
            s.shape = shape;
            MaskedImageView iv = laidOut(ctx, s, 400, 300);
            System.out.println("SHAPE[" + shape + "] provider=" + iv.getOutlineProvider()
                    + " clipToOutline=" + iv.getClipToOutline()
                    + " cornerPx=" + iv.cornerRadiusPx(400, 300));
            assertTrue("形状不该再依赖 clipToOutline", !iv.getClipToOutline());
        }
    }

    /**
     * 四种形状 × 有/无羽化都真的走一遍 onDraw。
     * Robolectric 的 LEGACY 画布不会真的栅格化，但渐变/圆角矩形的参数错误会在这里抛异常。
     */
    @Test
    public void everyShapeAndFeatherDraws() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        putImage(ctx, "photo.png");
        Drawable d = ImageProcessor.loadDrawable(ctx, Prefs.imageFile(ctx), 400, 400);
        assertNotNull(d);

        ImageSettings s = ImageSettings.load(ctx);
        s.cornerDp = 24;

        for (int shape = ImageSettings.SHAPE_NONE; shape <= ImageSettings.SHAPE_OVAL; shape++) {
            for (int feather : new int[]{0, 1, 25, 50, 100}) {
                s.shape = shape;
                s.featherPercent = feather;
                MaskedImageView iv = new MaskedImageView(ctx);
                iv.setImageDrawable(d);
                iv.apply(s, 2f);
                iv.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY));
                iv.layout(0, 0, 400, 300);

                android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(
                        400, 300, android.graphics.Bitmap.Config.ARGB_8888);
                iv.draw(new android.graphics.Canvas(bmp));
                System.out.println("DRAW shape=" + shape + " feather=" + feather + " ok");
            }
        }
    }

    /** 预览里应该真的出现一张 MaskedImageView，而不是"读取失败"的占位文字。 */
    @Test
    public void previewShowsTheImage() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        putImage(ctx, "photo.png");

        ActivityController<ImageSettingsActivity> controller =
                Robolectric.buildActivity(ImageSettingsActivity.class);
        ImageSettingsActivity activity = controller.setup().get();
        for (int i = 0; i < 40; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(50);
        }
        shadowOf(Looper.getMainLooper()).idle();

        View root = activity.findViewById(android.R.id.content);
        assertNotNull("预览里没有 MaskedImageView", findByClass(root, MaskedImageView.class));
        assertNull("预览显示了读取失败占位", findByText(root, "图片读取失败，试试重新选一张"));
    }

    /**
     * 椭圆羽化的缩放方向必须对：渐变半径（短边一半）乘上系数后要正好等于椭圆的半轴。
     * 方向搞反过一次，结果遮罩只盖住中间一条，图片被从中间截断。
     */
    @Test
    public void ovalFeatherScaleMatchesTheEllipse() {
        Context ctx = ApplicationProvider.getApplicationContext();
        MaskedImageView iv = new MaskedImageView(ctx);

        // 900x600 的宽图：短边 600，渐变半径 300
        float[] s = iv.roundFeatherScale(900, 600);
        assertEquals("x 方向缩放", 1.5f, s[0], 0.001f);
        assertEquals("y 方向缩放", 1f, s[1], 0.001f);
        assertEquals("渐变圆拉到椭圆后 x 半轴应该是 450", 450f, 300f * s[0], 0.5f);
        assertEquals("渐变圆拉到椭圆后 y 半轴应该是 300", 300f, 300f * s[1], 0.5f);

        // 高图反过来
        float[] t = iv.roundFeatherScale(600, 900);
        assertEquals(1f, t[0], 0.001f);
        assertEquals(1.5f, t[1], 0.001f);

        // 正方形不缩放
        float[] q = iv.roundFeatherScale(400, 400);
        assertEquals(1f, q[0], 0.001f);
        assertEquals(1f, q[1], 0.001f);
    }

    /**
     * 遮罩位图的尺寸和缓存：同参数命中缓存，尺寸/形状/半径/羽化变了必须重建。
     * （Robolectric 的 LEGACY 画布不真的栅格化，所以只能验到这一步。）
     */
    @Test
    public void maskBitmapIsCachedAndRebuiltOnChange() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ImageSettings s = ImageSettings.load(ctx);
        s.shape = ImageSettings.SHAPE_ROUND;
        s.cornerDp = 24;
        s.featherPercent = 0;

        MaskedImageView iv = new MaskedImageView(ctx);
        iv.apply(s, 2f);

        android.graphics.Bitmap a = iv.maskBitmap(400, 300);
        assertNotNull(a);
        assertEquals(400, a.getWidth());
        assertEquals(300, a.getHeight());
        assertSame("同样的参数应该命中缓存", a, iv.maskBitmap(400, 300));

        assertNotSame("尺寸变了要重建", a, iv.maskBitmap(400, 400));

        iv.apply(s, 2f);
        android.graphics.Bitmap b = iv.maskBitmap(400, 300);
        assertNotSame("重新 apply 之后要重建", a, b);

        s.shape = ImageSettings.SHAPE_OVAL;
        iv.apply(s, 2f);
        assertNotSame("形状变了要重建", b, iv.maskBitmap(400, 300));

        s.featherPercent = 20;
        iv.apply(s, 2f);
        android.graphics.Bitmap c = iv.maskBitmap(400, 300);
        assertNotSame("羽化变了要重建", b, c);

        s.cornerDp = 8;
        iv.apply(s, 2f);
        assertNotSame("圆角半径变了要重建", c, iv.maskBitmap(400, 300));
    }

    /** 悬浮窗内容才是核心路径，四种形状都要能建起来并布局。 */
    @Test
    public void overlayContentBuildsForEveryShape() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        putImage(ctx, "photo.png");
        Drawable d = ImageProcessor.loadDrawable(ctx, Prefs.imageFile(ctx), 800, 800);
        assertNotNull(d);

        ImageSettings s = ImageSettings.load(ctx);
        s.scalePercent = 100;
        s.featherPercent = 0;

        for (int shape = ImageSettings.SHAPE_NONE; shape <= ImageSettings.SHAPE_OVAL; shape++) {
            s.shape = shape;
            View v = OverlayContent.create(ctx, s, d, 800, 800, 1f);
            assertNotNull("shape=" + shape + " 返回 null", v);
            v.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.AT_MOST));
            v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
            System.out.println("OVERLAY shape=" + shape
                    + " size=" + v.getMeasuredWidth() + "x" + v.getMeasuredHeight()
                    + " views=" + countViews(v));
            assertTrue("布局尺寸不该为 0", v.getMeasuredWidth() > 0 && v.getMeasuredHeight() > 0);
        }
    }

    /** 预览用的 uiScale 必须真的把圆角半径一起缩小，否则预览和真机对不上。 */
    @Test
    public void previewScalesCornerRadius() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        putImage(ctx, "photo.png");
        Drawable d = ImageProcessor.loadDrawable(ctx, Prefs.imageFile(ctx), 800, 800);

        ImageSettings s = ImageSettings.load(ctx);
        s.shape = ImageSettings.SHAPE_ROUND;
        s.cornerDp = 40;
        s.featherPercent = 0;

        MaskedImageView full = firstMasked(OverlayContent.create(ctx, s, d, 800, 800, 1f));
        MaskedImageView half = firstMasked(OverlayContent.create(ctx, s, d, 800, 800, 0.5f));
        assertNotNull(full);
        assertNotNull(half);

        float density = ctx.getResources().getDisplayMetrics().density;
        assertEquals("真机尺寸下圆角 = 40dp", 40f * density,
                full.cornerRadiusPx(800, 800), 0.6f);
        assertEquals("预览尺寸下圆角要减半", 20f * density,
                half.cornerRadiusPx(800, 800), 0.6f);
    }

    private static MaskedImageView firstMasked(View v) {
        return (MaskedImageView) findByClass(v, MaskedImageView.class);
    }

    /**
     * 合并成一个应用之后，悬浮图服务只由同进程调用，所以清单里必须是 exported="false"。
     * 这条是回归测试：曾经它是个跨应用接口（还带着 SHOW/CLOSE 的 intent-filter），
     * 现在不该再对外暴露。
     */
    @Test
    public void overlayServiceIsNotExportedAnymore() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        android.content.pm.ServiceInfo info = ctx.getPackageManager().getServiceInfo(
                new android.content.ComponentName(ctx.getPackageName(), OverlayService.class.getName()),
                0);
        assertNotNull("清单里找不到 OverlayService", info);
        assertFalse("同进程调用就够了，不该再导出给别的应用", info.exported);
        assertEquals("com.linxi.sidekeypopup.action.SHOW", OverlayService.ACTION_SHOW);
    }

    /**
     * 形状 chip 的下标必须和 ImageSettings.SHAPE_* 一一对应，
     * 并且拖「圆角半径」时要自动切到「圆角」——否则用户会以为圆角半径坏了。
     */
    @Test
    public void shapeChipsDriveShapeSetting() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        putImage(ctx, "photo.png");

        ImageSettings s = ImageSettings.load(ctx);
        s.shape = ImageSettings.SHAPE_NONE;
        s.cornerDp = 0;
        s.featherPercent = 0;
        s.save(ctx);

        ActivityController<ImageSettingsActivity> controller =
                Robolectric.buildActivity(ImageSettingsActivity.class);
        ImageSettingsActivity activity = controller.setup().get();
        shadowOf(Looper.getMainLooper()).idle();

        View root = activity.findViewById(android.R.id.content);
        com.google.android.material.chip.ChipGroup group =
                (com.google.android.material.chip.ChipGroup) findByClass(
                        root, com.google.android.material.chip.ChipGroup.class);
        assertNotNull("找不到形状 ChipGroup", group);
        assertEquals("形状应该有 4 个选项", 4, group.getChildCount());

        for (int i = 0; i < group.getChildCount(); i++) {
            View v = group.getChildAt(i);
            assertTrue("ChipGroup 的子 View 应该都是 Chip", v instanceof com.google.android.material.chip.Chip);
            com.google.android.material.chip.Chip chip =
                    (com.google.android.material.chip.Chip) v;
            chip.performClick();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("第 " + i + " 个 chip('" + chip.getText() + "') 对应的 shape 不对",
                    i, settingsShape(activity));
        }

        // 形状是「原图」时拖圆角半径，应该自动切到「圆角」
        ImageSettings fresh = ImageSettings.load(ctx);
        fresh.shape = ImageSettings.SHAPE_NONE;
        fresh.cornerDp = 0;
        fresh.save(ctx);
        ImageSettingsActivity a2 = Robolectric.buildActivity(ImageSettingsActivity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        com.google.android.material.slider.Slider radius =
                findRadiusSlider(a2.findViewById(android.R.id.content));
        assertNotNull("找不到圆角半径滑块", radius);
        radius.setValue(24f);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("拖圆角半径应该自动切到圆角", ImageSettings.SHAPE_ROUND, settingsShape(a2));
        assertEquals("圆角半径没写进设置", 24, settingsCornerDp(a2));
    }

    private static com.google.android.material.slider.Slider findRadiusSlider(View v) {
        if (v instanceof com.google.android.material.slider.Slider) {
            com.google.android.material.slider.Slider sl =
                    (com.google.android.material.slider.Slider) v;
            if (sl.getValueFrom() == 0f && sl.getValueTo() == 80f) {
                return sl;
            }
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                com.google.android.material.slider.Slider hit = findRadiusSlider(g.getChildAt(i));
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static int settingsShape(ImageSettingsActivity a) throws Exception {
        return settingsField(a, "shape");
    }

    private static int settingsCornerDp(ImageSettingsActivity a) throws Exception {
        return settingsField(a, "cornerDp");
    }

    private static int settingsField(ImageSettingsActivity a, String name) throws Exception {
        java.lang.reflect.Field f = ImageSettingsActivity.class.getDeclaredField("s");
        f.setAccessible(true);
        ImageSettings s = (ImageSettings) f.get(a);
        java.lang.reflect.Field target = ImageSettings.class.getDeclaredField(name);
        target.setAccessible(true);
        return target.getInt(s);
    }

    private static View findByClass(View v, Class<?> type) {
        if (type.isInstance(v)) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findByClass(g.getChildAt(i), type);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }

    private static View findByText(View v, String text) {
        if (v instanceof TextView && text.contentEquals(((TextView) v).getText())) {
            return v;
        }
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) {
                View hit = findByText(g.getChildAt(i), text);
                if (hit != null) {
                    return hit;
                }
            }
        }
        return null;
    }
}
