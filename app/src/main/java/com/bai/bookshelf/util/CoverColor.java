package com.bai.bookshelf.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;

import com.bai.bookshelf.data.entity.Book;
import com.bumptech.glide.Glide;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * 封面主色（v5.8.14）：详情 hero 晕染、阅读进度条、分享卡各取一本书的主色，
 * 让每本书的页面有自己的颜色情绪，而不是统一套主题色。
 *
 * <p>不引入 Palette 依赖：封面缩到 ~48px 采样，按「色相 12 档 × 明度 4 档」分桶，
 * 取占比最高的有色桶均值；无封面时按书名从六种布色里取（与占位封面一致）。
 * 结果按 封面地址 + 主题明暗 缓存在内存里，重复进详情不再解码。
 */
public final class CoverColor {

    /** 与 colors.xml 的 book_cloth_* / BookCoverDrawable 一致 */
    private static final int[] CLOTH = {
            0xFF2E6E5C, 0xFF3A5BA0, 0xFF9E4A3A,
            0xFFA9762E, 0xFF6B5A7E, 0xFF33383A
    };

    private static final LruCache<String, Integer> CACHE = new LruCache<>(64);

    private CoverColor() {
    }

    /** 已算过的颜色（未算过返回 0，调用方可先用主题色兜底） */
    public static int cached(Context ctx, Book book) {
        Integer hit = CACHE.get(key(ctx, book));
        return hit == null ? 0 : hit;
    }

    /**
     * 取封面主色（阻塞，必须在后台线程调用）。
     * 返回已按当前主题明暗校正过的颜色，保证能压在纸面 / 炭黑上。
     */
    @WorkerThread
    public static int of(Context ctx, Book book) {
        String key = key(ctx, book);
        Integer hit = CACHE.get(key);
        if (hit != null) {
            return hit;
        }
        int raw = 0;
        String url = book.coverUrl;
        if (url != null && !url.isEmpty()) {
            Bitmap bitmap = decodeSample(ctx, url);
            if (bitmap != null) {
                raw = dominant(samplePixels(bitmap));
                bitmap.recycle();
            }
        }
        if (raw == 0) {
            raw = clothFor(book.title);
        }
        int color = adjust(raw, ThemeManager.current(ctx) == ThemeManager.THEME_NIGHT);
        CACHE.put(key, color);
        return color;
    }

    /** 分享卡已有封面 Bitmap 时直接取色，不再二次解码 / 联网 */
    public static int fromBitmap(@Nullable Bitmap bitmap, @Nullable String title) {
        int raw = bitmap == null ? 0 : dominant(samplePixels(bitmap));
        return raw == 0 ? clothFor(title) : raw;
    }

    /** 浅色纸面上当强调色用：压暗到能看清，深色封面则保持原样 */
    public static int readableOnPaper(int rgb) {
        return adjust(rgb, false);
    }

    private static String key(Context ctx, Book book) {
        String src = book.coverUrl == null || book.coverUrl.isEmpty()
                ? "title:" + book.title
                : book.coverUrl;
        return (ThemeManager.current(ctx) == ThemeManager.THEME_NIGHT ? "N|" : "L|") + src;
    }

    /** 无封面时按书名选布色（与 BookCoverDrawable 的选色规则一致） */
    static int clothFor(@Nullable String title) {
        if (title == null || title.isEmpty()) {
            return CLOTH[0];
        }
        return CLOTH[(title.hashCode() & 0x7FFFFFFF) % CLOTH.length];
    }

