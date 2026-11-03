package com.bai.bookshelf.util;

import com.bai.bookshelf.util.StatsHoldings.Holdings;

import static com.bai.bookshelf.util.LibraryStats.MIN_SAMPLE;
import static com.bai.bookshelf.util.LibraryStats.copiesOf;
import static com.bai.bookshelf.util.LibraryStats.copyPaid;
import static com.bai.bookshelf.util.LibraryStats.copyStatuses;
import static com.bai.bookshelf.util.LibraryStats.countOwnedCopies;
import static com.bai.bookshelf.util.LibraryStats.enough;
import static com.bai.bookshelf.util.LibraryStats.groupCopies;
import static com.bai.bookshelf.util.LibraryStats.isPartialPeriod;
import static com.bai.bookshelf.util.LibraryStats.median;
import static com.bai.bookshelf.util.LibraryStats.pearson;
import static com.bai.bookshelf.util.LibraryStats.samePeriodLastYearKey;
import static com.bai.bookshelf.util.LibraryStats.toArray;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 阅读节奏（v5.8.1 从 LibraryStats 拆出）：
 * 完成率与阅读速度、书债、年度目标预测、阅读习惯、买读比、该处理的书。
 */
public final class StatsPace {

    private StatsPace() {
    }

    /** 完成率、阅读速度与未读资产 */
    /**
     * 弃读之后的走向（v5.8.31，种口径）：从 readingRecords 的会话序列推。
     * 弃读不是终点——先把「有多少书弃过」摆出来，再看其中多少本后来重读、最终读完。
     */
    public static class DropRecovery {
        public int droppedTitles;
        public int rereadAfterDrop;
        public int finishedAfterDrop;

        public boolean enoughSample() {
            return droppedTitles >= MIN_SAMPLE;
        }

        public String line() {
            if (droppedTitles == 0) {
                return "弃读后走向：没有弃读记录";
            }
            if (!enoughSample()) {
                return "弃读后走向：弃读 " + droppedTitles + " 种，样本不足 "
                        + MIN_SAMPLE + " 本，先不下结论";
            }
            return "弃读后走向：弃读 " + droppedTitles + " 种 · 之后重读 "
                    + rereadAfterDrop + " 种 · 最终读完 " + finishedAfterDrop + " 种";
        }
    }

    /** 按会话 seq 判断「弃读之后还有没有下一场 / 有没有读完」 */
    public static DropRecovery dropRecovery(List<Book> books) {
        DropRecovery d = new DropRecovery();
        if (books == null) {
            return d;
        }
        for (Book book : books) {
            List<ReadingRecords.Record> records = ReadingRecords.load(book);
            int firstDropSeq = 0;
            for (ReadingRecords.Record r : records) {
                if (r.status == Book.READ_DROPPED
                        && (firstDropSeq == 0 || r.seq < firstDropSeq)) {
                    firstDropSeq = r.seq;
                }
            }
            if (firstDropSeq == 0) {
                continue;
            }
            d.droppedTitles++;
            boolean reread = false;
            boolean finished = false;
            for (ReadingRecords.Record r : records) {
                if (r.seq > firstDropSeq) {
                    reread = true;
                    if (r.status == Book.READ_DONE) {
                        finished = true;
                    }
                }
            }
            if (reread) {
                d.rereadAfterDrop++;
            }
            if (finished) {
                d.finishedAfterDrop++;
            }
        }
        return d;
    }

    public static class Pace {
        /** 已读 / 总种 */
        public double finishRate;
        /** 已读 / (已读 + 在读)：只看手上正在推进的部分 */
        public double activeFinishRate;
        /** 读完中位天数；样本不足 -1 */
        public double medianDays;
        /** 中位阅读速度（页/天）；样本不足 -1 */
        public double medianPagesPerDay;
        public int daySample;
        public int speedSample;
        public int unreadTitles;
        public int unreadCopies;
        /** 未读部分的实付（按册） */
        public double unreadPaid;
        /** 未读部分的总页数（按册） */
        public long unreadPages;
        /** 按当前速度清完未读需要几个月；无法估算 -1 */
        public double monthsToClear = -1;
        /** 无法估算时的原因（供 UI 说人话） */
        public String unknownReason;
    }

