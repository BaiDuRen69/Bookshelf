package com.bai.bookshelf.stats;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.data.entity.WishBook;
import com.bai.bookshelf.ui.stats.OpenLoan;
import com.bai.bookshelf.ui.stats.ReturnTrend;
import com.bai.bookshelf.ui.stats.StatsSnapshot;
import com.bai.bookshelf.util.BookFlags;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.CheckInHabit;
import com.bai.bookshelf.util.CheckInStore;
import com.bai.bookshelf.util.CheckInUiState;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.LendSpans;
import com.bai.bookshelf.util.LibraryStats;
import com.bai.bookshelf.util.NextPick;
import com.bai.bookshelf.util.ReadDays;
import com.bai.bookshelf.util.ReadingCalendar;
import com.bai.bookshelf.util.ReadingModeHealth;
import com.bai.bookshelf.util.StatsHealth;
import com.bai.bookshelf.util.StatsHoldings;
import com.bai.bookshelf.util.StatsLend;
import com.bai.bookshelf.util.StatsPace;
import com.bai.bookshelf.util.StatsSpend;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 统计页一次聚合的全部口径计算。原样搬自 StatsFragment.computeFrom（v5.9.5 卡 2 第二刀）：
 * 只改「数据怎么来」，不改「数字怎么算」，四栏结果必须逐位相同。
 * <p>
 * 这个类不 import 任何 Android 平台类，读表通过 {@link Source} 注入，所以四栏的关键数字能在纯 JVM
 * 单测里直接断言；线程调度（io / ui）仍由 Fragment 负责。
 */
public final class StatsComputer {

    /** Room 读表的最小切口：只暴露聚合真正用到的那几张全表查询。 */
    public interface Source {
        List<BookCopy> bookCopies();

        List<BorrowRecord> borrowRecords();

        /** 打卡一次取全：习惯栏与阅读日历共用，精确轮按真实日集合铺（v5.9.1） */
        List<CheckIn> checkIns();

        List<Category> categories();

        List<NoteImage> noteImages();

        List<Excerpt> excerpts();

        List<WishBook> wishBooks();
    }

    /** 计算产物：不可变快照 + 这一轮抽中的「下一本读什么」。 */
    public static final class Result {
        public final StatsSnapshot snapshot;
        public final NextPick.Pick nextPick;

        Result(StatsSnapshot snapshot, NextPick.Pick nextPick) {
            this.snapshot = snapshot;
            this.nextPick = nextPick;
        }
    }

    private final Source source;
    private final long nextPickSeed;

    public StatsComputer(Source source, long nextPickSeed) {
        this.source = source;
        this.nextPickSeed = nextPickSeed;
    }

