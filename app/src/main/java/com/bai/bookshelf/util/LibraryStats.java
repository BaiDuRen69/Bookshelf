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
    public static final int MIN_SAMPLE = 10;

    private LibraryStats() {
    }

    // ==================== 基础工具 ====================

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

    // ==================== 藏书总量（种 / 册） ====================

    /** 藏书基础量：种、册、状态分布与花费 */
    public static class Holdings {
        /** 在架种数（已归还的电子书签不计） */
        public int titles;
        /** 在架册数（物理本） */
        public int copies;
        /** 全部书籍行数，含已归还借阅：书架列表的总数口径 */
        public int rows;
        public int readDone;
        public int reading;
        public int unread;
        public int dropped;
        /** 借出在外的册数 */
        public int copiesLent;
        /** 遗失的册数 */
        public int copiesLost;
        /** 遗失册的自购实付合计（损耗率） */
        public double lostValue;
        /** 自购实付合计（按册） */
        public double paid;
        /** 自购定价合计（按册） */
        public double listValue;
        /** 赠书定价合计：算资产，不算花费 */
        public double giftValue;
        /** 同时有定价与实付的自购种数（折扣样本） */
        public int discountSample;
        /** 累计省下多少（只统计折扣样本） */
        public double saved;
        /** 有页数的种数与总页数（每页成本样本，按册） */
        public int pageSample;
        public long pages;
        /** 有字数的种数与总字数 */
        public int wordSample;
        public double words;

        /** 已读率（按种）；无藏书返回 -1 */
        public double readRate() {
            return titles == 0 ? -1 : (double) readDone / titles;
        }

        /** 在架可翻到的册数（盘点结果：总数扣掉在外与遗失） */
        public int copiesInStore() {
            return Math.max(0, copies - copiesLent - copiesLost);
        }

        /** 损耗率（遗失册数 / 总册数）；无藏书 -1 */
        public double lossRate() {
            return copies == 0 ? -1 : (double) copiesLost / copies;
        }

        /** 在读 + 已读之外的「积压」种数 */
        public int backlogTitles() {
            return unread + dropped;
        }
    }

    /** 藏书总量：种 / 册 / 花费一次算清 */
    public static Holdings holdings(List<Book> books, java.util.Map<Long, List<BookCopy>> copiesByBook) {
        Holdings h = new Holdings();
        if (books == null) {
            return h;
        }
        for (Book book : BookSanitizer.normalizeAll(books)) {
            h.rows++;
            boolean owned = BookFlags.countsAsOwned(book);
            List<BookCopy> copies = copiesByBook == null
                    ? null : copiesByBook.get(book.id);
            int n = copiesOf(book, copies);
            if (owned) {
                h.titles++;
                h.copies += n;
                int[] statuses = copyStatuses(book, copies);
                for (int i = 0; i < n; i++) {
                    if (statuses[i] == Book.STOCK_LENT) {
                        h.copiesLent++;
                    } else if (statuses[i] == Book.STOCK_LOST) {
                        h.copiesLost++;
                        if (book.ownType == Book.OWN_BUY) {
                            h.lostValue += copyPaid(book, copies, i);
                        }
                    }
                }
                switch (book.readStatus) {
                    case Book.READ_DONE:
                        h.readDone++;
                        break;
                    case Book.READING:
                        h.reading++;
                        break;
                    case Book.READ_DROPPED:
                        h.dropped++;
                        break;
                    default:
                        h.unread++;
                        break;
                }
            }
            // 花费口径：只有自购算钱，且按册累加逐本实付
            if (book.ownType == Book.OWN_BUY) {
                boolean both = book.purchasePrice > 0 && book.listPrice > 0;
                for (int i = 0; i < n; i++) {
                    double paid = copyPaid(book, copies, i);
                    if (paid > 0) {
                        h.paid += paid;
                    }
                    h.listValue += book.listPrice;
                    if (both) {
                        h.saved += book.listPrice - paid;
                    }
                }
                if (both) {
                    h.discountSample++;
                }
            } else if (book.ownType == Book.OWN_GIFT) {
                h.giftValue += book.listPrice * n;
            }
            if (owned && book.pages > 0) {
                h.pageSample++;
                h.pages += (long) book.pages * n;
            }
            double words = BookSanitizer.parseWords(book.wordCount);
            if (owned && words > 0) {
                h.wordSample++;
                h.words += words * n;
            }
        }
        return h;
    }

    // ==================== 花费与单位成本 ====================

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

    // ==================== 阅读速度与书债 ====================

    /** 完成率、阅读速度与未读资产 */
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

    // ==================== 借阅 ====================

    /** 借出管理与按时归还率 */
    public static class Lend {
        public int out;
        public int overdue;
        public int returned;
        /** 按时归还率（只统计填了应还日期的归还）；无样本 -1 */
        public double onTimeRate;
        public int onTimeSample;
        /** 按时归还的笔数（onTimeRate 的分子） */
        public int onTimeCount;
        /** 在外书籍的平均已借天数；无在外的 -1 */
        public double avgDaysOut;
        /** 逾期未还的平均逾期天数；无逾期的 -1 */
        public double avgOverdueDays;
    }

    public static Lend lend(List<BorrowRecord> records, int todayKey) {
        Lend l = new Lend();
        if (records == null) {
            return l;
        }
        List<Double> outDays = new ArrayList<>();
        List<Double> overdueDays = new ArrayList<>();
        for (BorrowRecord r : records) {
            int due = DateKeys.parseKey(r.dueDate);
            int ret = DateKeys.parseKey(r.returnDate);
            int lend = DateKeys.parseKey(r.lendDate);
            if (r.status == BorrowRecord.STATUS_RETURNED) {
                l.returned++;
                if (due > 0 && ret > 0) {
                    l.onTimeSample++;
                    if (ret <= due) {
                        l.onTimeCount++;
                    }
                }
            } else {
                l.out++;
                if (due > 0 && due < todayKey) {
                    l.overdue++;
                    if (lend > 0) {
                        overdueDays.add((double) BorrowReminder.daysBetween(due, todayKey));
                    }
                }
                if (lend > 0) {
                    outDays.add((double) BorrowReminder.daysBetween(lend, todayKey) + 1);
                }
            }
        }
        l.onTimeRate = l.onTimeSample >= MIN_SAMPLE
                ? (double) l.onTimeCount / l.onTimeSample : -1;
        l.avgDaysOut = outDays.isEmpty() ? -1 : median(toArray(outDays));
        l.avgOverdueDays = overdueDays.isEmpty() ? -1 : median(toArray(overdueDays));
        return l;
    }

    // ==================== 数据健康度 ====================

    /** 元数据完整度与加权「书房健康分」 */
    public static class Health {
        public int total;
        public int withCover;
        public int withIsbn;
        public int withAuthor;
        public int withPublisher;
        public int withLocation;
        public int withCategory;
        public int withTags;
        public int withRating;
        /** 0~100 的加权分；无藏书返回 -1 */
        public int score;
        /** 最需要补的一项的中文名，全满返回 null */
        public String weakest;
        public int weakestMissing;
    }

    /** 各项覆盖率权重（合计 100）：封面与地点最影响日常使用，权重最高 */
    private static final int W_COVER = 20;
    private static final int W_LOCATION = 20;
    private static final int W_CATEGORY = 15;
    private static final int W_ISBN = 10;
    private static final int W_AUTHOR = 10;
    private static final int W_PUBLISHER = 10;
    private static final int W_TAGS = 5;
    private static final int W_RATING = 10;

    public static Health health(List<Book> books) {
        Health s = new Health();
        if (books == null) {
            return s;
        }
        for (Book book : BookSanitizer.normalizeAll(books)) {
            if (!BookFlags.countsAsOwned(book)) {
                continue;
            }
            s.total++;
            boolean cover = !book.coverUrl.isEmpty();
            boolean isbn = !book.isbn.isEmpty();
            boolean author = !book.author.trim().isEmpty();
            boolean publisher = !book.publisher.trim().isEmpty();
            boolean location = book.locationId != null;
            boolean category = !book.category.trim().isEmpty();
            boolean tags = !book.tags.trim().isEmpty();
            boolean rating = book.rating > 0 || book.ratingAvg > 0;
            if (cover) {
                s.withCover++;
            }
            if (isbn) {
                s.withIsbn++;
            }
            if (author) {
                s.withAuthor++;
            }
            if (publisher) {
                s.withPublisher++;
            }
            if (location) {
                s.withLocation++;
            }
            if (category) {
                s.withCategory++;
            }
            if (tags) {
                s.withTags++;
            }
            if (rating) {
                s.withRating++;
            }
        }
        if (s.total == 0) {
            s.score = -1;
            return s;
        }
        int[][] pairs = {
                {s.withCover, W_COVER, 0}, {s.withLocation, W_LOCATION, 1},
                {s.withCategory, W_CATEGORY, 2}, {s.withIsbn, W_ISBN, 3},
                {s.withAuthor, W_AUTHOR, 4}, {s.withPublisher, W_PUBLISHER, 5},
                {s.withRating, W_RATING, 6}, {s.withTags, W_TAGS, 7},
        };
        String[] names = {"封面", "存放地点", "分类", "ISBN", "作者", "出版社", "评分", "标签"};
        int sum = 0;
        int worstMissing = -1;
        for (int[] p : pairs) {
            sum += p[1] * p[0] / s.total;
            int missing = s.total - p[0];
            // 缺口按权重的绝对量排序：补谁收益最大
            int loss = missing * p[1];
            if (missing > 0 && loss > worstMissing) {
                worstMissing = loss;
                s.weakest = names[p[2]];
                s.weakestMissing = missing;
            }
        }
        s.score = sum;
        return s;
    }

    // ==================== 分组聚合（分类 / 出版社 / 来源） ====================

    /** 一个分组的统计：分类树状图、出版社对比、来源结构共用 */
    public static class Group {
        public String name;
        /** 种数 */
        public int titles;
        /** 册数 */
        public int copies;
        public int readDone;
        /** 自购实付（按册） */
        public double paid;
        /** 定价合计（按册） */
        public double listValue;
        public long pages;
        /** 二级分组（只有分类树用得到） */
        public final List<Group> children = new ArrayList<>();

        public Group(String name) {
            this.name = name;
        }

        /** 已读率（按种）；无数据 -1 */
        public double readRate() {
            return titles == 0 ? -1 : (double) readDone / titles;
        }

        /** 平均折扣（实付/定价）；样本为 0 或定价为 0 时 -1 */
        public double discount() {
            return listValue > 0 && paid > 0 ? paid / listValue : -1;
        }

        /** 每页成本；无页数 -1 */
        public double perPage() {
            return pages > 0 && paid > 0 ? paid / pages : -1;
        }

        /** 一行结论：种 / 册 / 花费 / 折扣 / 读完率（渠道 ROI 与分类成本共用） */
        public String statLine() {
            StringBuilder sb = new StringBuilder(name).append(" · ").append(titles).append(" 种");
            if (copies != titles) {
                sb.append(" / ").append(copies).append(" 册");
            }
            if (paid > 0) {
                sb.append(" · 花费 ¥").append(Math.round(paid));
            }
            double d = discount();
            if (d > 0) {
                sb.append(String.format(java.util.Locale.ROOT, " · %.1f 折", d * 10));
            }
            double pp = perPage();
            if (pp > 0) {
                sb.append(String.format(java.util.Locale.ROOT, " · 每页 ¥%.2f", pp));
            }
            double rr = readRate();
            if (rr >= 0) {
                sb.append(" · 读完 ").append(Math.round(rr * 100)).append("%");
            }
            return sb.toString();
        }
    }

    /** 分组键提取器（Java 8 函数式接口，minSdk 24 可直接用） */
    public interface KeyOf {
        String of(Book book);
    }

    /**
     * 按某个字段把在架藏书分组，按种数从多到少排序。
     *
     * @param keyOf        取分组名；返回空串的书归入 {@code fallbackName}
     * @param countBorrow  是否把借阅来源的书计入（花费口径下要排除，结构口径下要保留）
     */
    public static List<Group> groupBy(List<Book> raw,
                                      java.util.Map<Long, List<BookCopy>> copiesByBook,
                                      KeyOf keyOf, String fallbackName, boolean countBorrow) {
        List<Book> books = BookSanitizer.normalizeAll(raw);
        java.util.Map<String, Group> map = new java.util.LinkedHashMap<>();
        for (Book book : books) {
            if (!BookFlags.countsAsOwned(book)) {
                continue;
            }
            if (!countBorrow && BookFlags.isBorrowSource(book)) {
                continue;
            }
            String key = keyOf.of(book);
            if (key == null) {
                key = "";
            }
            key = key.trim();
            if (key.isEmpty()) {
                key = fallbackName;
            }
            Group g = map.get(key);
            if (g == null) {
                g = new Group(key);
                map.put(key, g);
            }
            List<BookCopy> copies = copiesByBook == null ? null : copiesByBook.get(book.id);
            int n = copiesOf(book, copies);
            g.titles++;
            g.copies += n;
            if (book.readStatus == Book.READ_DONE) {
                g.readDone++;
            }
            if (book.pages > 0) {
                g.pages += (long) book.pages * n;
            }
            if (book.ownType == Book.OWN_BUY) {
                for (int i = 0; i < n; i++) {
                    double paid = copyPaid(book, copies, i);
                    if (paid > 0) {
                        g.paid += paid;
                    }
                    g.listValue += book.listPrice;
                }
            }
        }
        List<Group> out = new ArrayList<>(map.values());
        out.sort((x, y) -> y.titles != x.titles
                ? Integer.compare(y.titles, x.titles) : x.name.compareTo(y.name));
        return out;
    }

    /**
     * 一级/二级分类树。
     *
     * <p>{@code Book.category} 只存分类名，层级靠 categories 表的 {@code parentId} 还原：
     * 名字命中二级栏目时归到它的一级父栏目下，命中不了一级/二级都按一级处理
     * （分类被删过但书上还留着名字的情况，不能因此丢书）。
     */
    public static List<Group> byCategory(List<Book> books,
                                         java.util.Map<Long, List<BookCopy>> copiesByBook,
                                         List<com.bai.bookshelf.data.entity.Category> categories,
                                         String fallbackName) {
        java.util.Map<String, com.bai.bookshelf.data.entity.Category> byName = new java.util.HashMap<>();
        java.util.Map<Long, String> nameById = new java.util.HashMap<>();
        if (categories != null) {
            for (com.bai.bookshelf.data.entity.Category c : categories) {
                String n = c.name == null ? "" : c.name.trim();
                if (n.isEmpty()) {
                    continue;
                }
                byName.put(n, c);
                nameById.put(c.id, n);
            }
        }
        List<Group> top = groupBy(books, copiesByBook,
                b -> topLevelOf(b, byName, nameById), fallbackName, true);
        // 第二遍：把每本书挂到对应一级组的二级子组上
        for (Group g : top) {
            java.util.Map<String, Group> subs = new java.util.LinkedHashMap<>();
            g.children.clear();
            for (Book book : BookSanitizer.normalizeAll(books)) {
                if (!BookFlags.countsAsOwned(book) || !topLevelOf(book, byName, nameById).equals(g.name)) {
                    continue;
                }
                String sub = subLevelOf(book, byName, nameById);
                if (sub.isEmpty() || sub.equals(g.name)) {
                    continue; // 本身就是一级，或没有二级归属
                }
                Group child = subs.get(sub);
                if (child == null) {
                    child = new Group(sub);
                    subs.put(sub, child);
                    g.children.add(child);
                }
                List<BookCopy> copies = copiesByBook == null ? null : copiesByBook.get(book.id);
                int n = copiesOf(book, copies);
                child.titles++;
                child.copies += n;
                if (book.readStatus == Book.READ_DONE) {
                    child.readDone++;
                }
                if (book.pages > 0) {
                    child.pages += (long) book.pages * n;
                }
                if (book.ownType == Book.OWN_BUY) {
                    for (int i = 0; i < n; i++) {
                        double paid = copyPaid(book, copies, i);
                        if (paid > 0) {
                            child.paid += paid;
                        }
                        child.listValue += book.listPrice;
                    }
                }
            }
            g.children.sort((x, y) -> y.titles != x.titles
                    ? Integer.compare(y.titles, x.titles) : x.name.compareTo(y.name));
        }
        return top;
    }

    private static String topLevelOf(Book book,
                                     java.util.Map<String, com.bai.bookshelf.data.entity.Category> byName,
                                     java.util.Map<Long, String> nameById) {
        String n = book.category == null ? "" : book.category.trim();
        if (n.isEmpty()) {
            return "";
        }
        com.bai.bookshelf.data.entity.Category c = byName.get(n);
        if (c == null || c.parentId == null) {
            return n;
        }
        String parent = nameById.get(c.parentId);
        return parent == null || parent.isEmpty() ? n : parent;
    }

    private static String subLevelOf(Book book,
                                     java.util.Map<String, com.bai.bookshelf.data.entity.Category> byName,
                                     java.util.Map<Long, String> nameById) {
        String n = book.category == null ? "" : book.category.trim();
        com.bai.bookshelf.data.entity.Category c = byName.get(n);
        return c != null && c.parentId != null ? n : "";
    }

    // ==================== 购买 → 阅读 cohort ====================

    /** 某一买入年份的「买了多久才读」 */
    public static class Cohort {
        public int year;
        /** 该年买入的种数（借阅来源不算「买入」） */
        public int bought;
        /** 其中已有读完日期的种数 */
        public int finished;
        public int readIn1y;
        public int readIn3y;
        public int readIn5y;
        /** 购入→读完的中位天数；无样本 -1 */
        public double medianGapDays;
        public int gapSample;

        /** 观察窗口是否已经关闭：2026 年买的书谈不上「5 年内读完」 */
        public boolean windowClosed(int years, int todayYear) {
            return todayYear >= year + years;
        }

        /**
         * 一行的结论文案（带口径与样本数）：窗口没关满的年份不给几年内比例，
         * 样本不足 10 种只报买入数，不硬算百分比。
         */
        public String line(int todayYear) {
            // 注意别写 new StringBuilder(year)：那是容量构造器，年份会被吞掉
            StringBuilder sb = new StringBuilder().append(year).append(" 年买入 ")
                    .append(bought).append(" 种");
            if (!enough(bought)) {
                return sb.append(" · 样本不足").toString();
            }
            if (windowClosed(1, todayYear)) {
                sb.append(" · 1 年内读完 ").append(percent(readIn1y));
            }
            if (windowClosed(3, todayYear)) {
                sb.append(" · 3 年内 ").append(percent(readIn3y));
            }
            if (windowClosed(5, todayYear)) {
                sb.append(" · 5 年内 ").append(percent(readIn5y));
            }
            if (medianGapDays > 0) {
                sb.append(" · 中位 ").append(Math.round(medianGapDays)).append(" 天读完");
            }
            return sb.toString();
        }

        private String percent(int part) {
            return bought == 0 ? "—" : part + "（" + Math.round(part * 100.0 / bought) + "%）";
        }
    }

    /** 按买入年份从近到远给出各年 cohort */
    public static List<Cohort> cohort(List<Book> raw, int todayKey) {
        List<Book> books = BookSanitizer.normalizeAll(raw);
        java.util.Map<Integer, Cohort> map = new java.util.TreeMap<>();
        java.util.Map<Integer, List<Double>> gaps = new java.util.HashMap<>();
        for (Book book : books) {
            int buy = DateKeys.parseKey(book.purchaseDate);
            if (buy <= 0 || BookFlags.isBorrowSource(book)) {
                continue;
            }
            int year = buy / 10000;
            Cohort c = map.get(year);
            if (c == null) {
                c = new Cohort();
                c.year = year;
                map.put(year, c);
            }
            c.bought++;
            if (book.readStatus == Book.READ_DROPPED || book.readingFinishDate.isEmpty()) {
                continue;
            }
            int finish = DateKeys.parseKey(book.readingFinishDate);
            if (finish <= 0 || finish < buy) {
                continue;
            }
            int gap = BorrowReminder.daysBetween(buy, finish);
            c.finished++;
            List<Double> list = gaps.get(year);
            if (list == null) {
                list = new ArrayList<>();
                gaps.put(year, list);
            }
            list.add((double) gap);
            if (gap <= 365) {
                c.readIn1y++;
            }
            if (gap <= 365 * 3) {
                c.readIn3y++;
            }
            if (gap <= 365 * 5) {
                c.readIn5y++;
            }
        }
        List<Cohort> out = new ArrayList<>(map.values());
        java.util.Collections.reverse(out); // 近 → 远
        for (Cohort c : out) {
            List<Double> list = gaps.get(c.year);
            c.gapSample = list == null ? 0 : list.size();
            c.medianGapDays = median(toArray(list));
        }
        return out;
    }

    // ==================== 阅读目标预测 ====================

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
                return "目标 " + target + " 本已完成（已读 " + done + " 本）";
            }
            StringBuilder sb = new StringBuilder("今年已读 ").append(done).append(" 本，目标 ")
                    .append(target).append(" 本；按当前速度全年约 ").append(Math.round(projected))
                    .append(" 本").append(reachable ? "，能完成" : "，完不成");
            if (!reachable) {
                sb.append("；剩下 ").append(12 - monthsElapsed).append(" 个月每月要读 ")
                        .append(whole(needPerMonth)).append(" 本");
            }
            return sb.toString();
        }

        /** 整数就不带小数点 */
        private static String whole(double v) {
            return v == Math.floor(v) ? String.valueOf((int) v)
                    : String.format(java.util.Locale.ROOT, "%.1f", v);
        }
    }

    // ==================== 阅读习惯 ====================

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

    // ==================== 买读比与囤书指数 ====================

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
            return ratio < 0 ? "今年还没读完一本"
                    : String.format(java.util.Locale.ROOT, "%.1f : 1", ratio);
        }
    }

    // ==================== 该处理的书 ====================

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

    // ==================== 数据体检 ====================

    /** 重复与脏数据 */
    public static class Anomalies {
        public final List<String> duplicateIsbn = new ArrayList<>();
        public final List<String> duplicateTitle = new ArrayList<>();
        public int negativePrice;
        public int futureDate;
        public int overpriced;
        public int oddPages;

        public int total() {
            return duplicateIsbn.size() + duplicateTitle.size() + negativePrice
                    + futureDate + overpriced + oddPages;
        }
    }

    // ==================== 评分分析 ====================

    /** 评分分布与偏好 */
    public static class Rating {
        public int rated;
        public int total;
        public double average = -1;
        /** 索引 1..5 → 该分数的种数（四舍五入到整数星） */
        public final int[] buckets = new int[6];
        public String bestCategory;
        public double bestCategoryAvg = -1;

        public double unratedRate() {
            return total == 0 ? -1 : (double) (total - rated) / total;
        }
    }

    // ==================== 借阅人可靠性 ====================

    /** 一个借阅人的账本 */
    public static class Lender {
        public String name;
        public int loans;
        public int overdue;
        public int outNow;
        /** 有应还日期且已归还的笔数（按时率样本） */
        public int returnedWithDue;
        public int onTime;

        Lender(String name) {
            this.name = name;
        }

        public double onTimeRate() {
            return returnedWithDue == 0 ? -1 : (double) onTime / returnedWithDue;
        }
    }

    // ==================== 同比 / 环比 ====================

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

    // ==================== 读的结构 vs 藏的结构 ====================

    /** 同一分类在「藏书」与「已读」两个口径下的占比 */
    public static class Share {
        public String name;
        public int shelf;
        public int read;
        public double shelfShare;
        public double readShare;

        /** 一行结论：买得多不等于读得多 */
        public String line() {
            return name + " 藏书 " + Math.round(shelfShare * 100) + "% → 已读 "
                    + Math.round(readShare * 100) + "%";
        }
    }

    /** 按一级分类比较藏书占比与已读占比 */
    public static List<Share> structureCompare(List<Book> raw,
                                               java.util.Map<Long, List<BookCopy>> copiesByBook,
                                               List<com.bai.bookshelf.data.entity.Category> categories) {
        List<Share> out = new ArrayList<>();
        List<Group> tree = byCategory(raw, copiesByBook, categories, "未分类");
        int shelfTotal = 0;
        int readTotal = 0;
        for (Group g : tree) {
            shelfTotal += g.titles;
            readTotal += g.readDone;
        }
        if (shelfTotal == 0) {
            return out;
        }
        for (Group g : tree) {
            Share s = new Share();
            s.name = g.name;
            s.shelf = g.titles;
            s.read = g.readDone;
            s.shelfShare = (double) g.titles / shelfTotal;
            s.readShare = readTotal == 0 ? 0 : (double) g.readDone / readTotal;
            out.add(s);
        }
        return out;
    }

    // ==================== 花费趋势与预测 ====================

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

    // ==================== 价格与评分 / 页数的相关性 ====================

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

    // ==================== 摘抄 / 书评密度 ====================

    /** 有多少书真正读进去了：摘抄与短评的覆盖 */
    public static class NoteStat {
        public int total;
        public int withExcerpt;
        public int withReview;
        public int excerpts;

        public String line() {
            if (total == 0) {
                return "还没有藏书";
            }
            return "摘抄覆盖 " + Math.round(withExcerpt * 100.0 / total) + "%（"
                    + withExcerpt + " 种 · 共 " + excerpts + " 条）· 短评覆盖 "
                    + Math.round(withReview * 100.0 / total) + "%";
        }
    }

    public static NoteStat notes(List<Book> raw,
                                 List<com.bai.bookshelf.data.entity.Excerpt> excerpts) {
        NoteStat n = new NoteStat();
        java.util.Set<Long> hasExcerpt = new java.util.HashSet<>();
        if (excerpts != null) {
            for (com.bai.bookshelf.data.entity.Excerpt e : excerpts) {
                n.excerpts++;
                hasExcerpt.add(e.bookId);
            }
        }
        for (Book book : BookSanitizer.normalizeAll(raw)) {
            if (!BookFlags.countsAsOwned(book)) {
                continue;
            }
            n.total++;
            if (hasExcerpt.contains(book.id)) {
                n.withExcerpt++;
            }
            if (!book.review.trim().isEmpty()) {
                n.withReview++;
            }
        }
        return n;
    }

    // ==================== 想读转化 ====================

    /** 想读清单的规模与转化 */
    public static class WishStat {
        public int wanted;
        public double mindPrice;
        public int highPriority;
        public int converted;
        public double medianDaysToBuy = -1;

        public String line() {
            if (wanted == 0) {
                return "想读清单是空的";
            }
            StringBuilder sb = new StringBuilder("想读 ").append(wanted).append(" 本");
            if (mindPrice > 0) {
                sb.append(" · 心理价合计 ¥").append(Math.round(mindPrice));
            }
            if (highPriority > 0) {
                sb.append(" · 高优先 ").append(highPriority).append(" 本");
            }
            sb.append(" · 已转藏书 ").append(converted).append(" 本");
            if (medianDaysToBuy > 0) {
                sb.append(" · 中位 ").append(Math.round(medianDaysToBuy)).append(" 天后买");
            }
            return sb.toString();
        }
    }

    /**
     * 想读 → 藏书的转化：ISBN 相同，或「书名 + 作者」都相同视为同一本。
     * 天数按想读创建日到藏书录入日算，只统计转化成功的那些。
     */
    public static WishStat wishes(
            List<com.bai.bookshelf.data.entity.WishBook> wishes, List<Book> raw) {
        WishStat w = new WishStat();
        if (wishes == null || wishes.isEmpty()) {
            return w;
        }
        List<Book> books = BookSanitizer.normalizeAll(raw);
        java.util.Set<String> isbnSet = new java.util.HashSet<>();
        java.util.Set<String> titleSet = new java.util.HashSet<>();
        java.util.Map<String, Long> addedAt = new java.util.HashMap<>();
        java.util.Map<String, Long> addedAtByIsbn = new java.util.HashMap<>();
        for (Book b : books) {
            if (!b.isbn.isEmpty()) {
                isbnSet.add(b.isbn.trim());
                Long prevIsbn = addedAtByIsbn.get(b.isbn.trim());
                if (prevIsbn == null || b.createdAt < prevIsbn) {
                    addedAtByIsbn.put(b.isbn.trim(), b.createdAt);
                }
            }
            String key = wishKey(b.title, b.author);
            titleSet.add(key);
            Long prev = addedAt.get(key);
            if (prev == null || b.createdAt < prev) {
                addedAt.put(key, b.createdAt);
            }
        }
        List<Double> days = new ArrayList<>();
        for (com.bai.bookshelf.data.entity.WishBook wish : wishes) {
            w.wanted++;
            w.mindPrice += Math.max(0, wish.expectedPrice);
            if (wish.priority == com.bai.bookshelf.data.entity.WishBook.PRIORITY_HIGH) {
                w.highPriority++;
            }
            String isbn = wish.isbn.trim();
            boolean byIsbn = !isbn.isEmpty() && isbnSet.contains(isbn);
            String key = wishKey(wish.title, wish.author);
            // 录入时间要按「怎么匹配上的」去查：只认书名作者的话，
            // 靠 ISBN 匹配上的那本会算不出间隔天数
            Long added = byIsbn ? addedAtByIsbn.get(isbn) : addedAt.get(key);
            if (!byIsbn) {
                byIsbn = titleSet.contains(key);
            }
            if (!byIsbn) {
                continue;
            }
            w.converted++;
            if (added != null && wish.createdAt > 0 && added >= wish.createdAt) {
                days.add((added - wish.createdAt) / 86_400_000.0);
            }
        }
        w.medianDaysToBuy = median(toArray(days));
        return w;
    }

    private static String wishKey(String title, String author) {
        return SearchText.normalize(title) + "|" + SearchText.normalize(author);
    }

    // ==================== 借出结构 ====================

    /** 借出去的书集中在哪些分类 / 作者 */
    public static List<Group> lendStructure(List<Book> raw, List<BorrowRecord> records, KeyOf keyOf,
                                            String fallbackName) {
        java.util.Map<Long, Book> byId = new java.util.HashMap<>();
        for (Book book : BookSanitizer.normalizeAll(raw)) {
            byId.put(book.id, book);
        }
        java.util.Map<String, Group> groups = new java.util.LinkedHashMap<>();
        if (records == null) {
            return new ArrayList<>();
        }
        for (BorrowRecord r : records) {
            Book book = byId.get(r.bookId);
            if (book == null) {
                continue;
            }
            String key = keyOf.of(book);
            key = key == null ? "" : key.trim();
            if (key.isEmpty()) {
                key = fallbackName;
            }
            Group g = groups.get(key);
            if (g == null) {
                g = new Group(key);
                groups.put(key, g);
            }
            g.titles++;
            if (book.readStatus == Book.READ_DONE) {
                g.readDone++;
            }
        }
        List<Group> out = new ArrayList<>(groups.values());
        out.sort((x, y) -> y.titles != x.titles ? Integer.compare(y.titles, x.titles)
                : x.name.compareTo(y.name));
        return out;
    }

    /**
     * 系列缺卷数：卷号能解析成整数时，看 1 到最大卷号之间缺几本。
     *
     * <p>卷号是自由文本（「外传」「第一部」都合法），解析不出整数就不猜，返回 0。
     */
    public static int missingVolumes(List<Book> raw, String seriesName) {
        java.util.TreeSet<Integer> vols = new java.util.TreeSet<>();
        for (Book book : BookSanitizer.normalizeAll(raw)) {
            String name = book.seriesName == null ? "" : book.seriesName.trim();
            if (name.isEmpty() || !name.equals(seriesName)) {
                continue;
            }
            try {
                int v = Integer.parseInt(book.seriesVolume.trim());
                if (v > 0) {
                    vols.add(v);
                }
            } catch (NumberFormatException ignored) {
                // 非数字卷号不参与缺卷判断
            }
        }
        if (vols.isEmpty()) {
            return 0;
        }
        int missing = 0;
        for (int v = vols.first(); v <= vols.last(); v++) {
            if (!vols.contains(v)) {
                missing++;
            }
        }
        return missing;
    }

    // ==================== 新增聚合入口 ====================

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

    /** 重复与脏数据（只报有把握的异常，避免把用户正常数据说成脏） */
    public static Anomalies anomalies(List<Book> raw, int todayKey) {
        Anomalies a = new Anomalies();
        List<Book> books = BookSanitizer.normalizeAll(raw);
        java.util.Map<String, Integer> isbnCount = new java.util.HashMap<>();
        java.util.Map<String, Integer> titleCount = new java.util.HashMap<>();
        List<Double> prices = new ArrayList<>();
        for (Book book : books) {
            if (!book.isbn.isEmpty()) {
                int n = isbnCount.merge(book.isbn, 1, Integer::sum);
                if (n == 2) {
                    a.duplicateIsbn.add(book.isbn);
                }
            }
            String title = book.title.trim();
            if (!title.isEmpty()) {
                int n = titleCount.merge(title, 1, Integer::sum);
                if (n == 2) {
                    a.duplicateTitle.add(title);
                }
            }
            if (book.purchasePrice < 0 || book.listPrice < 0) {
                a.negativePrice++;
            }
            if (book.purchasePrice > 0) {
                prices.add(book.purchasePrice);
            }
            int buy = DateKeys.parseKey(book.purchaseDate);
            if (buy > todayKey) {
                a.futureDate++;
            }
            if (book.pages > 0 && (book.pages < 4 || book.pages > 20000)) {
                a.oddPages++;
            }
        }
        double medianPrice = median(toArray(prices));
        if (medianPrice > 0) {
            for (Book book : books) {
                if (book.purchasePrice > medianPrice * 2) {
                    a.overpriced++;
                }
            }
        }
        return a;
    }

    /** 评分分布（平均分按 {@code ratingAvg}，没有平均分的退回整数星） */
    public static Rating ratings(List<Book> raw) {
        Rating r = new Rating();
        java.util.Map<String, double[]> byCategory = new java.util.LinkedHashMap<>();
        double sum = 0;
        for (Book book : BookSanitizer.normalizeAll(raw)) {
            if (!BookFlags.countsAsOwned(book)) {
                continue;
            }
            r.total++;
            double v = book.ratingAvg > 0 ? book.ratingAvg : book.rating;
            if (v <= 0) {
                continue;
            }
            r.rated++;
            sum += v;
            int bucket = Math.min(5, Math.max(1, (int) Math.round(v)));
            r.buckets[bucket]++;
            String cat = book.category.trim();
            if (!cat.isEmpty()) {
                double[] acc = byCategory.computeIfAbsent(cat, k -> new double[2]);
                acc[0] += v;
                acc[1]++;
            }
        }
        r.average = r.rated == 0 ? -1 : sum / r.rated;
        double best = 0;
        for (java.util.Map.Entry<String, double[]> e : byCategory.entrySet()) {
            double[] acc = e.getValue();
            if (acc[1] >= MIN_SAMPLE) {
                double avg = acc[0] / acc[1];
                if (avg > best) {
                    best = avg;
                    r.bestCategory = e.getKey();
                    r.bestCategoryAvg = avg;
                }
            }
        }
        return r;
    }

    /** 按借阅人聚合的可靠性账本，按借出笔数降序 */
    public static List<Lender> lenders(List<BorrowRecord> records, int todayKey) {
        java.util.Map<String, Lender> map = new java.util.LinkedHashMap<>();
        if (records == null) {
            return new ArrayList<>();
        }
        for (BorrowRecord r : records) {
            String name = r.borrower == null || r.borrower.trim().isEmpty()
                    ? "未填借阅人" : r.borrower.trim();
            Lender l = map.computeIfAbsent(name, Lender::new);
            l.loans++;
            int due = DateKeys.parseKey(r.dueDate);
            int ret = DateKeys.parseKey(r.returnDate);
            if (r.status == BorrowRecord.STATUS_RETURNED) {
                if (due > 0 && ret > 0) {
                    l.returnedWithDue++;
                    if (ret <= due) {
                        l.onTime++;
                    } else {
                        l.overdue++;
                    }
                }
            } else {
                l.outNow++;
                if (due > 0 && due < todayKey) {
                    l.overdue++;
                }
            }
        }
        List<Lender> out = new ArrayList<>(map.values());
        out.sort((x, y) -> y.loans != x.loans ? Integer.compare(y.loans, x.loans)
                : x.name.compareTo(y.name));
        return out;
    }

    private static double[] toArray(List<Double> list) {
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
