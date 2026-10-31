package com.bai.bookshelf.util;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.ui.widget.BookCoverDrawable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import java.io.File;

/**
 * 封面加载统一入口（v4.33 重构）。
 *
 * <p>此前「无封面用布面首字占位、有封面走 Glide 淡入、本地路径 vs 网络 URL」
 * 这套判断在书架列表 / 网格 / 统计 / 年报 / 详情 / 编辑页各写了一遍，
 * 任何规则调整都要改六处。统一收在这里。
 */
public final class CoverLoader {

    private CoverLoader() {
    }

    public static Drawable placeholder(Context ctx, Book book) {
        return new BookCoverDrawable(ctx, book.title);
    }

    /** 无封面：直接画布面首字封面 */
    public static void showPlaceholder(ImageView iv, Book book) {
        Glide.with(iv).clear(iv);
        // 复用到别的书时要把「完整显示」的纸色底和缩放模式还原
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(android.graphics.Color.TRANSPARENT);
        iv.setImageDrawable(placeholder(iv.getContext(), book));
    }

    /** 本地封面的宽高比判断结果缓存（同一个文件只读一次头） */
    private static final java.util.Map<String, Boolean> RATIO_CACHE =
            java.util.Collections.synchronizedMap(new java.util.HashMap<>());

    /** 有封面走 Glide（180ms 淡入），失败回落到布面封面 */
    public static void load(ImageView iv, Book book) {
        if (book.coverUrl == null || book.coverUrl.isEmpty()) {
            showPlaceholder(iv, book);
            return;
        }
        Object model = book.coverUrl.startsWith("http") ? book.coverUrl : new File(book.coverUrl);
        applyScaleType(iv, book.coverUrl);
        Glide.with(iv)
                .load(model)
                .placeholder(placeholder(iv.getContext(), book))
                .error(placeholder(iv.getContext(), book))
                .transition(DrawableTransitionOptions.withCrossFade(180))
                .into(iv);
    }

    /**
     * 封面裁切策略（v5.8.6）。
     *
     * <p>扫描件的白边经常吃掉书名：3:4 的格子 {@code centerCrop} 会把左右裁掉。
     * 本地文件先只读图片头拿宽高比（不解码像素），偏离 3:4 超过 9% 的改成
     * {@code fitCenter} + 纸色底，宁可留白也不切字；网络图尺寸未知，维持原样。
     */
    private static void applyScaleType(ImageView iv, String url) {
        boolean fit = shouldFit(url);
        iv.setScaleType(fit ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER_CROP);
        iv.setBackgroundColor(fit
                ? com.bai.bookshelf.util.ThemeManager.resolveColor(
                        iv.getContext(), com.bai.bookshelf.R.attr.surface)
                : android.graphics.Color.TRANSPARENT);
    }

    private static boolean shouldFit(String url) {
        if (url == null || url.startsWith("http")) {
            return false;
        }
        Boolean cached = RATIO_CACHE.get(url);
        if (cached != null) {
            return cached;
        }
        boolean fit = false;
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(url, o);
            if (o.outWidth > 0 && o.outHeight > 0) {
                double ratio = (double) o.outWidth / o.outHeight;
                // 3:4 = 0.75；带白边的扫描封面通常 0.62~0.68 或 0.85+
                fit = Math.abs(ratio - 0.75) > 0.09;
            }
        } catch (Exception ignored) {
            // 读失败按普通封面处理
        }
        RATIO_CACHE.put(url, fit);
        return fit;
    }

    /** 详情 hero 的模糊底：缩到极小再放大作为模糊近似（不引入额外依赖） */
    public static void loadBlurred(ImageView iv, Book book) {
        if (book.coverUrl == null || book.coverUrl.isEmpty()) {
            iv.setImageDrawable(placeholder(iv.getContext(), book));
            return;
        }
        Object model = book.coverUrl.startsWith("http") ? book.coverUrl : new File(book.coverUrl);
        Glide.with(iv)
                .load(model)
                .override(28, 36)
                .centerCrop()
                .into(iv);
    }
}
