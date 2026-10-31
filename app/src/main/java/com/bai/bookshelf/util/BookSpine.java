package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;

/**
 * 书脊厚度（v5.8.14）：用厚度表达「有几册 / 是不是套装」，
 * 网格和列表共用同一套规则，扫一眼就能看出这套书不止一本。
 */
public final class BookSpine {

    public static final int SINGLE_DP = 6;
    public static final int MULTI_DP = 8;
    public static final int SET_DP = 10;

    private BookSpine() {
    }

    /** 套装（有系列名）最厚；其次按拥有册数：1 册 6dp，2–3 册 8dp，4 册以上 10dp */
    public static int widthDp(Book book) {
        if (book == null) {
            return SINGLE_DP;
        }
        if (!BookSeries.name(book).isEmpty()) {
            return SET_DP;
        }
        int copies = Math.max(1, book.quantity);
        if (copies >= 4) {
            return SET_DP;
        }
        return copies >= 2 ? MULTI_DP : SINGLE_DP;
    }

    /** 真实封面上的书脊只做暗边提示，比生成封面淡一些 */
    public static float alpha(Book book) {
        return book != null && book.coverUrl != null && !book.coverUrl.isEmpty() ? 0.62f : 1f;
    }
}
