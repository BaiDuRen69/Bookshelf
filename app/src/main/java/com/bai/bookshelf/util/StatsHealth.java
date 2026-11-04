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
 * 数据健康（v5.8.1 从 LibraryStats 拆出）：
 * 元数据完整度与书房健康分、重复/脏数据体检、评分分布、摘抄与想读覆盖。
 */
public final class StatsHealth {

    private StatsHealth() {
    }

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

    /** 重复与脏数据 */
    public static class Anomalies {
        public final List<String> duplicateIsbn = new ArrayList<>();
        public final List<String> duplicateTitle = new ArrayList<>();
        public int negativePrice;
        public int futureDate;
        public int overpriced;
        public int oddPages;
        /** v5.8.31：阅读时长 >365 天且中途有弃读的书（旧单会话模型留下的跨空窗时长） */
        public int longReadWithDrop;

        public int total() {
            return duplicateIsbn.size() + duplicateTitle.size() + negativePrice
                    + futureDate + overpriced + oddPages + longReadWithDrop;
        }
    }

    /** 评分分布与偏好 */    public static class Rating {
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
            StringBuilder sb = new StringBuilder("想读 ").append(Units.titles(wanted));
            if (mindPrice > 0) {
                sb.append(" · 心理价合计 ¥").append(Math.round(mindPrice));
            }
            if (highPriority > 0) {
                sb.append(" · 高优先 ").append(Units.titles(highPriority));
            }
            sb.append(" · 已转藏书 ").append(Units.titles(converted));
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
        a.longReadWithDrop = longReadsWithDrop(books, LONG_READ_DAYS).size();
        return a;
    }

    /** v5.8.31：体检阈值——单场阅读超过一年就算跨弃读空窗的嫌疑时长 */
    public static final int LONG_READ_DAYS = 365;

    /**
     * 找出「单场阅读 >{@code thresholdDays} 天且中途有弃读」的书。
     * 体检计数与一次性修复页共用这一个判定，避免两处口径不一致。
     */
    public static List<Book> longReadsWithDrop(List<Book> raw, int thresholdDays) {
        List<Book> out = new ArrayList<>();
        for (Book book : BookSanitizer.normalizeAll(raw)) {
            if (ReadingRecords.isLongReadAfterDrop(
                    ReadingRecords.load(book), thresholdDays)) {
                out.add(book);
            }
        }
        return out;
    }

    // ==================== 借阅孤儿（v5.8.32） ====================

    /**
     * 借阅数据的四类孤儿。既带计数也带「要改哪些」的清单，
     * 体检文案、修复确认弹窗与修复执行共用同一份，避免列的和改的不是一回事。
     */
    public static final class BorrowOrphans {
        /** ① 标着借出（书或副本）但没有任何未归还记录 */
        public final List<Book> lendBooks = new ArrayList<>();
        public final List<BookCopy> lendCopies = new ArrayList<>();
        /** ② 未归还记录指向的副本行不存在（第 2 本起才有） */
        public final List<BorrowRecord> orphanRecords = new ArrayList<>();
        /** ③ note_images 挂在已删除的借出记录上 */
        public final List<com.bai.bookshelf.data.entity.NoteImage> orphanImages =
                new ArrayList<>();
        /** ④ 借入的 flat 汇总字段与 borrowRecords JSON 推导结果不一致 */
        public final List<Book> outOfSyncBooks = new ArrayList<>();

        public int total() {
            return lendBooks.size() + lendCopies.size() + orphanRecords.size()
                    + orphanImages.size() + outOfSyncBooks.size();
        }

        public boolean isEmpty() {
            return total() == 0;
        }

        /** 体检行文案：只在有问题时给，零问题不占洞察卡一行 */
        public String line() {
            if (isEmpty()) {
                return "";
            }
            return "借阅孤儿 " + total() + " 项（借出无记录 " + (lendBooks.size() + lendCopies.size())
                    + " · 记录无副本 " + orphanRecords.size()
                    + " · 图片孤儿 " + orphanImages.size()
                    + " · 借入汇总不符 " + outOfSyncBooks.size() + "）";
        }
    }

    /**
     * 检测借阅孤儿。四个入参都是「全表快照」，由调用方在 io 线程取好；
     * 本方法只读不改，方便纯逻辑单测。
     */
    public static BorrowOrphans borrowOrphans(List<Book> books, List<BookCopy> copies,
                                              List<BorrowRecord> allRecords,
                                              List<com.bai.bookshelf.data.entity.NoteImage> images) {
        BorrowOrphans o = new BorrowOrphans();
        List<BookCopy> copyList = copies == null ? new ArrayList<>() : copies;
        List<BorrowRecord> recordList = allRecords == null ? new ArrayList<>() : allRecords;

        java.util.Set<String> openKeys = new java.util.HashSet<>();
        java.util.Set<String> copyKeys = new java.util.HashSet<>();
        java.util.Set<Long> recordIds = new java.util.HashSet<>();
        for (BookCopy c : copyList) {
            copyKeys.add(c.bookId + ":" + c.copyIndex);
        }
        for (BorrowRecord r : recordList) {
            recordIds.add(r.id);
            if (r.status == BorrowRecord.STATUS_OUT) {
                openKeys.add(r.bookId + ":" + r.copyIndex);
            }
        }
        for (Book book : BookSanitizer.normalizeAll(books)) {
            if (book.stockStatus == Book.STOCK_LENT && !openKeys.contains(book.id + ":1")) {
                o.lendBooks.add(book);
            }
            if (book.ownType == Book.OWN_BORROW
                    && book.borrowRecords != null && !book.borrowRecords.isEmpty()) {
                List<BorrowSourceRecords.Record> rs =
                        BorrowSourceRecords.parse(book.borrowRecords);
                if (!rs.isEmpty()) {
                    BorrowSourceRecords.Record last = rs.get(0);
                    for (BorrowSourceRecords.Record r : rs) {
                        if (r.seq >= last.seq) {
                            last = r;
                        }
                    }
                    if (!nz(book.borrowStartDate).equals(nz(last.startDate))
                            || !nz(book.borrowDueDate).equals(nz(last.dueDate))
                            || !nz(book.borrowReturnDate).equals(nz(last.returnDate))) {
                        o.outOfSyncBooks.add(book);
                    }
                }
            }
        }
        for (BookCopy c : copyList) {
            if (c.stockStatus == Book.STOCK_LENT
                    && !openKeys.contains(c.bookId + ":" + c.copyIndex)) {
                o.lendCopies.add(c);
            }
        }
        for (BorrowRecord r : recordList) {
            if (r.status == BorrowRecord.STATUS_OUT && r.copyIndex > 1
                    && !copyKeys.contains(r.bookId + ":" + r.copyIndex)) {
                o.orphanRecords.add(r);
            }
        }
        if (images != null) {
            for (com.bai.bookshelf.data.entity.NoteImage img : images) {
                String key = nz(img.ownerKey);
                if (!key.startsWith("borrow:")) {
                    continue;
                }
                try {
                    if (!recordIds.contains(Long.parseLong(key.substring("borrow:".length())))) {
                        o.orphanImages.add(img);
                    }
                } catch (NumberFormatException e) {
                    o.orphanImages.add(img);
                }
            }
        }
        return o;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
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
}
