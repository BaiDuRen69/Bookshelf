package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;

/**
 * 阅读进度的页码换算（v5.8.19）：进度存的是 0-100 的百分比，但人记书是「读到第几页」，
 * 所以填了总页数时两边要能来回换算，并且换算口径只有一处（详情页、统计、抽书都用它）。
 */
public final class ReadingProgress {

    private ReadingProgress() {
    }

    /** 第几页 → 百分比（四舍五入并夹到 0-100）；没填总页数返回 0 */
    public static int percentForPage(int page, int totalPages) {
        if (totalPages <= 0) {
            return 0;
        }
        int percent = (int) Math.round(page * 100.0 / totalPages);
        return Math.max(0, Math.min(100, percent));
    }

    /** 百分比 → 第几页（四舍五入，不超过总页数）；没填总页数返回 0 */
    public static int pageForPercent(int percent, int totalPages) {
        if (totalPages <= 0) {
            return 0;
        }
        int p = Math.max(0, Math.min(100, percent));
        return (int) Math.round(p * totalPages / 100.0);
    }

    /** 还剩几页没读；没填总页数的书返回 -1（表示「算不进页数口径」） */
    public static int pagesLeft(Book book) {
        if (book == null || book.pages <= 0) {
            return -1;
        }
        int percent = Math.max(0, Math.min(100, book.readingProgress));
        int read = (int) Math.round(percent * book.pages / 100.0);
        return Math.max(0, book.pages - read);
    }

    /** 进度是否已到 100% */
    public static boolean isComplete(Book book) {
        return book != null && book.readingProgress >= 100;
    }
}
