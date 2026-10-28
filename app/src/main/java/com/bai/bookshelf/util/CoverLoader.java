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
        iv.setImageDrawable(placeholder(iv.getContext(), book));
    }

    /** 有封面走 Glide（180ms 淡入），失败回落到布面封面 */
    public static void load(ImageView iv, Book book) {
        if (book.coverUrl == null || book.coverUrl.isEmpty()) {
            showPlaceholder(iv, book);
            return;
        }
        Object model = book.coverUrl.startsWith("http") ? book.coverUrl : new File(book.coverUrl);
        Glide.with(iv)
                .load(model)
                .placeholder(placeholder(iv.getContext(), book))
                .error(placeholder(iv.getContext(), book))
                .transition(DrawableTransitionOptions.withCrossFade(180))
                .into(iv);
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