    /**
     * @param readingDaysLastYear 最近一年实际读过书的天数（来自阅读日历），用于把
     *                            页/天换算成「几个月能清完」；传 0 表示无法估算
     */
    public static Pace pace(List<Book> raw, java.util.Map<Long, List<BookCopy>> copiesByBook,
                            Holdings h, int readingDaysLastYear) {
        List<Book> books = BookSanitizer.normalizeAll(raw);
        Pace p = new Pace();
        p.finishRate = h.readRate();
        int inPlay = h.readDone + h.reading;
        p.activeFinishRate = inPlay == 0 ? -1 : (double) h.readDone / inPlay;

        List<Double> days = new ArrayList<>();
        List<Double> speed = new ArrayList<>();
        for (Book book : books) {
            if (book.readStatus == Book.READ_DROPPED || book.readingFinishDate.isEmpty()) {
                continue;
            }
            int finishKey = DateKeys.parseKey(book.readingFinishDate);
            int startKey = DateKeys.parseKey(book.readingStartDate);
            if (finishKey <= 0 || startKey <= 0 || finishKey < startKey) {
                continue; // 没有完整起止日期的书不参与速度统计
            }
            double d = BorrowReminder.daysBetween(startKey, finishKey) + 1;
            if (d <= 0) {
                continue;
            }
            days.add(d);
            if (book.pages > 0) {
                speed.add(book.pages / d);
            }
        }
        p.daySample = days.size();
        p.speedSample = speed.size();
        p.medianDays = median(toArray(days));
        p.medianPagesPerDay = median(toArray(speed));

        for (Book book : books) {
            if (!BookFlags.countsAsOwned(book) || book.readStatus != Book.READ_NONE) {
                continue;
            }
            List<BookCopy> copies = copiesByBook == null ? null : copiesByBook.get(book.id);
            int n = copiesOf(book, copies);
            p.unreadTitles++;
            p.unreadCopies += n;
            if (book.ownType == Book.OWN_BUY) {
                for (int i = 0; i < n; i++) {
                    p.unreadPaid += copyPaid(book, copies, i);
                }
            }
            p.unreadPages += (long) Math.max(0, book.pages) * n;
        }

        double v = p.medianPagesPerDay;
        if (v <= 0) {
            p.unknownReason = "读完记录里没有「起止日期 + 页数」都齐的书";
        } else if (readingDaysLastYear <= 0) {
            p.unknownReason = "最近一年还没有阅读日历记录";
        } else if (p.unreadPages <= 0) {
            p.monthsToClear = 0;
        } else {
            double pagesPerMonth = v * (readingDaysLastYear / 12.0);
            if (pagesPerMonth <= 0) {
                p.unknownReason = "平均每月阅读页数算不出来";
            } else {
                p.monthsToClear = p.unreadPages / pagesPerMonth;
            }
        }
        return p;
    }

    /**
     * 在读进度盘点（v5.8.19）：还剩多少页没读、按中位速度大概哪天读完。
     * 只算「在读 + 填了总页数」的书，没填页数的单独报个数，不混进页数口径。
     */
    public static final class Backlog {
        public int titles;
        public int counted;
        public int pagesLeft;
        public double pagesPerDay;
        public int daysLeft = -1;
        public String etaDate;

        public String line() {
            if (titles == 0) {
                return "现在没有「在读」的书 · 去书架把要读的点成在读";
            }
            StringBuilder sb = new StringBuilder();
            sb.append("在读 ").append(titles).append(" 本");
            if (counted == 0) {
                sb.append(" · 都没填总页数，算不出还剩多少页");
                return sb.toString();
            }
            sb.append(" · 还剩约 ").append(pagesLeft).append(" 页");
            if (counted < titles) {
                sb.append("（").append(titles - counted).append(" 本没填页数，没算进来）");
            }
            if (pagesLeft == 0) {
                sb.append(" · 都读到 100% 了，去标个「已读」");
                return sb.toString();
            }
            if (pagesPerDay <= 0 || daysLeft < 0) {
                sb.append(" · 读完记录里凑不出速度，算不出预计读完日");
            } else {
                sb.append(" · 按 ").append(Math.round(pagesPerDay)).append(" 页/天，约 ")
                        .append(daysLeft).append(" 天后（").append(etaDate).append("）读完");
            }
            return sb.toString();
        }
    }

    /**
     * @param medianPagesPerDay 来自 {@link Pace#medianPagesPerDay}，<=0 表示样本不足
     * @param todayKey          yyyyMMdd 整数键
     */
    public static Backlog backlog(List<Book> raw, double medianPagesPerDay, int todayKey) {
        Backlog b = new Backlog();
        b.pagesPerDay = medianPagesPerDay;
        for (Book book : BookSanitizer.normalizeAll(raw)) {
            if (book.readStatus != Book.READING) {
                continue;
            }
            b.titles++;
            int left = ReadingProgress.pagesLeft(book);
            if (left < 0) {
                continue;
            }
            b.counted++;
            b.pagesLeft += left;
        }
        if (b.pagesPerDay > 0 && b.pagesLeft > 0) {
            b.daysLeft = (int) Math.ceil(b.pagesLeft / b.pagesPerDay);
            b.etaDate = plusDays(todayKey, b.daysLeft);
        }
        return b;
    }

