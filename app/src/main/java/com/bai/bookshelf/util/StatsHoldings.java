package com.bai.bookshelf.util;

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
 * 藏书总量与结构（v5.8.1 从 LibraryStats 拆出）：
 * 种 / 册双口径、分类聚合与树状图数据、买入年份 → 读完时长、读的结构 vs 藏的结构。
 */
public final class StatsHoldings {

    private StatsHoldings() {
    }

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
}
