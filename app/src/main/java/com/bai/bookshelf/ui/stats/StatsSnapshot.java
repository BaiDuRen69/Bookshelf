package com.bai.bookshelf.ui.stats;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.util.NextPick;
import com.bai.bookshelf.util.ReadingModeHealth;
import com.bai.bookshelf.util.StatsHealth;
import com.bai.bookshelf.util.StatsHoldings;
import com.bai.bookshelf.util.StatsLend;
import com.bai.bookshelf.util.StatsPace;
import com.bai.bookshelf.util.StatsSpend;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 统计页一次聚合的全部结果：纯数据、字段全 final、不引用 Context 与视图。
 *
 * <p>没 set 过的字段保持改造前那些 Fragment 字段的初值（对象为 null、计数为 0、
 * {@link #totalReadingDays} 为 -1、{@link #checkInLine} 为空串），
 * 所以 {@link #empty()} 渲染出来的东西和「还没算过」时一致。
 */
public final class StatsSnapshot {

    // ---- 藏书栏 ----
    public final StatsHoldings.Holdings hold;
    public final StatsHealth.Health health;
    public final StatsHealth.Rating rating;
    public final StatsHealth.Anomalies anomalies;
    public final StatsHealth.BorrowOrphans borrowOrphans;
    public final List<StatsHoldings.Group> categoryTree;
    public final List<StatsHoldings.Group> seriesGroups;
    public final List<StatsHoldings.Cohort> cohorts;

    // ---- 阅读栏 ----
    public final StatsPace.Pace pace;
    public final StatsPace.Backlog backlog;
    public final StatsPace.Habit habit;
    public final StatsPace.Stale stale;
    public final StatsPace.BuyRead buyRead;
    public final StatsPace.DropRecovery dropRecovery;
    public final int totalReadingDays;
    public final String checkInLine;
    public final boolean showCheckInLine;
    public final List<NextPick.Pick> nextRanked;
    public final ReadingModeHealth.Report modeReport;
    public final List<StatsHoldings.Share> structure;

    // ---- 购书栏 ----
    public final StatsSpend.Spend spend;
    public final StatsSpend.Relation relation;
    public final StatsSpend.Trend trend;
    public final List<StatsSpend.PeriodDelta> periods;
    public final List<StatsHoldings.Group> channels;
    public final List<StatsHoldings.Group> costByCategory;
    public final StatsHealth.NoteStat noteStat;
    public final StatsHealth.WishStat wishStat;

    // ---- 借阅栏 ----
    public final StatsLend.Lend lend;
    public final List<StatsLend.Lender> lenders;
    public final List<StatsHoldings.Group> lendByCategory;
    public final List<OpenLoan> openLoans;
    public final ReturnTrend.Series returnSeries;
    public final int lendDateUnparsable;

    // ---- 全量输入与总览之最 ----
    public final List<Book> books;
    public final int total;
    public final int finishedThisYear;
    public final int avgDays;
    public final double totalPrice;
    public final double totalSpent;
    public final double totalWords;
    public final Book mostExpensive;
    public final Book mostPages;
    public final Book mostWords;
    public final Book earliest;
    public final Book mostPrintings;
    public final List<Book> recent;
    public final List<Map.Entry<String, Integer>> topAuthors;
    public final List<Map.Entry<String, Integer>> topPublishers;
    public final List<Map.Entry<String, Integer>> topCountries;

    StatsSnapshot(Builder b) {
        this.hold = b.hold;
        this.health = b.health;
        this.rating = b.rating;
        this.anomalies = b.anomalies;
        this.borrowOrphans = b.borrowOrphans;
        this.categoryTree = b.categoryTree;
        this.seriesGroups = b.seriesGroups;
        this.cohorts = b.cohorts;
        this.pace = b.pace;
        this.backlog = b.backlog;
        this.habit = b.habit;
        this.stale = b.stale;
        this.buyRead = b.buyRead;
        this.dropRecovery = b.dropRecovery;
        this.totalReadingDays = b.totalReadingDays;
        this.checkInLine = b.checkInLine;
        this.showCheckInLine = b.showCheckInLine;
        this.nextRanked = b.nextRanked;
        this.modeReport = b.modeReport;
        this.structure = b.structure;
        this.spend = b.spend;
        this.relation = b.relation;
        this.trend = b.trend;
        this.periods = b.periods;
        this.channels = b.channels;
        this.costByCategory = b.costByCategory;
        this.noteStat = b.noteStat;
        this.wishStat = b.wishStat;
        this.lend = b.lend;
        this.lenders = b.lenders;
        this.lendByCategory = b.lendByCategory;
        this.openLoans = b.openLoans;
        this.returnSeries = b.returnSeries;
        this.lendDateUnparsable = b.lendDateUnparsable;
        this.books = b.books;
        this.total = b.total;
        this.finishedThisYear = b.finishedThisYear;
        this.avgDays = b.avgDays;
        this.totalPrice = b.totalPrice;
        this.totalSpent = b.totalSpent;
        this.totalWords = b.totalWords;
        this.mostExpensive = b.mostExpensive;
        this.mostPages = b.mostPages;
        this.mostWords = b.mostWords;
        this.earliest = b.earliest;
        this.mostPrintings = b.mostPrintings;
        this.recent = b.recent;
        this.topAuthors = b.topAuthors;
        this.topPublishers = b.topPublishers;
        this.topCountries = b.topCountries;
    }

    /** 还没算过时的快照：各字段都是改造前 Fragment 字段的初值。 */
    public static StatsSnapshot empty() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 以当前快照为底，只换想改的那几个字段。 */
    public Builder toBuilder() {
        Builder b = new Builder();
        b.hold = hold;
        b.health = health;
        b.rating = rating;
        b.anomalies = anomalies;
        b.borrowOrphans = borrowOrphans;
        b.categoryTree = categoryTree;
        b.seriesGroups = seriesGroups;
        b.cohorts = cohorts;
        b.pace = pace;
        b.backlog = backlog;
        b.habit = habit;
        b.stale = stale;
        b.buyRead = buyRead;
        b.dropRecovery = dropRecovery;
        b.totalReadingDays = totalReadingDays;
        b.checkInLine = checkInLine;
        b.showCheckInLine = showCheckInLine;
        b.nextRanked = nextRanked;
        b.modeReport = modeReport;
        b.structure = structure;
        b.spend = spend;
        b.relation = relation;
        b.trend = trend;
        b.periods = periods;
        b.channels = channels;
        b.costByCategory = costByCategory;
        b.noteStat = noteStat;
        b.wishStat = wishStat;
        b.lend = lend;
        b.lenders = lenders;
        b.lendByCategory = lendByCategory;
        b.openLoans = openLoans;
        b.returnSeries = returnSeries;
        b.lendDateUnparsable = lendDateUnparsable;
        b.books = books;
        b.total = total;
        b.finishedThisYear = finishedThisYear;
        b.avgDays = avgDays;
        b.totalPrice = totalPrice;
        b.totalSpent = totalSpent;
        b.totalWords = totalWords;
        b.mostExpensive = mostExpensive;
        b.mostPages = mostPages;
        b.mostWords = mostWords;
        b.earliest = earliest;
        b.mostPrintings = mostPrintings;
        b.recent = recent;
        b.topAuthors = topAuthors;
        b.topPublishers = topPublishers;
        b.topCountries = topCountries;
        return b;
    }

    /** 链式装配；未 set 的字段保持初值。 */
    public static final class Builder {
        private StatsHoldings.Holdings hold;
        private StatsHealth.Health health;
        private StatsHealth.Rating rating;
        private StatsHealth.Anomalies anomalies;
        private StatsHealth.BorrowOrphans borrowOrphans;
        private List<StatsHoldings.Group> categoryTree;
        private List<StatsHoldings.Group> seriesGroups;
        private List<StatsHoldings.Cohort> cohorts;
        private StatsPace.Pace pace;
        private StatsPace.Backlog backlog;
        private StatsPace.Habit habit;
        private StatsPace.Stale stale;
        private StatsPace.BuyRead buyRead;
        private StatsPace.DropRecovery dropRecovery;
        private int totalReadingDays = -1;
        private String checkInLine = "";
        private boolean showCheckInLine;
        private List<NextPick.Pick> nextRanked = new ArrayList<>();
        private ReadingModeHealth.Report modeReport;
        private List<StatsHoldings.Share> structure;
        private StatsSpend.Spend spend;
        private StatsSpend.Relation relation;
        private StatsSpend.Trend trend;
        private List<StatsSpend.PeriodDelta> periods;
        private List<StatsHoldings.Group> channels;
        private List<StatsHoldings.Group> costByCategory;
        private StatsHealth.NoteStat noteStat;
        private StatsHealth.WishStat wishStat;
        private StatsLend.Lend lend;
        private List<StatsLend.Lender> lenders;
        private List<StatsHoldings.Group> lendByCategory;
        private List<OpenLoan> openLoans;
        private ReturnTrend.Series returnSeries;
        private int lendDateUnparsable;
        private List<Book> books;
        private int total;
        private int finishedThisYear;
        private int avgDays;
        private double totalPrice;
        private double totalSpent;
        private double totalWords;
        private Book mostExpensive;
        private Book mostPages;
        private Book mostWords;
        private Book earliest;
        private Book mostPrintings;
        private List<Book> recent;
        private List<Map.Entry<String, Integer>> topAuthors;
        private List<Map.Entry<String, Integer>> topPublishers;
        private List<Map.Entry<String, Integer>> topCountries;

        Builder() {
        }

        public Builder hold(StatsHoldings.Holdings v) { hold = v; return this; }
        public Builder health(StatsHealth.Health v) { health = v; return this; }
        public Builder rating(StatsHealth.Rating v) { rating = v; return this; }
        public Builder anomalies(StatsHealth.Anomalies v) { anomalies = v; return this; }
        public Builder borrowOrphans(StatsHealth.BorrowOrphans v) { borrowOrphans = v; return this; }
        public Builder categoryTree(List<StatsHoldings.Group> v) { categoryTree = v; return this; }
        public Builder seriesGroups(List<StatsHoldings.Group> v) { seriesGroups = v; return this; }
        public Builder cohorts(List<StatsHoldings.Cohort> v) { cohorts = v; return this; }
        public Builder pace(StatsPace.Pace v) { pace = v; return this; }
        public Builder backlog(StatsPace.Backlog v) { backlog = v; return this; }
        public Builder habit(StatsPace.Habit v) { habit = v; return this; }
        public Builder stale(StatsPace.Stale v) { stale = v; return this; }
        public Builder buyRead(StatsPace.BuyRead v) { buyRead = v; return this; }
        public Builder dropRecovery(StatsPace.DropRecovery v) { dropRecovery = v; return this; }
        public Builder totalReadingDays(int v) { totalReadingDays = v; return this; }
        public Builder checkInLine(String v) { checkInLine = v; return this; }
        public Builder showCheckInLine(boolean v) { showCheckInLine = v; return this; }
        public Builder nextRanked(List<NextPick.Pick> v) { nextRanked = v; return this; }
        public Builder modeReport(ReadingModeHealth.Report v) { modeReport = v; return this; }
        public Builder structure(List<StatsHoldings.Share> v) { structure = v; return this; }
        public Builder spend(StatsSpend.Spend v) { spend = v; return this; }
        public Builder relation(StatsSpend.Relation v) { relation = v; return this; }
        public Builder trend(StatsSpend.Trend v) { trend = v; return this; }
        public Builder periods(List<StatsSpend.PeriodDelta> v) { periods = v; return this; }
        public Builder channels(List<StatsHoldings.Group> v) { channels = v; return this; }
        public Builder costByCategory(List<StatsHoldings.Group> v) { costByCategory = v; return this; }
        public Builder noteStat(StatsHealth.NoteStat v) { noteStat = v; return this; }
        public Builder wishStat(StatsHealth.WishStat v) { wishStat = v; return this; }
        public Builder lend(StatsLend.Lend v) { lend = v; return this; }
        public Builder lenders(List<StatsLend.Lender> v) { lenders = v; return this; }
        public Builder lendByCategory(List<StatsHoldings.Group> v) { lendByCategory = v; return this; }
        public Builder openLoans(List<OpenLoan> v) { openLoans = v; return this; }
        public Builder returnSeries(ReturnTrend.Series v) { returnSeries = v; return this; }
        public Builder lendDateUnparsable(int v) { lendDateUnparsable = v; return this; }
        public Builder books(List<Book> v) { books = v; return this; }
        public Builder total(int v) { total = v; return this; }
        public Builder finishedThisYear(int v) { finishedThisYear = v; return this; }
        public Builder avgDays(int v) { avgDays = v; return this; }
        public Builder totalPrice(double v) { totalPrice = v; return this; }
        public Builder totalSpent(double v) { totalSpent = v; return this; }
        public Builder totalWords(double v) { totalWords = v; return this; }
        public Builder mostExpensive(Book v) { mostExpensive = v; return this; }
        public Builder mostPages(Book v) { mostPages = v; return this; }
        public Builder mostWords(Book v) { mostWords = v; return this; }
        public Builder earliest(Book v) { earliest = v; return this; }
        public Builder mostPrintings(Book v) { mostPrintings = v; return this; }
        public Builder recent(List<Book> v) { recent = v; return this; }
        public Builder topAuthors(List<Map.Entry<String, Integer>> v) { topAuthors = v; return this; }
        public Builder topPublishers(List<Map.Entry<String, Integer>> v) { topPublishers = v; return this; }
        public Builder topCountries(List<Map.Entry<String, Integer>> v) { topCountries = v; return this; }

        public StatsSnapshot build() {
            return new StatsSnapshot(this);
        }
    }
}
