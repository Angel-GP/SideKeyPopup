package com.linxi.sidekeypopup.overlay;

import com.linxi.sidekeypopup.MainActivity;
import com.linxi.sidekeypopup.R;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.slider.Slider;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 设置页（Material 3）：对悬浮图做「预修改」。
 * 顶部是按屏幕比例缩放的实时预览，改任何一项都立刻重建预览；点保存后悬浮图同步更新。
 * 入口：长按桌面图标 -> 设置，或通知栏的「设置」。
 */
public class ImageSettingsActivity extends AppCompatActivity {

    private ImageSettings s;
    private FrameLayout previewBox;
    private Drawable previewDrawable;
    private int previewW;
    private int previewH;
    private float uiScale = 1f;
    private long loadedStamp = Long.MIN_VALUE;

    /** 形状选项组，改圆角半径时要反向选中它。 */
    private ChipGroup shapeChips;

    private int brand;
    private int ink;
    private int inkSub;
    private int chipBg;

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private interface OnInt {
        void onInt(int value);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        brand = getColor(R.color.brand);
        ink = getColor(R.color.ink);
        inkSub = getColor(R.color.ink_sub);
        chipBg = getColor(R.color.chip_bg);
        s = ImageSettings.load(this);
        setContentView(buildRoot());
    }

    @Override
    protected void onResume() {
        super.onResume();
        File f = Prefs.imageFile(this);
        long stamp = f.exists() ? f.lastModified() : -1L;
        if (stamp != loadedStamp) {
            loadedStamp = stamp;
            loadPreviewDrawable();
        }
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        previewDrawable = null;
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 骨架

    private View buildRoot() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int pad = dp(16);
        int boxW = Math.max(dp(140), dm.widthPixels - 2 * pad - dp(32));
        previewW = boxW;
        previewH = Math.min(Math.round(boxW * 1.3f),
                Math.min(dp(360), Math.round(boxW * (float) dm.heightPixels / dm.widthPixels)));
        uiScale = (float) boxW / dm.widthPixels;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(pad, dp(4), pad, dp(24));
        content.addView(previewCard());
        content.addView(sectionLabel("图片"));
        content.addView(imageCard());
        content.addView(sectionLabel("形状与边界"));
        content.addView(shapeCard());

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(R.color.page_bg));