    @Nullable
    private static Bitmap decodeSample(Context ctx, String url) {
        try {
            if (url.startsWith("http")) {
                return Glide.with(ctx.getApplicationContext()).asBitmap()
                        .load(url)
                        .submit(64, 86)
                        .get(4, TimeUnit.SECONDS);
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(url, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                return null;
            }
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 48
                    && bounds.outHeight / (sample * 2) >= 64) {
                sample *= 2;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            options.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(url, options);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static int[] samplePixels(Bitmap bitmap) {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        if (width <= 0 || height <= 0) {
            return new int[0];
        }
        int step = Math.max(1, Math.min(width, height) / 48);
        int[] pixels = new int[((width + step - 1) / step) * ((height + step - 1) / step)];
        int index = 0;
        for (int y = 0; y < height; y += step) {
            for (int x = 0; x < width; x += step) {
                pixels[index++] = bitmap.getPixel(x, y);
            }
        }
        return index == pixels.length ? pixels : Arrays.copyOf(pixels, index);
    }

    /**
     * 纯 Java 主色计算（可直接单测）：
     * 跳过透明 / 过暗 / 过亮 / 近灰的像素，按色相 12 档 × 明度 4 档分桶，
     * 取像素最多的一桶求均值；一张图几乎没有彩色时退回中性色均值。
     */
    static int dominant(int[] pixels) {
        final int bucketCount = 48;
        int[] count = new int[bucketCount];
        long[] sumR = new long[bucketCount];
        long[] sumG = new long[bucketCount];
        long[] sumB = new long[bucketCount];
        int neutralCount = 0;
        long neutralR = 0;
        long neutralG = 0;
        long neutralB = 0;

        for (int argb : pixels) {
            if ((argb >>> 24) < 128) {
                continue;
            }
            int r = (argb >> 16) & 0xFF;
            int g = (argb >> 8) & 0xFF;
            int b = argb & 0xFF;
            int max = Math.max(r, Math.max(g, b));
            int min = Math.min(r, Math.min(g, b));
            int delta = max - min;
            float saturation = max == 0 ? 0f : delta / (float) max;
            float value = max / 255f;
            if (saturation < 0.12f || value < 0.10f || value > 0.97f) {
                neutralCount++;
                neutralR += r;
                neutralG += g;
                neutralB += b;
                continue;
            }
            float hue;
            if (delta == 0) {
                hue = 0f;
            } else if (max == r) {
                hue = 60f * (((g - b) / (float) delta) % 6f);
            } else if (max == g) {
                hue = 60f * (((b - r) / (float) delta) + 2f);
            } else {
                hue = 60f * (((r - g) / (float) delta) + 4f);
            }
            if (hue < 0f) {
                hue += 360f;
            }
            int hueBucket = Math.min(11, (int) (hue / 30f));
            int valueBucket = Math.min(3, (int) (value * 4f));
            int index = hueBucket * 4 + valueBucket;
            count[index]++;
            sumR[index] += r;
            sumG[index] += g;
            sumB[index] += b;
        }

        int best = 0;
        for (int i = 1; i < bucketCount; i++) {
            if (count[i] > count[best]) {
                best = i;
            }
        }
        if (count[best] >= 2) {
            return 0xFF000000
                    | ((int) (sumR[best] / count[best]) << 16)
                    | ((int) (sumG[best] / count[best]) << 8)
                    | (int) (sumB[best] / count[best]);
        }
        if (neutralCount == 0) {
            return 0;
        }
        int nr = (int) (neutralR / neutralCount);
        int ng = (int) (neutralG / neutralCount);
        int nb = (int) (neutralB / neutralCount);
        double luma = luma(nr, ng, nb);
        // 整张图都是白底/黑边时没有可用主色，交给布色兜底
        if (luma > 0.85 || luma < 0.15) {
            return 0;
        }
        return 0xFF000000 | (nr << 16) | (ng << 8) | nb;
    }

    /** 校正到当前表面能用的亮度：浅色纸面压暗到 0.42，夜读提亮到 0.66 */
    static int adjust(int rgb, boolean night) {
        double lum = luma((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        double target = night ? 0.66 : 0.42;
        if (night && lum < target) {
            return blend(rgb, 0xFFFFFFFF, (target - lum) / Math.max(0.001, 1 - lum));
        }
        if (!night && lum > target) {
            return blend(rgb, 0xFF000000, (lum - target) / Math.max(0.001, lum));
        }
        return rgb;
    }

    private static double luma(int r, int g, int b) {
        return (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
    }

    private static int blend(int from, int to, double ratio) {
        double t = Math.max(0, Math.min(1, ratio));
        int r = (int) Math.round(((to >> 16) & 0xFF) * t + ((from >> 16) & 0xFF) * (1 - t));
        int g = (int) Math.round(((to >> 8) & 0xFF) * t + ((from >> 8) & 0xFF) * (1 - t));
        int b = (int) Math.round((to & 0xFF) * t + (from & 0xFF) * (1 - t));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }
}
