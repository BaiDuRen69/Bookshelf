package com.bai.bookshelf.util;

import android.content.Context;
import android.graphics.Color;

import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;

/**
 * 书架语义色（v5.8.14）：状态条与阅读进度线共用一个色板，
 * 借出黄铜、遗失/弃读砖红、其余主色，避免封面上出现两套不相干的强调色。
 */
public final class ShelfAccent {

    private ShelfAccent() {
    }

    /** 书脊底部状态条：借出 / 遗失 / 在读 / 弃读 */
    public static int statusColor(Context ctx, Book book) {
        if (book.stockStatus == Book.STOCK_LOST) {
            return ContextCompat.getColor(ctx, R.color.danger);
        }
        if (book.stockStatus == Book.STOCK_LENT) {
            return ContextCompat.getColor(ctx, R.color.badge_borrowed);
        }
        if (book.readStatus == Book.READ_DROPPED) {
            return ContextCompat.getColor(ctx, R.color.danger);
        }
        return ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
    }

    /** 进度胶囊底：在任意封面上都能压住白字 */
    public static int percentBackground(Context ctx, Book book) {
        return Color.argb(204, Color.red(statusColor(ctx, book)),
                Color.green(statusColor(ctx, book)), Color.blue(statusColor(ctx, book)));
    }
}
