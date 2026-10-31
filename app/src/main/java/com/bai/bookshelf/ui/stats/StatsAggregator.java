package com.bai.bookshelf.ui.stats;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.util.BookFlags;

import java.util.Calendar;
import java.util.List;

/**
 * 统计页的购买数量聚合（v5.6 从 ReadingStatsActivity 抽出）：
 * 按月每日 / 按月 / 全年每日的购买本数，以及月/年汇总。
 */
public final class StatsAggregator {

    /** 月汇总：购买数、录入数、自购花费与每日分布 */
    public static final class MonthSummary {
        public int bought;
        public int entered;
        public double cost;
        public int[] daily = new int[0];
    }

    /** 年汇总：购买数、录入数、自购花费与每月分布 */
    public static final class YearSummary {
        public int bought;
        public int entered;
        public double cost;
        public int[] monthly = new int[12];
    }

    private StatsAggregator() {
    }

    public static MonthSummary monthSummary(List<Book> books, int year, int month0) {
        MonthSummary s = new MonthSummary();
        s.daily = dailyCounts(books, year, month0);
        // v3.0：createdAt 的 Calendar 只创建一次并复用，避免每本书都 new 一次
        Calendar created = Calendar.getInstance();
        for (Book book : books) {
            int[] pd = StatsText.parseYmd(book.purchaseDate);
            if (pd != null && pd[0] == year && pd[1] == month0 && !BookFlags.isBorrowSource(book)) {
                s.bought++;
                // v4.20：花费只统计自购（排除赠送 / 借阅）
                if (book.ownType == Book.OWN_BUY) {
                    s.cost += book.purchasePrice;
                }
            }
            created.setTimeInMillis(book.createdAt);
            if (BookFlags.countsAsOwned(book)
                    && created.get(Calendar.YEAR) == year
                    && created.get(Calendar.MONTH) == month0) {
                s.entered++;
            }
        }
        return s;
    }

    public static YearSummary yearSummary(List<Book> books, int year) {
        YearSummary s = new YearSummary();
        s.monthly = monthlyCounts(books, year);
        Calendar created = Calendar.getInstance();
        for (Book book : books) {
            int[] pd = StatsText.parseYmd(book.purchaseDate);
            if (pd != null && pd[0] == year && !BookFlags.isBorrowSource(book)) {
                s.bought++;
                if (book.ownType == Book.OWN_BUY) {
                    s.cost += book.purchasePrice;
                }
            }
            created.setTimeInMillis(book.createdAt);
            if (BookFlags.countsAsOwned(book) && created.get(Calendar.YEAR) == year) {
                s.entered++;
            }
        }
        return s;
    }

    /** 某年某月每天的购买数量（下标 0 = 1 日） */
    public static int[] dailyCounts(List<Book> books, int year, int month0) {
        int[] out = new int[StatsText.daysInMonth(year, month0)];
        for (Book book : books) {
            if (BookFlags.isBorrowSource(book)) {
                continue;
            }
            int[] pd = StatsText.parseYmd(book.purchaseDate);
            if (pd != null && pd[0] == year && pd[1] == month0
                    && pd[2] >= 1 && pd[2] <= out.length) {
                out[pd[2] - 1]++;
            }
        }
        return out;
    }

    /** 某年每月购买数量（下标 0 = 1 月） */
    public static int[] monthlyCounts(List<Book> books, int year) {
        int[] out = new int[12];
        for (Book book : books) {
            if (BookFlags.isBorrowSource(book)) {
                continue;
            }
            int[] pd = StatsText.parseYmd(book.purchaseDate);
            if (pd != null && pd[0] == year) {
                out[pd[1]]++;
            }
        }
        return out;
    }

    /** 构造整年每天的购买数（下标 0 = 1 月 1 日） */
    public static int[] yearDaily(List<Book> books, int year) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, 0, 1);
        int len = c.getActualMaximum(Calendar.DAY_OF_YEAR);
        int[] out = new int[len];
        for (Book book : books) {
            if (BookFlags.isBorrowSource(book)) {
                continue;
            }
            int[] pd = StatsText.parseYmd(book.purchaseDate);
            if (pd == null || pd[0] != year) {
                continue;
            }
            Calendar d = Calendar.getInstance();
            d.clear();
            d.set(pd[0], pd[1], pd[2]);
            int idx = d.get(Calendar.DAY_OF_YEAR) - 1;
            if (idx >= 0 && idx < out.length) {
                out[idx]++;
            }
        }
        return out;
    }
}
