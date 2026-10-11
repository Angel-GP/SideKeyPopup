package com.linxi.sidekeypopup.overlay;

import com.linxi.sidekeypopup.R;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.widget.ImageView;

/**
 * 支持「形状裁剪」和「边缘羽化」的 ImageView。
 *
 * 做法分两步：
 * <ol>
 *   <li>把「形状 + 羽化」先画成一张和 view 等大的遮罩位图（普通位图画布，软件 Skia）；</li>
 *   <li>在 onDraw 里用一张**铺满整个 view** 的 drawRect + DST_IN 把遮罩乘到图片上。</li>
 * </ol>
 *
 * 两个关键点，都是踩过坑才定下来的：
 * <ul>
 *   <li><b>DST_IN 只作用于"真正画到的像素"。</b>所以乘遮罩那一步必须是铺满整个 view 的矩形。
 *       直接 drawRoundRect / drawOval 配 DST_IN 的话，形状外面根本不会被处理，
 *       表现就是"形状和圆角半径完全不生效"，而铺满 view 的羽化却是好的。</li>
 *   <li><b>不用 setClipToOutline / ViewOutlineProvider。</b>那套东西只在硬件加速的
 *       RenderNode 上生效（AOSP 里 View.onAttachedToWindow() 才 rebuildOutline()），
 *       悬浮窗落到软件渲染时形状会静默失效。</li>
 * </ul>
 */
public class MaskedImageView extends ImageView {

    private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    private int shape = ImageSettings.SHAPE_NONE;
    private int cornerPx;
    private int featherPercent;

    /** 缓存的遮罩位图，尺寸/形状/半径/羽化任一变化就重建。 */
    private Bitmap mask;
    private int maskW;
    private int maskH;
    private int maskKey;

    public MaskedImageView(Context context) {
        super(context);
    }

    public void apply(ImageSettings s, float density) {
        this.shape = s.shape;
        this.cornerPx = Math.round(s.cornerDp * density);
        this.featherPercent = s.featherPercent;
        dropMask();
        invalidate();
    }

    /** 当前形状在给定尺寸下的圆角半径，已夹到短边的一半（再大就没有意义了）。 */
    float cornerRadiusPx(int w, int h) {
        return Math.min((float) cornerPx, Math.min(w, h) / 2f);
    }

    private boolean isRound() {
        return shape == ImageSettings.SHAPE_CIRCLE || shape == ImageSettings.SHAPE_OVAL;
    }

    private void dropMask() {
        // 故意不 recycle：BitmapShader 可能还被上一帧的显示列表引用着
        mask = null;
        maskKey = 0;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        dropMask();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        dropMask();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0 || (shape == ImageSettings.SHAPE_NONE && featherPercent <= 0)) {
            super.onDraw(canvas);
            return;
        }

        int layer = canvas.saveLayer(0f, 0f, (float) w, (float) h, null, Canvas.ALL_SAVE_FLAG);
        super.onDraw(canvas);

        // 必须铺满整个 view：DST_IN 不会碰没画到的像素
        maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        maskPaint.setShader(new BitmapShader(maskBitmap(w, h),
                Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        canvas.drawRect(0f, 0f, (float) w, (float) h, maskPaint);

        canvas.restoreToCount(layer);
    }

    /** 把「形状 + 羽化」画成一张白色遮罩位图，白色=保留，透明=丢掉。 */
    Bitmap maskBitmap(int w, int h) {
        int key = ((shape * 31 + cornerPx) * 131 + featherPercent);
        if (mask != null && maskW == w && maskH == h && maskKey == key) {
            return mask;
        }

        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        rect.set(0f, 0f, (float) w, (float) h);

        // 1) 形状：白色实心
        shapePaint.setXfermode(null);
        shapePaint.setShader(null);
        shapePaint.setStyle(Paint.Style.FILL);
        shapePaint.setColor(0xFFFFFFFF);
        if (isRound()) {
            c.drawOval(rect, shapePaint);
        } else if (shape == ImageSettings.SHAPE_ROUND) {
            float r = cornerRadiusPx(w, h);
            c.drawRoundRect(rect, r, r, shapePaint);
        } else {
            c.drawRect(rect, shapePaint);
        }

        // 2) 羽化：在遮罩自己身上把边缘渐变掉，同样是铺满整张位图的 DST_IN
        float f = Math.max(0f, Math.min(1f, featherPercent / 100f));
        if (f > 0f) {
            shapePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
            if (isRound()) {
                drawRoundFeather(c, w, h, f);
            } else {
                drawEdgeFeather(c, w, h, f);
            }
        }

        mask = bmp;
        maskW = w;
        maskH = h;
        maskKey = key;
        return mask;
    }

    /**
     * 圆 / 椭圆：一条从中心往外的径向渐变。
     *
     * 椭圆要用 {@link Matrix} 把圆拉成椭圆（{@code setLocalMatrix}），不能拿
     * {@code canvas.scale} 去缩——那样连"画到哪块区域"也一起缩了，覆盖范围很容易算错，
     * 之前就是那么把图片从中间截断的。这里始终 drawRect 铺满整张位图，只让渐变变形。
     */
    private void drawRoundFeather(Canvas c, int w, int h, float f) {
        float min = Math.min(w, h);
        float[] scale = roundFeatherScale(w, h);
        RadialGradient g = new RadialGradient(w / 2f, h / 2f, Math.max(1f, min / 2f),
                new int[]{0xFFFFFFFF, 0xFFFFFFFF, 0x00FFFFFF},
                new float[]{0f, Math.max(0.001f, 1f - f), 1f}, Shader.TileMode.CLAMP);
        Matrix m = new Matrix();
        m.setScale(scale[0], scale[1], w / 2f, h / 2f);
        g.setLocalMatrix(m);
        shapePaint.setShader(g);
        c.drawRect(rect, shapePaint);
    }

    /**
     * 椭圆羽化时「把圆拉成椭圆」的缩放系数（shader 空间 -> 视图空间）。
     *
     * 渐变的半径是短边的一半，乘上这个系数之后必须正好等于椭圆的半轴
     * (w/2, h/2)，否则遮罩和椭圆对不上——方向搞反过一次，结果图片被从中间截断。
     */
    float[] roundFeatherScale(int w, int h) {
        float min = Math.min(w, h);
        return new float[]{w / min, h / min};
    }

    /** 矩形 / 圆角：四条边各一条线性渐变，DST_IN 会把它们相乘，四条边淡出宽度一致。 */
    private void drawEdgeFeather(Canvas c, int w, int h, float f) {
        float band = Math.max(1f, Math.min(w, h) / 2f * f);
        fadeEdge(c, 0f, 0f, band, 0f, w, h);
        fadeEdge(c, (float) w, 0f, (float) w - band, 0f, w, h);
        fadeEdge(c, 0f, 0f, 0f, band, w, h);
        fadeEdge(c, 0f, (float) h, 0f, (float) h - band, w, h);
    }

    /** 从 (x0,y0) 全透明渐变到 (x1,y1) 全不透明，铺满整张位图。 */
    private void fadeEdge(Canvas c, float x0, float y0, float x1, float y1, int w, int h) {
        shapePaint.setShader(new LinearGradient(x0, y0, x1, y1,
                new int[]{0x00000000, 0xFF000000},
                new float[]{0f, 1f}, Shader.TileMode.CLAMP));
        c.drawRect(0f, 0f, (float) w, (float) h, shapePaint);
    }
}
