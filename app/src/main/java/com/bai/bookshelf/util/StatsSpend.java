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
 * 花费与趋势（v5.8.1 从 LibraryStats 拆出）：
 * 折扣与单位成本、同比环比、逐月趋势与全年外推、价格与评分/页数的相关性。
 */
public final class StatsSpend {

    private StatsSpend() {
    }

    /** 折扣与每页/每万字成本 */
    public static class Spend {
        public double paid;
        public double listValue;
        public double saved;
        // 三个比率一律以 -1 起跳：0 会被 UI 当成「0 折 / 0 元一页」画出来，
        // 而口径要求样本不足时显示「数据不足」，不能显示 0
        /** 平均折扣（实付/定价，如 0.62 表示 6.2 折）；样本不足为 -1 */
        public double discount = -1;
        /** 每页成本（元）；样本不足为 -1 */
        public double perPage = -1;
        /** 每一万字成本（元）；样本不足为 -1 */
        public double per10kWords = -1;
        public int discountSample;
        public int pageSample;
        public int wordSample;

        /** 折扣的人话：6.2 折 */
        public String discountText() {
            return discount < 0 ? "数据不足"
                    : String.format(java.util.Locale.ROOT, "%.1f 折", discount * 10);
        }
    }

    public static Spend spend(Holdings h) {
        Spend s = new Spend();
        s.paid = h.paid;
        s.listValue = h.listValue;
        s.saved = Math.max(0, h.saved);
        s.discountSample = h.discountSample;
        s.pageSample = h.pageSample;
        s.wordSample = h.wordSample;
        if (h.discountSample >= MIN_SAMPLE && h.listValue > 0 && h.paid > 0) {
            s.discount = h.paid / h.listValue;
        }
        if (h.pageSample >= MIN_SAMPLE && h.pages > 0 && h.paid > 0) {
            s.perPage = h.paid / h.pages;
        }
        if (h.wordSample >= MIN_SAMPLE && h.words > 0 && h.paid > 0) {
            s.per10kWords = h.paid / (h.words / 10000.0);
        }
        return s;
    }

    /** 一段时间窗口的对比（今年 vs 去年同期、本月至今 vs 上月同期） */
    public static class PeriodDelta {
        public String label;
        public String nowLabel;
        public String prevLabel;
        public int nowBought;
        public int prevBought;
        public int nowRead;
        public int prevRead;
        public double nowSpent;
        public double prevSpent;
        public int nowLent;
        public int prevLent;
        public int nowReturned;
        public int prevReturned;

        /** 一行结论：三个量各给「本期 / 对比期」，不画折线也不报增长率 */
        public String line() {
            String s = label + "：买 " + nowBought + " / " + prevBought + " 种 · 读 "
                    + nowRead + " / " + prevRead + " 种 · 花 ¥"
                    + Math.round(nowSpent) + " / ¥" + Math.round(prevSpent);
            // 没借出去过的人不该被塞一行「借出 0 / 0」，所以只在真有借还记录时补
            if (nowLent + prevLent + nowReturned + prevReturned > 0) {
                s += " · 借出 " + nowLent + " / " + prevLent
                        + " · 归还 " + nowReturned + " / " + prevReturned;
            }
            return s;
        }
    }

    /** 年同比与月环比（不看借阅） */
    public static List<PeriodDelta> periods(List<Book> raw, int todayKey) {
        return periods(raw, null, todayKey);
    }