    /** yyyyMMdd 键 + N 天 → yyyy-MM-dd */
    private static String plusDays(int dateKey, int days) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(dateKey / 10000, dateKey / 100 % 100 - 1, dateKey % 100, 0, 0, 0);
        c.add(java.util.Calendar.DAY_OF_MONTH, days);
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d",
                c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1,
                c.get(java.util.Calendar.DAY_OF_MONTH));
    }

    /** 年度目标能不能完成 */
    public static class Goal {
        public int target;
        public int done;
        /** 今年已过几个月（含当月，1~12） */
        public int monthsElapsed;
        /** 按当前速度预计全年能读几本 */
        public double projected;
        /** 剩下的月份里每月还要读几本才达标；已完成 0 */
        public double needPerMonth;
        public boolean reachable;

        /**
         * @param done         今年读完的种数
         * @param target       年度目标种数
         * @param month1       当前月份（1~12）
         */
        public static Goal of(int done, int target, int month1) {
            Goal g = new Goal();
            g.done = done;
            g.target = Math.max(1, target);
            g.monthsElapsed = Math.max(1, Math.min(12, month1));
            g.projected = done * 12.0 / g.monthsElapsed;
            int left = g.target - done;
            int monthsLeft = Math.max(0, 12 - g.monthsElapsed);
            g.needPerMonth = left <= 0 ? 0 : (double) left / monthsLeft;
            g.reachable = g.projected >= g.target;
            return g;
        }

        /** 一行结论；已完成与来不及都说清楚 */
        public String line() {
            if (done >= target) {
                return "今年已读完 " + Units.titles(done) + "，目标已完成";
            }
            StringBuilder sb = new StringBuilder("今年已读完 ").append(Units.titles(done))
                    .append("，目标 ").append(Units.titles(target))
                    .append("；按当前速度全年约 ").append(Units.titles(Math.round(projected)))
                    .append(reachable ? "，能完成" : "，完不成");
            if (!reachable) {
                sb.append("；剩下 ").append(12 - monthsElapsed).append(" 个月每月要读 ")
                        .append(whole(needPerMonth)).append(" 种");
            }
            return sb.toString();
        }

        /** 整数就不带小数点 */
        private static String whole(double v) {
            return v == Math.floor(v) ? String.valueOf((int) v)
                    : String.format(java.util.Locale.ROOT, "%.1f", v);
        }
    }

    /** 阅读日历派生的习惯指标 */
    public static class Habit {
        public int daysReadThisYear;
        /** 最长连续阅读天数（历史全部记录里找） */
        public int longestStreak;
        /** 当前连续天数 */
        public int currentStreak;
        /** 读书最多的月份（1~12，0 = 无数据） */
        public int bestMonth;
        public int bestMonthDays;
        /** 一周里读书最多的一天（Calendar.MONDAY=2..SUNDAY=1） */
        public int bestWeekday;
        public String bestWeekdayName;

        private static final String[] WEEKDAY_NAMES = {"周日", "周一", "周二", "周三",
                "周四", "周五", "周六"};

        /**
         * @param dayKeys  所有读过书的日子（yyyyMMdd）
         * @param todayKey 今天的 yyyyMMdd
         *
         * <p><b>只看当年</b>：阅读日历是按记录起止区间铺出来的，「在读且没填结束日期」
         * 的书会从开始日一直铺到今天。热力图要的就是这个语义，但习惯指标如果不按年
         * 截断，就会算出「7 月读了 155 天」（五个七月）、「连着 1627 天」这种废话。
         */
        public static Habit of(java.util.Collection<Integer> dayKeys, int year, int todayKey) {
            Habit h = new Habit();
            if (dayKeys == null || dayKeys.isEmpty()) {
                return h;
            }
            java.util.TreeSet<Integer> sorted = new java.util.TreeSet<>();
            for (int key : dayKeys) {
                if (key / 10000 == year) {
                    sorted.add(key);
                }
            }
            if (sorted.isEmpty()) {
                return h;
            }
            h.daysReadThisYear = sorted.size();
            int bestWeekday = -1;
            int[] weekdayCount = new int[7];
            int[] monthCount = new int[13];
            for (int key : sorted) {
                int m = key / 100 % 100;
                if (m >= 1 && m <= 12) {
                    monthCount[m]++;
                }
                java.util.Calendar c = java.util.Calendar.getInstance();
                c.clear();
                c.set(year, m - 1, key % 100);
                int dow = c.get(java.util.Calendar.DAY_OF_WEEK) - 1; // 0 = 周日
                weekdayCount[dow]++;
            }
            int streak = 0;
            int longest = 0;
            int previous = -2;
            for (int key : sorted) {
                streak = key == previous + 1 ? streak + 1 : 1;
                longest = Math.max(longest, streak);
                previous = key;
            }
            h.longestStreak = longest;
            // 当前连续：从今天或昨天往回数
            int cursor = sorted.contains(todayKey) ? todayKey
                    : (sorted.contains(DateKeys.shiftDayKey(todayKey, -1))
                            ? DateKeys.shiftDayKey(todayKey, -1) : -1);
            while (cursor > 0 && sorted.contains(cursor)) {
                h.currentStreak++;
                cursor = DateKeys.shiftDayKey(cursor, -1);
            }
            for (int m = 1; m <= 12; m++) {
                if (monthCount[m] > h.bestMonthDays) {
                    h.bestMonthDays = monthCount[m];
                    h.bestMonth = m;
                }
            }
            for (int d = 0; d < 7; d++) {
                if (weekdayCount[d] > bestWeekday) {
                    bestWeekday = weekdayCount[d];
                    h.bestWeekday = d;
                }
            }
            if (bestWeekday > 0) {
                h.bestWeekdayName = WEEKDAY_NAMES[h.bestWeekday];
            }
            return h;
        }
    }

    /** 买得比读得快多少 */
    public static class BuyRead {
        public int boughtThisYear;
        public int readThisYear;
        /** 买入 / 读完；读完为 0 时返回 -1（不显示除零） */
        public double ratio;
        /** 未读种数 / 在架种数 */
        public double hoardIndex;

        public static BuyRead of(int bought, int read, int unreadTitles, int totalTitles) {
            BuyRead b = new BuyRead();
            b.boughtThisYear = bought;
            b.readThisYear = read;
            b.ratio = read > 0 ? (double) bought / read : -1;
            b.hoardIndex = totalTitles > 0 ? (double) unreadTitles / totalTitles : -1;
            return b;
        }

        public String ratioText() {
            return ratio < 0 ? "今年还没读完一种"
                    : String.format(java.util.Locale.ROOT, "%.1f : 1", ratio);
        }
    }

    /** 读得慢与买了没读的清单 */
    public static class Stale {
        /** 在读超过 90 天的书（书名 → 已挂天数），只留前 limit 本 */
        public final List<String[]> readingTooLong = new ArrayList<>();
        /** 买入超过一年仍未读，只留前 limit 本 */
        public final List<String[]> boughtUnread = new ArrayList<>();
        public int readingOver30;
        public int readingOver90;
        /** 买来一年没读的总数（清单只是预览，计数不能被 limit 截断） */
        public int boughtUnreadCount;

        public boolean isEmpty() {
            return readingTooLong.isEmpty() && boughtUnread.isEmpty();
        }
    }

    /**
     * 该处理的书：在读挂太久、买了放一年没读。
     *
     * @param limit 每类最多列几本（清单只给个概览，全部数量在计数里）
     */
    public static Stale stale(List<Book> raw, int todayKey, int limit) {
        Stale s = new Stale();
        int yearAgo = DateKeys.shiftDayKey(todayKey, -365);
        for (Book book : BookSanitizer.normalizeAll(raw)) {
            if (!BookFlags.countsAsOwned(book)) {
                continue;
            }
            int start = DateKeys.parseKey(book.readingStartDate);
            if (book.readStatus == Book.READING && start > 0) {
                int days = BorrowReminder.daysBetween(start, todayKey);
                if (days > 30) {
                    s.readingOver30++;
                }
                if (days > 90) {
                    s.readingOver90++;
                    if (s.readingTooLong.size() < limit) {
                        s.readingTooLong.add(new String[]{book.title, String.valueOf(days)});
                    }
                }
            }
            int buy = DateKeys.parseKey(book.purchaseDate);
            if (book.readStatus == Book.READ_NONE && buy > 0 && buy <= yearAgo
                    && !BookFlags.isBorrowSource(book)) {
                s.boughtUnreadCount++;
                if (s.boughtUnread.size() < limit) {
                    s.boughtUnread.add(new String[]{book.title,
                            String.valueOf(BorrowReminder.daysBetween(buy, todayKey))});
                }
            }
        }
        return s;
    }
}
