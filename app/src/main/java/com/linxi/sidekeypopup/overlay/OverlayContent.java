package com.linxi.sidekeypopup.overlay;

import com.linxi.sidekeypopup.R;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/**
 * 按设置把图片渲染成一个内容视图。
 * 悬浮窗和设置页预览共用同一套渲染逻辑，保证所见即所得。
 */
public final class OverlayContent {

    private OverlayContent() {
    }

    /**
     * @param d       已经解码好的图片（可能是动图）
     * @param maxW    图片可用宽度上限（已含大小设置）
     * @param maxH    图片可用高度上限
     * @param uiScale 预览时小于 1，用来等比缩小圆角等尺寸
     */
    public static View create(Context ctx, ImageSettings s, Drawable d, int maxW, int maxH, float uiScale) {
        float density = ctx.getResources().getDisplayMetrics().density;
        if (uiScale <= 0f) {
            uiScale = 1f;
        }

        LinearLayout container = new LinearLayout(ctx);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setGravity(Gravity.CENTER_HORIZONTAL);
        container.setClipChildren(false);
        container.setClipToPadding(false);

        if (d != null) {
            FrameLayout imageBox = new FrameLayout(ctx);
            imageBox.setClipChildren(false);
            imageBox.setClipToPadding(false);

            MaskedImageView iv = new MaskedImageView(ctx);
            if (s.shape == ImageSettings.SHAPE_CIRCLE) {
                // 圆形要先把图片中心裁成正方形，否则得到的是椭圆
                int side = Math.max(1, Math.min(maxW, maxH));
                iv.setLayoutParams(new FrameLayout.LayoutParams(side, side, Gravity.CENTER));
                iv.setScaleType(MaskedImageView.ScaleType.CENTER_CROP);
            } else {
                FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
                ilp.gravity = Gravity.CENTER;
                iv.setLayoutParams(ilp);
                iv.setAdjustViewBounds(true);
                iv.setScaleType(MaskedImageView.ScaleType.FIT_CENTER);
                if (s.swapsDimensions()) {
                    iv.setMaxWidth(maxH);
                    iv.setMaxHeight(maxW);
                } else {
                    iv.setMaxWidth(maxW);
                    iv.setMaxHeight(maxH);
                }
            }
            iv.setImageDrawable(d);
            iv.setRotation(s.rotation);
            // 圆角半径是 dp，预览里必须跟着 uiScale 一起缩，否则预览和真机对不上
            iv.apply(s, density * uiScale);
            if (d instanceof MovieDrawable) {
                // Movie 画在硬件加速画布上有兼容问题，给它一个软件图层
                iv.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
            }
            imageBox.addView(iv);
            ImageProcessor.startIfAnimated(d);

            container.addView(imageBox);
        }

        float alpha = Math.max(0.05f, Math.min(1f, s.alphaPercent / 100f));
        container.setAlpha(alpha);
        return container;
    }
}