    /** 一次算完四栏要的所有数字。raw 是 LiveData 推进来的全量书籍。 */
    public Result compute(List<Book> raw) {
        List<Book> books = BookSanitizer.normalizeAll(raw);

        // v5.8 口径地基：种 / 册 / 花费统一由 LibraryStats 算，页面只负责摆
        // 卡 2：book_copy 原本在聚合的头和借阅体检处各读一遍同一张表，这里一次读进局部变量
        List<BookCopy> copyAll;
        try {
            copyAll = source.bookCopies();
        } catch (Exception e) {
            copyAll = new ArrayList<>();
        }
        Map<Long, List<BookCopy>> copiesByBook;
        try {
            copiesByBook = LibraryStats.groupCopies(copyAll);
        } catch (Exception e) {
            copiesByBook = new HashMap<>();
        }
        // v5.8.33：借出数据一次建好——阅读天数要扣掉「书在别人手上」的日子。
        // 这里把 loanRecords 的查询提前，借阅栏仍复用同一份，不重复查库。
        List<BorrowRecord> loanRecords;
        try {
            loanRecords = source.borrowRecords();
        } catch (Exception e) {
            loanRecords = new ArrayList<>();
        }
        final Map<Long, List<int[]>> lendSpans =
                LendSpans.byBook(loanRecords, books, copiesByBook);
        final Map<Long, java.util.Set<Integer>> openCopiesByBook = new HashMap<>();
        for (BorrowRecord r : loanRecords) {
            if (r == null || r.status != BorrowRecord.STATUS_OUT) {
                continue;
            }
            java.util.Set<Integer> set = openCopiesByBook.get(r.bookId);
            if (set == null) {
                set = new java.util.HashSet<>();
                openCopiesByBook.put(r.bookId, set);
            }
            set.add(Math.max(1, r.copyIndex));
        }
        final int lendDateUnparsable = LendSpans.countUnparsable(loanRecords);
        final StatsHoldings.Holdings hold =
                StatsHoldings.holdings(books, copiesByBook, openCopiesByBook);

        // ---- v5.8 洞察卡：花费 / 单位成本 / 阅读速度 / 书债 / 归还率 / 健康分 ----
        final StatsSpend.Spend spend = StatsSpend.spend(hold);
        final StatsHealth.Health health = StatsHealth.health(books);
        int todayKey = DateKeys.todayKey();
        int fromKey = DateKeys.shiftDayKey(todayKey, -364);
        List<CheckIn> loadedCheckIns;
        try {
            loadedCheckIns = source.checkIns();
        } catch (Exception e) {
            loadedCheckIns = new ArrayList<>();
        }
        final List<CheckIn> allCheckIns = loadedCheckIns;
        final Map<Long, Map<Integer, List<Integer>>> preciseByBook =
                ReadDays.byBook(allCheckIns);
        // v5.9.5 第 4 项：阅读栏第三格「阅读时长（天）」——阅读日 ∪ 打卡日，去重且只到今天
        final int allReadingDays = ReadingCalendar.distinctDays(
                books, preciseByBook, allCheckIns, todayKey);
        int readingDays = 0;
        for (String day : ReadingCalendar.titlesByDay(books, preciseByBook).keySet()) {
            int k = DateKeys.parseKey(day);
            if (k >= fromKey && k <= todayKey) {
                readingDays++;
            }
        }
        final StatsPace.Pace pace =
                StatsPace.pace(books, copiesByBook, hold, readingDays, lendSpans, preciseByBook);
        // v5.8.19：在读剩余页数与预计读完日，速度沿用上面的中位页/天
        final StatsPace.Backlog backlog =
                StatsPace.backlog(books, pace.medianPagesPerDay, todayKey);
        // v5.8.19：「下一本读什么」的候选（加权排序，抽哪一本在 UI 侧按种子决定）
        final List<NextPick.Pick> nextRanked = NextPick.rank(books, todayKey, preciseByBook);
        final StatsLend.Lend lend = StatsLend.lend(loanRecords, todayKey);

        // 分类树状图与买入年份 cohort（v5.8）
        List<Category> cats;
        try {
            cats = source.categories();
        } catch (Exception e) {
            cats = new ArrayList<>();
        }
        final List<StatsHoldings.Group> categoryTree =
                StatsHoldings.byCategory(books, copiesByBook, cats, "未分类");
        final List<StatsHoldings.Cohort> cohorts = StatsHoldings.cohort(books, todayKey);

        // ---- 总览 ----
        int total = BookFlags.countOwned(books);
        double totalPrice = 0;
        double totalSpent = 0;   // 总花费（购入价求和）
        double totalWords = 0;   // 总字数（解析「28万字」「320千字」等写法）
        int finishedThisYear = 0;
        long readDaysSum = 0;
        int readDaysCount = 0;
        final int currentYear = Calendar.getInstance().get(Calendar.YEAR);
        // ---- 之最 ----
        Book mostExpensive = null, mostPages = null, earliest = null, mostWords = null, mostPrintings = null;
        // ---- 分布 ----
        Map<String, Integer> authorCount = new HashMap<>();
        Map<String, Integer> publisherCount = new HashMap<>();
        Map<String, Integer> countryCount = new HashMap<>();

        for (Book book : books) {
            // v5.7：借阅来源的书不计定价/花费，但字数、阅读状态照常统计
            if (!BookFlags.isBorrowSource(book)) {
                totalPrice += book.listPrice;
                totalSpent += book.purchasePrice;
            }
            totalWords += BookSanitizer.parseWords(book.wordCount);
            // 弃读的结束日期不算「读完」，也不计入年度阅读天数
            if (book.readStatus != Book.READ_DROPPED && !book.readingFinishDate.isEmpty()) {
                int finishKey = DateKeys.parseKey(book.readingFinishDate);
                if (finishKey / 10000 == currentYear) {
                    finishedThisYear++;
                }
                int startKey = DateKeys.parseKey(book.readingStartDate);
                if (startKey > 0 && finishKey >= startKey) {
                    readDaysSum += DateKeys.daysBetween(startKey, finishKey) + 1;
                    readDaysCount++;
                }
            }
            if (!BookFlags.isBorrowSource(book)
                    && (mostExpensive == null || book.listPrice > mostExpensive.listPrice)) {
                mostExpensive = book;
            }
            if (mostPages == null || book.pages > mostPages.pages) {
                mostPages = book;
            }
            // 字数最多（wordCount 是字符串，先解析再比较）
            double words = BookSanitizer.parseWords(book.wordCount);
            if (words > 0 && (mostWords == null || words > BookSanitizer.parseWords(mostWords.wordCount))) {
                mostWords = book;
            }
            // 出版日期是字符串，直接按字典序比较（yyyy 开头时等价于时间序）
            if (!book.publishDate.isEmpty()
                    && (earliest == null || book.publishDate.compareTo(earliest.publishDate) < 0)) {
                earliest = book;
            }
            // 印次最多：printing 格式 yyyy-mm|N，取 | 后的数字
            int printCount = BookSanitizer.parsePrintingCount(book.printing);
            if (printCount > 0 && (mostPrintings == null
                    || printCount > BookSanitizer.parsePrintingCount(mostPrintings.printing))) {
                mostPrintings = book;
            }
            if (!book.author.isEmpty()) {
                authorCount.merge(book.author, 1, Integer::sum);
            }
            if (!book.publisher.isEmpty()) {
                publisherCount.merge(book.publisher, 1, Integer::sum);
            }
            if (book.country != null && !book.country.isEmpty()) {
                countryCount.merge(book.country, 1, Integer::sum);
            }
        }

        final int avgDays = readDaysCount > 0
                ? (int) Math.round((double) readDaysSum / readDaysCount) : 0;

        // v4.31：最近录入的 4 本，用于 hero 封面拼贴
        List<Book> recent = new ArrayList<>();
        for (Book book : books) {
            if (BookFlags.countsAsOwned(book)) {
                recent.add(book);
            }
        }
        recent.sort((x, y) -> Long.compare(y.createdAt, x.createdAt));

        // ---- v5.8 第二批：阅读习惯 / 该处理的书 / 买读比 / 评分 / 数据体检 / 渠道 ----
        List<Integer> dayKeys = new ArrayList<>();
        // 第 ④ 步：有书累计打卡 ≥3 次就以打卡自然日为事实源，修掉
        //「在读未填结束日 → 按天铺满整个区间」的虚高；不足则回退区间估算。
        final boolean habitByCheckIn = CheckInHabit.useCheckIns(allCheckIns);
        if (habitByCheckIn) {
            dayKeys.addAll(CheckInHabit.dayKeys(allCheckIns));
        } else {
            for (String day : ReadingCalendar.titlesByDay(books, preciseByBook).keySet()) {
                int k = DateKeys.parseKey(day);
                if (k > 0) {
                    dayKeys.add(k);
                }
            }
        }
        final StatsPace.Habit habit = StatsPace.Habit.of(dayKeys, currentYear, todayKey);
        habit.fromCheckIns = habitByCheckIn;
        final int[] yearCheckIn = CheckInStore.yearSummary(allCheckIns, currentYear);
        final String checkInLine = CheckInUiState.statsLine(
                currentYear, yearCheckIn, CheckInStore.streakDays(allCheckIns, todayKey));
        final boolean showCheckInLine = yearCheckIn[1] > 0;
        final StatsPace.Stale stale = StatsPace.stale(books, todayKey, 3);
        final StatsHealth.Rating rating = StatsHealth.ratings(books);
        final StatsHealth.Anomalies anomalies = StatsHealth.anomalies(books, todayKey);
        // v5.8.32：借阅孤儿体检。两次全表查询跟其它派生指标一样只在这段聚合里跑
        List<NoteImage> imageAll;
        try {
            imageAll = source.noteImages();
        } catch (Exception e) {
            imageAll = new ArrayList<>();
        }
        final StatsHealth.BorrowOrphans orphans =
                StatsHealth.borrowOrphans(books, copyAll, loanRecords, imageAll);
        // v5.9.1 第六步：精确记录的三项结构体检（纯函数，日集合直接复用上面的全量打卡行）
        final ReadingModeHealth.Report readingModeReport =
                ReadingModeHealth.check(books, allCheckIns);
        int boughtThisYear = 0;
        for (Book book : books) {
            int buy = DateKeys.parseKey(book.purchaseDate);
            if (buy / 10000 == currentYear && !BookFlags.isBorrowSource(book)) {
                boughtThisYear++;
            }
        }
        final StatsPace.BuyRead buyRead =
                StatsPace.BuyRead.of(boughtThisYear, finishedThisYear, hold.unread, hold.titles);
        final List<StatsHoldings.Group> channels =
                StatsHoldings.groupBy(books, copiesByBook, book -> book.sourceChannel, "未填渠道", false);
        // 同比 / 环比、读的结构 vs 藏的结构、借阅人可靠性
        final List<StatsSpend.PeriodDelta> periods =
                StatsSpend.periods(books, loanRecords, todayKey);
        final List<StatsHoldings.Share> structure =
                StatsHoldings.structureCompare(books, copiesByBook, cats);
        final List<StatsLend.Lender> lenders = StatsLend.lenders(loanRecords, todayKey);
        final List<StatsHoldings.Group> costByCategory =
                StatsHoldings.byCategory(books, copiesByBook, cats, "未分类");
        // 第三批：花费趋势、价格相关性、摘抄密度、想读转化、借出结构、系列完成度
        List<Excerpt> excerptAll;
        try {
            excerptAll = source.excerpts();
        } catch (Exception e) {
            excerptAll = new ArrayList<>();
        }
        List<WishBook> wishAll;
        try {
            wishAll = source.wishBooks();
        } catch (Exception e) {
            wishAll = new ArrayList<>();
        }
        final StatsSpend.Trend trend = StatsSpend.trend(books, currentYear, todayKey);
        final StatsSpend.Relation relation = StatsSpend.relations(books);
        final StatsHealth.NoteStat noteStat = StatsHealth.notes(books, excerptAll);
        final StatsHealth.WishStat wishStat = StatsHealth.wishes(wishAll, books);
        final StatsPace.DropRecovery dropRecovery = StatsPace.dropRecovery(books);
        final List<StatsHoldings.Group> lendByCategory =
                StatsLend.lendStructure(books, loanRecords, book -> book.category, "未分类");
        final List<StatsHoldings.Group> seriesGroups =
                StatsHoldings.groupBy(books, copiesByBook, book -> book.seriesName, "未成系列", true);

        // v5.8.3：聚合结果一次性落成不可变快照，切栏时直接复用，不再重复查库/重算
        final StatsSnapshot snap = StatsSnapshot.builder()
                .hold(hold)
                .spend(spend)
                .pace(pace)
                .backlog(backlog)
                .nextRanked(nextRanked)
                .lend(lend)
                .health(health)
                .rating(rating)
                .anomalies(anomalies)
                .habit(habit)
                .totalReadingDays(allReadingDays)
                .checkInLine(checkInLine)
                .showCheckInLine(showCheckInLine)
                .stale(stale)
                .buyRead(buyRead)
                .relation(relation)
                .noteStat(noteStat)
                .wishStat(wishStat)
                .dropRecovery(dropRecovery)
                .trend(trend)
                .channels(channels)
                .costByCategory(costByCategory)
                .structure(structure)
                .seriesGroups(seriesGroups)
                .cohorts(cohorts)
                .lenders(lenders)
                .lendByCategory(lendByCategory)
                .periods(periods)
                .books(books)
                .total(total)
                .finishedThisYear(finishedThisYear)
                .avgDays(avgDays)
                .totalPrice(totalPrice)
                .totalSpent(totalSpent)
                .totalWords(totalWords)
                .mostExpensive(mostExpensive)
                .mostPages(mostPages)
                .mostWords(mostWords)
                .earliest(earliest)
                .mostPrintings(mostPrintings)
                .recent(recent.size() > 4 ? new ArrayList<>(recent.subList(0, 4)) : recent)
                .topAuthors(topN(authorCount, 8))
                .topPublishers(topN(publisherCount, 8))
                .topCountries(topN(countryCount, 8))
                .openLoans(OpenLoan.build(books, loanRecords, todayKey))
                .returnSeries(ReturnTrend.of(loanRecords, todayKey, ReturnTrend.MONTHS))
                .categoryTree(categoryTree)
                .lendDateUnparsable(lendDateUnparsable)
                .borrowOrphans(orphans)
                .modeReport(readingModeReport)
                .build();
        // 种子不变 → 同一批书永远抽到同一本；点「换一本」才换种子
        return new Result(snap, NextPick.chooseFrom(nextRanked, nextPickSeed));
    }

    /** 计数 Map → 按数量倒序的前 N 名 */
    private static List<Map.Entry<String, Integer>> topN(Map<String, Integer> map, int n) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
        list.sort((x, y) -> y.getValue() - x.getValue());
        return list.size() > n ? list.subList(0, n) : list;
    }
}
