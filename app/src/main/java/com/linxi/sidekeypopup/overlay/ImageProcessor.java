package com.linxi.sidekeypopup.overlay;

import com.linxi.sidekeypopup.R;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Movie;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.Size;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * 图片解码。静态图、动图（GIF / 动图 WebP）都在这里统一成 Drawable。
 *
 * Android 9+ 用 {@link ImageDecoder#decodeDrawable} 拿到系统的
 * AnimatedImageDrawable，GIF 自动播放；9 以下用 {@link Movie} 兜底。
 */
public final class ImageProcessor {

    private ImageProcessor() {
    }

    /** 解码成 Drawable（动图会自己动）。失败返回 null。 */
    public static Drawable loadDrawable(Context ctx, File f, int maxW, int maxH) {
        if (f == null || !f.exists() || f.length() == 0L) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(f);
                final int limitW = Math.max(1, maxW);
                final int limitH = Math.max(1, maxH);
                Drawable d = ImageDecoder.decodeDrawable(src, new ImageDecoder.OnHeaderDecodedListener() {
                    @Override
                    public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info,
                                                ImageDecoder.Source source) {
                        Size size = info.getSize();
                        int w = size.getWidth();
                        int h = size.getHeight();
                        if (w <= 0 || h <= 0) {
                            return;
                        }
                        float scale = Math.min(1f, Math.min((float) limitW / w, (float) limitH / h));
                        if (scale < 1f) {
                            decoder.setTargetSampleSize(Math.max(1, Math.round(1f / scale)));
                        }
                    }
                });
                if (d instanceof AnimatedImageDrawable) {
                    ((AnimatedImageDrawable) d).setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                }
                return d;
            } catch (Throwable ignored) {
                // 落到下面的兜底分支
            }
        }

        Drawable movie = loadMovie(f);
        if (movie != null) {
            return movie;
        }
        Bitmap bm = decode(f, maxW, maxH);
        return bm == null ? null : new BitmapDrawable(ctx.getResources(), bm);
    }

    /** 只有真的是动图才返回 MovieDrawable，静态图返回 null。 */
    private static Drawable loadMovie(File f) {
        InputStream in = null;
        try {
            in = new BufferedInputStream(new FileInputStream(f), 64 * 1024);
            Movie movie = Movie.decodeStream(in);
            if (movie == null || movie.width() <= 0 || movie.height() <= 0) {
                return null;
            }
            if (movie.duration() <= 0) {
                return null; // 静态图，交给 BitmapFactory
            }
            return new MovieDrawable(movie);
        } catch (Throwable t) {
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    /** 让动图开始播放（必须在 UI 线程调用）。 */
    public static void startIfAnimated(Drawable d) {
        if (d instanceof AnimatedImageDrawable) {
            AnimatedImageDrawable a = (AnimatedImageDrawable) d;
            if (!a.isRunning()) {
                try {
                    a.start();
                } catch (Throwable ignored) {
                }
            }
        } else if (d instanceof MovieDrawable) {
            if (!((MovieDrawable) d).isRunning()) {
                ((MovieDrawable) d).start();
            }
        }
    }

    /** 按目标尺寸采样解码静态图。 */
    public static Bitmap decode(File f, int maxW, int maxH) {
        if (f == null || !f.exists() || f.length() == 0L) {
            return null;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try {
                ImageDecoder.Source src = ImageDecoder.createSource(f);
                final int limitW = Math.max(1, maxW);
                final int limitH = Math.max(1, maxH);
                return ImageDecoder.decodeBitmap(src, new ImageDecoder.OnHeaderDecodedListener() {
                    @Override
                    public void onHeaderDecoded(ImageDecoder decoder, ImageDecoder.ImageInfo info,
                                                ImageDecoder.Source source) {
                        Size size = info.getSize();
                        int w = size.getWidth();
                        int h = size.getHeight();
                        if (w <= 0 || h <= 0) {
                            return;
                        }
                        float scale = Math.min(1f, Math.min((float) limitW / w, (float) limitH / h));
                        if (scale < 1f) {
                            decoder.setTargetSampleSize(Math.max(1, Math.round(1f / scale)));
                        }
                        decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    }
                });
            } catch (Throwable ignored) {
                // 落到下面的 BitmapFactory 分支
            }
        }

        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = new FileInputStream(f);
            try {
                BitmapFactory.decodeStream(in, null, bounds);
            } finally {
                closeQuietly(in);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }

            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= maxW && bounds.outHeight / (sample * 2) >= maxH) {
                sample *= 2;
            }

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            in = new FileInputStream(f);
            try {
                return BitmapFactory.decodeStream(in, null, opts);
            } finally {
                closeQuietly(in);
            }
        } catch (Throwable t) {
            return null;
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
    }
}
