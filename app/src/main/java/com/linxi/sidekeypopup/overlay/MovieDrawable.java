package com.linxi.sidekeypopup.overlay;

import com.linxi.sidekeypopup.R;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Movie;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;

/**
 * Android 9 以下的 GIF 播放兜底方案（用已废弃但依然可用的 {@link Movie}）。
 * Android 9+ 走系统自带的 AnimatedImageDrawable，不需要这个类。
 */
public class MovieDrawable extends Drawable implements Animatable, Runnable {

    private final Movie movie;
    private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final long start = SystemClock.uptimeMillis();
    private boolean running;

    public MovieDrawable(Movie movie) {
        this.movie = movie;
    }

    @Override
    public void draw(Canvas canvas) {
        if (movie == null) {
            return;
        }
        Rect b = getBounds();
        if (b.isEmpty()) {
            return;
        }
        int duration = movie.duration();
        int time = duration > 0 ? (int) ((SystemClock.uptimeMillis() - start) % duration) : 0;
        movie.setTime(time);

        int mw = movie.width();
        int mh = movie.height();
        if (mw <= 0 || mh <= 0) {
            return;
        }
        int save = canvas.save();
        canvas.translate(b.left, b.top);
        canvas.scale((float) b.width() / mw, (float) b.height() / mh);
        movie.draw(canvas, 0f, 0f);
        canvas.restoreToCount(save);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return movie == null ? -1 : movie.width();
    }

    @Override
    public int getIntrinsicHeight() {
        return movie == null ? -1 : movie.height();
    }

    @Override
    public void run() {
        if (!running) {
            return;
        }
        invalidateSelf();
        scheduleSelf(this, SystemClock.uptimeMillis() + 33L);
    }

    @Override
    public void start() {
        if (running) {
            return;
        }
        running = true;
        scheduleSelf(this, SystemClock.uptimeMillis());
    }

    @Override
    public void stop() {
        running = false;
        unscheduleSelf(this);
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