    /**
     * 年同比与月环比。
     *
     * <p>对比期一律截到「同样的天数」：今年只过到 10 月 31 日，去年也只看 1 月 1 日到
     * 10 月 31 日；本月同理。否则就是拿今年 10 个月比去年 12 个月，结论必然是假的。
     *
     * @param loans 借出记录（{@code borrow_records}），为空时不出借还两项
     */
    public static List<PeriodDelta> periods(List<Book> raw, List<BorrowRecord> loans,
                                            int todayKey) {
        List<Book> books = BookSanitizer.normalizeAll(raw);
        List<PeriodDelta> out = new ArrayList<>();
        int year = todayKey / 10000;
        int month = todayKey / 100 % 100;
        int day = todayKey % 100;

        PeriodDelta y = new PeriodDelta();
        y.label = "今年 vs 去年同期";
        y.nowLabel = year + " 年至今";
        y.prevLabel = (year - 1) + " 年同期";
        accumulate(books, year * 10000 + 101, todayKey, y, true);
        accumulate(books, (year - 1) * 10000 + 101, samePeriodLastYearKey(todayKey), y, false);
        out.add(y);

        int monthStart = year * 10000 + month * 100 + 1;
        int prevYear = month == 1 ? year - 1 : year;
        int prevMonth = month == 1 ? 12 : month - 1;
        int prevStart = prevYear * 10000 + prevMonth * 100 + 1;
        // 上月没有「同一日」时（比如 31 日遇上 2 月）就截到上月末
        int prevSame = prevYear * 10000 + prevMonth * 100 + Math.min(day,
                DateKeys.daysInMonth(prevYear, prevMonth));
        PeriodDelta m = new PeriodDelta();
        m.label = "本月 vs 上月同期";
        m.nowLabel = month + " 月至今";
        m.prevLabel = prevMonth + " 月同期";
        accumulate(books, monthStart, todayKey, m, true);
        accumulate(books, prevStart, prevSame, m, false);
        out.add(m);

        if (loans != null && !loans.isEmpty()) {
            for (PeriodDelta d : out) {
                d.nowLent = d.prevLent = d.nowReturned = d.prevReturned = 0;
            }
            int lastYearStart = (year - 1) * 10000 + 101;
            int lastYearSame = samePeriodLastYearKey(todayKey);
            for (BorrowRecord loan : loans) {
                int lent = DateKeys.parseKey(loan.lendDate);
                int back = DateKeys.parseKey(loan.returnDate);
                countIn(y, lent, year * 10000 + 101, todayKey, lastYearStart, lastYearSame, true);
                countIn(m, lent, monthStart, todayKey, prevStart, prevSame, true);
                countIn(y, back, year * 10000 + 101, todayKey, lastYearStart, lastYearSame, false);
                countIn(m, back, monthStart, todayKey, prevStart, prevSame, false);
            }
        }
        return out;
    }

    /** 借出按 lendDate 归期、归还按 returnDate 归期，一条记录两个日期各算一次 */
    private static void countIn(PeriodDelta d, int key, int nowFrom, int nowTo,
                                int prevFrom, int prevTo, boolean lent) {
        if (key <= 0) {
            return;
        }
        if (key >= nowFrom && key <= nowTo) {
            if (lent) {
                d.nowLent++;
            } else {
                d.nowReturned++;
            }
        } else if (key >= prevFrom && key <= prevTo) {
            if (lent) {
                d.prevLent++;
            } else {
                d.prevReturned++;
            }
        }
    }

    /** 把落在 [from, to] 内的买入 / 读完 / 花费累加进对比项的本期或对比期 */
    private static void accumulate(List<Book> books, int from, int to, PeriodDelta d,
                                   boolean current) {
        if (to < from) {
            return;
        }
        for (Book book : books) {
            int buy = DateKeys.parseKey(book.purchaseDate);
            if (buy >= from && buy <= to && !BookFlags.isBorrowSource(book)) {
                // 花费按册（与 trend / Holdings.paid 同口径），否则同一句「今年花销」
                // 会在两张卡上算出两个数
                double paid = book.ownType == Book.OWN_BUY
                        ? Math.max(0, book.purchasePrice) * Math.max(1, book.quantity) : 0;
                if (current) {
                    d.nowBought++;
                    d.nowSpent += paid;
                } else {
                    d.prevBought++;
                    d.prevSpent += paid;
                }
            }
            if (book.readStatus == Book.READ_DROPPED) {
                continue;
            }
            int finish = DateKeys.parseKey(book.readingFinishDate);
            if (finish >= from && finish <= to) {
                if (current) {
                    d.nowRead++;
                } else {
                    d.prevRead++;
                }
            }
        }
    }

    /** 某年的逐月买入与花费，外加外推与移动平均 */
    public static class Trend {
        public int year;
        /** 1~12 月的标签（只到已过去的月份） */
        public final List<String> months = new ArrayList<>();
        public final List<Integer> bought = new ArrayList<>();
        public final List<Double> spent = new ArrayList<>();
        public double yearSpent;
        public int yearBought;
        public int monthsElapsed = 1;
        /** 按已过月份外推的全年花费 */
        public double projectedYear;
        /** 近三个月月均花费 */
        public double threeMonthAvg;
        /** 这一年是否还没过完（外推要标「至今」） */
        public boolean partial;