        MaterialToolbar bar = new MaterialToolbar(this);
        bar.setTitle(R.string.settings_title);
        bar.setTitleTextColor(ink);
        bar.setNavigationIcon(R.drawable.ic_back);
        bar.setBackgroundColor(getColor(R.color.white));
        bar.setElevation(dp(3));
        bar.setNavigationOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
        root.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));

        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(bottomBar());
        return root;
    }

    private View bottomBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setBackgroundColor(getColor(R.color.white));
        bar.setElevation(dp(8));
        bar.setPadding(dp(16), dp(10), dp(16), dp(14));

        MaterialButton save = new MaterialButton(this);
        save.setText("保存并应用");
        save.setTextSize(16);
        save.setAllCaps(false);
        save.setCornerRadius(dp(14));
        save.setBackgroundTintList(ColorStateList.valueOf(brand));
        save.setTextColor(getColor(R.color.white));
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                s.save(ImageSettingsActivity.this);
                // 正在悬浮就刷新；没在悬浮就直接弹出来，让用户马上看到效果
                if (OverlayService.isShowing()) {
                    OverlayService.refresh(ImageSettingsActivity.this);
                } else if (Prefs.hasImage(ImageSettingsActivity.this)) {
                    OverlayService.show(ImageSettingsActivity.this);
                }
                Toast.makeText(ImageSettingsActivity.this, "已应用", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        bar.addView(save, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return bar;
    }

    // ------------------------------------------------------------------ 卡片

    private View previewCard() {
        Card c = newCard();
        c.body.addView(rowLabel("实时预览", "与真机等比缩放"));

        previewBox = new FrameLayout(this);
        previewBox.setBackground(new CheckerDrawable(
                Math.max(4, dp(8)), 0xFF3C4148, 0xFF2A2E34,
                dp(14), getColor(R.color.line), Math.max(1f, dp(1))));
        previewBox.setClipChildren(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(previewW, previewH);
        lp.topMargin = dp(12);
        previewBox.setLayoutParams(lp);
        c.body.addView(previewBox);
        return c.view;
    }

    private View imageCard() {
        Card c = newCard();
        LinearLayout card = c.body;
        card.addView(slider("大小", 30, 100, s.scalePercent, "%", new OnInt() {
            @Override
            public void onInt(int v) {
                s.scalePercent = v;
                renderPreview();
            }
        }));
        card.addView(slider("不透明度", 20, 100, s.alphaPercent, "%", new OnInt() {
            @Override
            public void onInt(int v) {
                s.alphaPercent = v;
                renderPreview();
            }
        }));
        card.addView(slider("旋转角度", -180, 180, s.rotation, "°", new OnInt() {
            @Override
            public void onInt(int v) {
                s.rotation = v;
                renderPreview();
            }
        }));

        MaterialButton change = new MaterialButton(this);
        change.setText("更换图片");
        change.setTextSize(15);
        change.setAllCaps(false);
        change.setCornerRadius(dp(12));
        change.setBackgroundTintList(ColorStateList.valueOf(getColor(R.color.brand_soft)));
        change.setTextColor(brand);
        change.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // CLEAR_TOP：复用已经在栈里的主界面，顺便把本页弹掉，
                // 免得选完图之后返回又回到这个设置页。
                startActivity(new Intent(ImageSettingsActivity.this, MainActivity.class)
                        .setAction(MainActivity.ACTION_PICK_IMAGE)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            }
        });
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(16);
        change.setLayoutParams(clp);
        card.addView(change);
        return c.view;
    }

    private View shapeCard() {
        Card c = newCard();
        LinearLayout card = c.body;

        LinearLayout shapeBox = labeledBox("形状");
        shapeChips = newChipGroup(new String[]{"原图", "圆角", "圆形", "椭圆"}, s.shape, new OnInt() {
            @Override
            public void onInt(int v) {
                s.shape = v;
                renderPreview();
            }
        });
        shapeBox.addView(shapeChips);
        card.addView(shapeBox);

        card.addView(slider("圆角半径", 0, 80, s.cornerDp, "dp", new OnInt() {
            @Override
            public void onInt(int v) {
                s.cornerDp = v;
                // 圆角只在「圆角」形状下看得见，拖半径时顺手把形状切过去，
                // 免得用户拖了半天以为坏了
                if (v > 0 && s.shape != ImageSettings.SHAPE_ROUND) {
                    s.shape = ImageSettings.SHAPE_ROUND;
                    checkShape(ImageSettings.SHAPE_ROUND);
                }
                renderPreview();
            }
        }));
        card.addView(slider("边缘羽化", 0, 50, s.featherPercent, "%", new OnInt() {
            @Override
            public void onInt(int v) {
                s.featherPercent = v;
                renderPreview();
            }
        }));
        card.addView(hint("羽化会把图片四周柔和地淡出去，做出模糊边界的效果；"
                + "百分比是淡出宽度占图片短边一半的比例"));
        return c.view;
    }

    /** 反向选中形状 chip（会触发它的回调，但值相同，没有副作用）。 */
    private void checkShape(int shape) {
        if (shapeChips == null || shape < 0 || shape >= shapeChips.getChildCount()) {
            return;
        }
        View v = shapeChips.getChildAt(shape);
        if (v instanceof Chip) {
            ((Chip) v).setChecked(true);
        }
    }

    // ------------------------------------------------------------------ 预览

    private void loadPreviewDrawable() {
        final File f = Prefs.imageFile(this);
        final int w = previewW;
        final int h = previewH;
        io.execute(new Runnable() {
            @Override
            public void run() {
                final Drawable d;
                try {
                    d = ImageProcessor.loadDrawable(ImageSettingsActivity.this, f, w, h);
                } catch (Throwable t) {
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            previewDrawable = null;
                            renderPreview();
                        }
                    });
                    return;
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        previewDrawable = d;
                        renderPreview();
                    }
                });
            }
        });
    }

    /** 同步重建预览：不做异步位图处理，改哪一项都立刻能看到。 */
    private void renderPreview() {
        if (previewBox == null) {
            return;
        }
        previewBox.removeAllViews();
        if (previewDrawable == null) {
            TextView tv = new TextView(this);
            tv.setText(Prefs.hasImage(this) ? "图片读取失败，试试重新选一张" : "还没有选择图片");
            tv.setTextColor(0xFF9CA3AF);
            tv.setTextSize(14);
            previewBox.addView(tv, new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.CENTER));
            return;
        }
        int maxW = Math.max(1, previewW * s.scalePercent / 100);
        int maxH = Math.max(1, previewH * s.scalePercent / 100);
        View content = OverlayContent.create(this, s, previewDrawable, maxW, maxH, uiScale);
        previewBox.addView(content, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER));
    }

    // ------------------------------------------------------------------ 控件

    /** 一张卡片：外层 MaterialCardView + 里面真正放控件的容器。 */
    private static final class Card {
        MaterialCardView view;
        LinearLayout body;
    }

    private Card newCard() {
        Card c = new Card();
        c.view = new MaterialCardView(this);
        c.view.setRadius(dp(18));
        c.view.setCardElevation(0f);
        c.view.setCardBackgroundColor(getColor(R.color.white));
        c.view.setStrokeWidth(dp(1));
        c.view.setStrokeColor(getColor(R.color.line));

        c.body = new LinearLayout(this);
        c.body.setOrientation(LinearLayout.VERTICAL);
        c.body.setPadding(dp(16), dp(14), dp(16), dp(16));
        c.view.addView(c.body);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        c.view.setLayoutParams(lp);
        return c;
    }

    private TextView sectionLabel(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(13);
        tv.setTextColor(inkSub);
        tv.setPadding(dp(4), dp(20), 0, 0);
        return tv;
    }

    private View rowLabel(String text, String sub) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTextColor(ink);
        row.addView(tv);

        if (sub != null) {
            TextView s2 = new TextView(this);
            s2.setText(sub);
            s2.setTextSize(12);
            s2.setTextColor(inkSub);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.leftMargin = dp(8);
            s2.setLayoutParams(lp);
            row.addView(s2);
        }
        return row;
    }

    private TextView hint(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12);
        tv.setTextColor(inkSub);
        tv.setPadding(0, dp(10), 0, 0);
        return tv;
    }

    private View slider(String label, final int min, final int max, int value,
                        final String suffix, final OnInt cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(14), 0, 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(15);
        title.setTextColor(ink);
        head.addView(title, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        final TextView valueView = new TextView(this);
        valueView.setText(value + suffix);
        valueView.setTextSize(14);
        valueView.setTextColor(brand);
        head.addView(valueView);
        box.addView(head);

        Slider bar = new Slider(this);
        bar.setValueFrom(min);
        bar.setValueTo(max);
        bar.setStepSize(1f);
        bar.setValue(Math.max(min, Math.min(max, value)));
        bar.addOnChangeListener(new Slider.OnChangeListener() {
            @Override
            public void onValueChange(Slider slider, float v, boolean fromUser) {
                int iv = Math.round(v);
                valueView.setText(iv + suffix);
                cb.onInt(iv);
            }
        });
        box.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return box;
    }

    /** 带标题的一行控件容器。 */
    private LinearLayout labeledBox(String label) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(14), 0, 0);

        TextView title = new TextView(this);
        title.setText(label);
        title.setTextSize(15);
        title.setTextColor(ink);
        box.addView(title);
        return box;
    }

    private ChipGroup newChipGroup(String[] options, int selected, final OnInt cb) {
        ChipGroup group = new ChipGroup(this);
        group.setSingleSelection(true);
        group.setSelectionRequired(true);
        group.setChipSpacingHorizontal(dp(8));
        group.setChipSpacingVertical(dp(6));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        glp.topMargin = dp(8);
        group.setLayoutParams(glp);

        for (int i = 0; i < options.length; i++) {
            final int index = i;
            Chip chip = new Chip(this);
            chip.setText(options[i]);
            chip.setTextSize(13f);
            chip.setCheckable(true);
            chip.setChecked(i == selected);
            chip.setCheckedIconVisible(false);
            chip.setChipStrokeWidth(0f);
            chip.setChipBackgroundColor(chipBackground());
            chip.setTextColor(chipText());
            chip.setEnsureMinTouchTargetSize(false);
            chip.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (isChecked) {
                        cb.onInt(index);
                    }
                }
            });
            group.addView(chip);
        }
        return group;
    }

    private ColorStateList chipBackground() {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{brand, chipBg});
    }

    private ColorStateList chipText() {
        return new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_checked}, new int[]{}},
                new int[]{getColor(R.color.white), ink});
    }


    /**
     * 棋盘格背景。预览底下是透明的，用格子才看得出来哪块是图片、哪块已经透明了。
     * 圆角自己画，不依赖 {@code setClipToOutline}（那东西只在硬件加速下生效）。
     */
    private static final class CheckerDrawable extends Drawable {

        private final Paint fill = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path clip = new Path();
        private final RectF rect = new RectF();
        private final float radius;

        CheckerDrawable(int cell, int light, int dark, float radius, int borderColor, float borderWidth) {
            this.radius = radius;

            Bitmap tile = Bitmap.createBitmap(cell * 2, cell * 2, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(tile);
            Paint p = new Paint();
            p.setColor(light);
            c.drawRect(0f, 0f, cell * 2f, cell * 2f, p);
            p.setColor(dark);
            c.drawRect(0f, 0f, cell, cell, p);
            c.drawRect(cell, cell, cell * 2f, cell * 2f, p);
            fill.setShader(new BitmapShader(tile, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT));

            border.setStyle(Paint.Style.STROKE);
            border.setColor(borderColor);
            border.setStrokeWidth(borderWidth);
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            rect.set(b.left, b.top, b.right, b.bottom);
            clip.reset();
            clip.addRoundRect(rect, radius, radius, Path.Direction.CW);

            int save = canvas.save();
            canvas.clipPath(clip);
            canvas.drawRect(rect, fill);
            canvas.restoreToCount(save);

            canvas.drawPath(clip, border);
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            fill.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
