package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 统计口径地基（v5.8）：所有统计页的数字都从这里出，避免各页各算一套。
 *
 * <p><b>为什么需要这一层</b>：同一句「藏书 19 册」在书架页头、我的页、统计页
 * 曾经分别按 {@code books.size()} 和「排除已归还借阅」两种算法得出，
 * 而一本 3 册的书在任何一种算法里都只算 1——标签写着「册」，算的其实是「种」，
 * 于是已读率、地点资产、花费全都对不上。这里把口径一次定死：
 *
 * <ul>
 *   <li><b>种</b>（unique title）= books 表一行。藏书总数、已读率、阅读目标按种。</li>
 *   <li><b>册</b>（物理本）= {@code quantity} 与逐本副本表取大，至少 1。
 *       物理册数、地点资产、借出/遗失、按册的花费都按册。</li>
 *   <li><b>花费</b>只算自购：赠书与借阅不计入（赠书的定价另记为「赠书价值」，
 *       可以谈资产，但不能说成「花了多少钱」）。套装的分摊价在保存时已写进
 *       {@code purchasePrice}，这里直接求和即可。</li>
 *   <li><b>阅读速度</b>用中位数不用平均数：一两本大部头会把平均数拉到没有意义。</li>
 *   <li><b>样本不足</b>（&lt; {@link #MIN_SAMPLE}）时相关系数、趋势一律判「数据不足」，
 *       不硬画。</li>
 *   <li><b>未完整周期</b>：当月/当年要标「至今」，同比必须用「去年同期的前 N 天」。</li>
 * </ul>
 *
 * <p>本类是纯计算，不碰数据库与 UI，可直接在 JVM 单测里跑。
 */
public final class LibraryStats {

    /** 少于该样本量不给结论（用户定的口径：&lt; 10 显示「数据不足」） */
    /** 少于该样本量不给结论（用户定的口径：&lt; 10 显示「数据不足」） */
    public static final int MIN_SAMPLE = 10;

    private LibraryStats() {
    }

    /** 样本量是否够下结论 */
    public static boolean enough(int sampleSize) {
        return sampleSize >= MIN_SAMPLE;
    }

    /** 中位数；空数组返回 -1（调用方按「数据不足」处理，不要显示 0） */
    public static double median(double[] values) {
        if (values == null || values.length == 0) {
            return -1;
        }
        double[] v = values.clone();
        Arrays.sort(v);
        int mid = v.length / 2;
        return v.length % 2 == 1 ? v[mid] : (v[mid - 1] + v[mid]) / 2.0;
    }

    /** 一本书的物理册数：手填数量与逐本副本取大，至少 1 */
    public static int copiesOf(Book book, List<BookCopy> copies) {
        int declared = Math.max(1, book.quantity);
        int recorded = 1 + (copies == null ? 0 : copies.size());
        return Math.max(declared, recorded);
    }

    /**
     * 逐册的藏书状态：第 1 册用主记录状态，第 2 册起用副本表状态；
     * 副本表填得比册数少时，缺的那几册沿用主记录状态（用户还没逐本录）。
     */
    public static int[] copyStatuses(Book book, List<BookCopy> copies) {
        int n = copiesOf(book, copies);
        int[] out = new int[n];
        out[0] = book.stockStatus;
        for (int i = 1; i < n; i++) {
            out[i] = copies != null && i - 1 < copies.size()
                    ? copies.get(i - 1).stockStatus : book.stockStatus;
        }
        return out;
    }

    /** 逐册实付价：副本录了价就用副本的，否则用主记录价（套装分摊已在保存时写进主记录） */
    public static double copyPaid(Book book, List<BookCopy> copies, int index) {
        if (index == 0) {
            return book.purchasePrice;
        }
        if (copies != null && index - 1 < copies.size() && copies.get(index - 1).purchasePrice > 0) {
            return copies.get(index - 1).purchasePrice;
        }
        return book.purchasePrice;
    }

    /** 在架藏书的物理册数（书架页头、我的页这类只要一个数的场合用） */
    public static int countOwnedCopies(List<Book> books, java.util.Map<Long, List<BookCopy>> copiesByBook) {
        if (books == null) {
            return 0;
        }
        int total = 0;
        for (Book book : BookSanitizer.normalizeAll(books)) {
            if (!BookFlags.countsAsOwned(book)) {
                continue;
            }
            total += copiesOf(book, copiesByBook == null ? null : copiesByBook.get(book.id));
        }
        return total;
    }

    /** 按 bookId 把逐本副本分好组，供上面几个方法使用 */
    public static java.util.Map<Long, List<BookCopy>> groupCopies(List<BookCopy> all) {
        java.util.Map<Long, List<BookCopy>> map = new java.util.HashMap<>();
        if (all == null) {
            return map;
        }
        for (BookCopy c : all) {
            List<BookCopy> list = map.get(c.bookId);
            if (list == null) {
                list = new ArrayList<>();
                map.put(c.bookId, list);
            }
            list.add(c);
        }
        for (List<BookCopy> list : map.values()) {
            list.sort((x, y) -> Integer.compare(x.copyIndex, y.copyIndex));
        }
        return map;
    }

    /** 该年份/月份是否还没过完（同比、环比必须用同期口径） */
    public static boolean isPartialPeriod(int year, int month1, int todayYear, int todayMonth1) {
        if (year > todayYear) {
            return true;
        }
        return year == todayYear && month1 >= todayMonth1;
    }

    /**
     * 「去年同期的最后一天」日键：今年只过了前 N 天时，去年也只看前 N 天，
     * 否则就是拿今年 3 个月比去年 12 个月。
     *
     * @param todayKey yyyyMMdd
     */
    public static int samePeriodLastYearKey(int todayKey) {
        int y = todayKey / 10000;
        int md = todayKey % 10000;
        return (y - 1) * 10000 + md;
    }

    /** 皮尔逊相关系数；样本 < 2 或某一侧没有方差时返回 0 */
    public static double pearson(java.util.List<Double> x, java.util.List<Double> y) {
        int n = Math.min(x.size(), y.size());
        if (n < 2) {
            return 0;
        }
        double mx = 0;
        double my = 0;
        for (int i = 0; i < n; i++) {
            mx += x.get(i);
            my += y.get(i);
        }
        mx /= n;
        my /= n;
        double sxy = 0;
        double sxx = 0;
        double syy = 0;
        for (int i = 0; i < n; i++) {
            double dx = x.get(i) - mx;
            double dy = y.get(i) - my;
            sxy += dx * dy;
            sxx += dx * dx;
            syy += dy * dy;
        }
        if (sxx <= 0 || syy <= 0) {
            return 0;
        }
        return sxy / Math.sqrt(sxx * syy);
    }

    /** 包内共用：把 Double 列表转数组（评分、相关性这类计算用） */
    static double[] toArray(List<Double> list) {
        if (list == null) {
            return new double[0];
        }
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}