        public String projectedText() {
            return "全年预计 ¥" + Math.round(projectedYear)
                    + (partial ? "（按前 " + monthsElapsed + " 个月外推，至今）" : "");
        }

        /** 预算剩余；budget<=0 表示没设预算，返回 null 让调用方不显示 */
        public String budgetLeft(double budget) {
            if (budget <= 0) {
                return null;
            }
            double left = budget - yearSpent;
            return left >= 0 ? "预算还剩 ¥" + Math.round(left)
                    : "已超预算 ¥" + Math.round(-left);
        }
    }

    /** 逐月买入与花费趋势（只算自购，借阅与赠书不进花费） */
    public static Trend trend(List<Book> raw, int year, int todayKey) {
        Trend t = new Trend();
        t.year = year;
        int todayYear = todayKey / 10000;
        int todayMonth = todayKey / 100 % 100;
        t.partial = year == todayYear;
        t.monthsElapsed = t.partial ? todayMonth : 12;
        int[] counts = new int[13];
        double[] sums = new double[13];
        for (Book book : BookSanitizer.normalizeAll(raw)) {
            int buy = DateKeys.parseKey(book.purchaseDate);
            if (buy / 10000 != year || book.ownType != Book.OWN_BUY) {
                continue;
            }
            int m = buy / 100 % 100;
            if (m < 1 || m > 12) {
                continue;
            }
            // 趋势的「量」按种数走（与 cohort 同口径），「钱」按册累加
            counts[m]++;
            sums[m] += Math.max(0, book.purchasePrice) * Math.max(1, book.quantity);
        }
        for (int m = 1; m <= t.monthsElapsed; m++) {
            t.months.add(m + "月");
            t.bought.add(counts[m]);
            t.spent.add(sums[m]);
            t.yearBought += counts[m];
            t.yearSpent += sums[m];
        }
        t.projectedYear = t.yearSpent * 12.0 / t.monthsElapsed;
        int from = Math.max(1, t.monthsElapsed - 2);
        double acc = 0;
        int n = 0;
        for (int m = from; m <= t.monthsElapsed; m++) {
            acc += sums[m];
            n++;
        }
        t.threeMonthAvg = n == 0 ? 0 : acc / n;
        return t;
    }

    /** 贵书是不是更厚、更好看：皮尔逊相关系数，样本不足不给 */
    public static class Relation {
        public double priceVsRating;
        public double priceVsPages;
        public int ratingSample;
        public int pagesSample;

        public String line() {
            StringBuilder sb = new StringBuilder("价格与评分 ");
            sb.append(ratingSample >= MIN_SAMPLE ? "r=" + format(priceVsRating)
                    : "数据不足（" + ratingSample + " 种）");
            sb.append(" · 与页数 ");
            sb.append(pagesSample >= MIN_SAMPLE ? "r=" + format(priceVsPages)
                    : "数据不足（" + pagesSample + " 种）");
            return sb.toString();
        }

        private static String format(double r) {
            return String.format(java.util.Locale.ROOT, "%.2f", r);
        }
    }

    public static Relation relations(List<Book> raw) {
        Relation r = new Relation();
        java.util.List<Double> priceForRating = new ArrayList<>();
        java.util.List<Double> rating = new ArrayList<>();
        java.util.List<Double> priceForPages = new ArrayList<>();
        java.util.List<Double> pages = new ArrayList<>();
        for (Book book : BookSanitizer.normalizeAll(raw)) {
            if (!BookFlags.countsAsOwned(book) || book.purchasePrice <= 0) {
                continue;
            }
            double score = book.ratingAvg > 0 ? book.ratingAvg : book.rating;
            if (score > 0) {
                priceForRating.add(book.purchasePrice);
                rating.add(score);
            }
            if (book.pages > 0) {
                priceForPages.add(book.purchasePrice);
                pages.add((double) book.pages);
            }
        }
        r.ratingSample = rating.size();
        r.pagesSample = pages.size();
        r.priceVsRating = pearson(priceForRating, rating);
        r.priceVsPages = pearson(priceForPages, pages);
        return r;
    }
}
