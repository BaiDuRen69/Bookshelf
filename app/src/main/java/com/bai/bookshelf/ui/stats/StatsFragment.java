package com.bai.bookshelf.ui.stats;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.util.BookFlags;
import com.bai.bookshelf.databinding.FragmentStatsBinding;
import com.bai.bookshelf.databinding.ItemStatsExtremeBinding;
import com.bai.bookshelf.ui.detail.BookDetailActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.BookText;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ShelfNavigator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 统计页：藏书总览、藏书之最、购书统计入口、作者/出版社/国家分布 Top 8。
 * 数据量小，直接在内存中聚合；分布行统一由 {@link DistRowBuilder} 渲染。
 */
public class StatsFragment extends Fragment {

    private static final int TAB_BOOKS = 0;
    private static final int TAB_READING = 1;
    private static final int TAB_PURCHASE = 2;
    private static final int TAB_LOAN = 3;

    private FragmentStatsBinding b;
    private int lastFinishedThisYear;
    private int lastAvgDays;
    /** 是否已注册 books 的 LiveData */
    private boolean observingBooks;
    /** v5.8.3：统计页「可见才计算、切栏只重画当前栏」所需的状态与缓存 */
    private java.util.List<Book> statsBooks = new java.util.ArrayList<>();
    private boolean statsComputed;
    private boolean statsVisible;
    private boolean heroAnimated;
    /** 本年「还按 N 种吗」弹窗是否已弹过（进程内 + 持久化各一道） */
    private boolean goalPrompted;
    /** 切栏过渡进行中（防止连点） */
    private boolean tabAnimating;
    /** 每个分栏各自的滚动位置 */
    private final int[] tabScrollY = new int[4];
    /** 每栏是否需要重画（v5.8.12：数据没变时切回某栏不再重画 treemap/榜单/图表） */
    private final boolean[] tabDirty = {true, true, true, true};
    private static final long TAB_FADE_OUT_MS = 70;
    private static final long TAB_FADE_IN_MS = 110;
    private com.bai.bookshelf.util.StatsHoldings.Holdings hold;
    private com.bai.bookshelf.util.StatsSpend.Spend spend;
    private com.bai.bookshelf.util.StatsPace.Pace pace;
    /** 在读还剩多少页 / 预计读完日（v5.8.19） */
    private com.bai.bookshelf.util.StatsPace.Backlog backlog;
    /** 「下一本读什么」的候选与当前抽中的一本（v5.8.19） */
    private java.util.List<com.bai.bookshelf.util.NextPick.Pick> nextRanked = new ArrayList<>();
    private com.bai.bookshelf.util.NextPick.Pick nextPick;
    private long nextPickSeed = 1L;
    private com.bai.bookshelf.util.StatsLend.Lend lend;
    private com.bai.bookshelf.util.StatsHealth.Health health;
    private com.bai.bookshelf.util.StatsHealth.Rating rating;
    private com.bai.bookshelf.util.StatsHealth.Anomalies anomalies;
    private com.bai.bookshelf.util.StatsPace.Habit habit;
    private com.bai.bookshelf.util.StatsPace.Stale stale;
    private com.bai.bookshelf.util.StatsPace.BuyRead buyRead;
    private com.bai.bookshelf.util.StatsSpend.Relation relation;
    private com.bai.bookshelf.util.StatsHealth.NoteStat noteStat;
    private com.bai.bookshelf.util.StatsHealth.WishStat wishStat;
    /** v5.8.31：弃读后重读 / 弃读后读完（种口径，从会话序列推） */
    private com.bai.bookshelf.util.StatsPace.DropRecovery dropRecovery;
    private com.bai.bookshelf.util.StatsSpend.Trend trend;
    private java.util.List<com.bai.bookshelf.util.StatsHoldings.Group> channels;
    private java.util.List<com.bai.bookshelf.util.StatsHoldings.Group> costByCategory;
    private java.util.List<com.bai.bookshelf.util.StatsHoldings.Group> seriesGroups;
    private java.util.List<com.bai.bookshelf.util.StatsHoldings.Group> lendByCategory;
    private java.util.List<com.bai.bookshelf.util.StatsHoldings.Share> structure;
    private java.util.List<com.bai.bookshelf.util.StatsHoldings.Cohort> cohorts;
    private java.util.List<com.bai.bookshelf.util.StatsLend.Lender> lenders;
    private java.util.List<com.bai.bookshelf.util.StatsSpend.PeriodDelta> periods;
    private java.util.List<Book> books;
    private java.util.List<OpenLoan> openLoans;
    private int fTotal;
    private int fFinished;
    private int fAvgDays;
    private double fTotalPrice;
    private double fTotalSpent;
    private double fTotalWords;
    private Book fMostExpensive;
    private Book fMostPages;
    private Book fMostWords;
    private Book fEarliest;
    private Book fMostPrintings;
    private java.util.List<Book> fRecent;
    private java.util.List<java.util.Map.Entry<String, Integer>> topAuthors;
    private java.util.List<java.util.Map.Entry<String, Integer>> topPublishers;
    private java.util.List<java.util.Map.Entry<String, Integer>> topCountries;
    private int statsTab = TAB_BOOKS;
    private boolean statsEmpty;
    /** 分类树（一级带二级），树状图下钻用 */
    private java.util.List<com.bai.bookshelf.util.StatsHoldings.Group> categoryTree;
    /** 树状图当前展开的是第几个一级分类；-1 = 一级总览 */
    private int treemapDrill = -1;
    /** 三个分栏各自的洞察文案（v5.8.1：不再三栏复读同一段） */
    private String insightBooks = "";

    /** v5.8.33：体检那行要用——「在读但全部册在外」的书名与日期残缺的借出记录条数 */
    private java.util.List<String> readingOutNames = new ArrayList<>();
    private int lendDateUnparsable;
    private String insightReading = "";
    private String insightPurchase = "";
    private String insightLend = "";

    /** v5.8.32：借阅孤儿（体检文案 + 一次性修复入口共用这一份） */
    private com.bai.bookshelf.util.StatsHealth.BorrowOrphans borrowOrphans;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentStatsBinding.inflate(inflater, container, false);
        // v4.18：Android 15+ 强制 edge-to-edge，给顶部栏加状态栏内边距，
        // 避免「统计」标题被状态栏遮挡（工具栏是固定高度，工具会同时把高度补上）
        InsetsHelper.applyTopInset(b.toolbar);
        setupStatsTabs();
        // 「购书统计」二级页（v2.8，月/年/总统计）
        b.cardReading.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ReadingStatsActivity.class)));
        b.cardReadingCalendar.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ReadingCalendarActivity.class)));
        // v4.28：0 本时的统一空态（与书架同一套插画 + 主按钮）
        b.cardYearReport.setOnClickListener(v ->
                startActivity(new Intent(requireContext(),
                        com.bai.bookshelf.ui.stats.StudyReportActivity.class)));
        b.tvGoalSet.setOnClickListener(v -> showReadingGoalDialog());
        // v5.8.19：「下一本读什么」。换一本只重抽（候选已缓存），开始读直接改状态
        b.btnNextPickReroll.setOnClickListener(v -> {
            // 必须清掉缓存：renderNextPick 只在没结果时才重抽，否则换了种子也显示同一本
            long currentId = nextPick == null ? -1L : nextPick.book.id;
            nextPickSeed = System.nanoTime();
            nextPick = null;
            renderNextPick(currentId);
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        });
        b.btnNextPickStart.setOnClickListener(v -> startPickedBook());
        // v5.8：分类树状图。点一块下钻到它的二级，点下方提示行返回一级
        b.treemapCategories.setOnCellClickListener(index -> {
            if (treemapDrill >= 0 || categoryTree == null || index >= categoryTree.size()) {
                return;
            }
            if (!categoryTree.get(index).children.isEmpty()) {
                treemapDrill = index;
                renderTreemap();
            }
        });
        b.tvTreemapHint.setOnClickListener(v -> {
            treemapDrill = -1;
            renderTreemap();
        });
        b.emptyState.tvEmptyTitle.setText("还没有可统计的书");
        b.emptyState.tvEmptyDesc.setText("录入第一种书后，这里会显示收藏、已读与花费统计");
        b.emptyState.btnEmptyCta.setOnClickListener(v ->
                startActivity(new Intent(requireContext(),
                        com.bai.bookshelf.ui.edit.BookEditActivity.class)));
        // 「全部 ›」进入国家分布二级页面（完整列表）
        b.tvCountriesMore.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), CountryActivity.class)));
        // 「全部 ›」进入出版社分布二级页面（完整列表）
        b.tvPublishersMore.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), PublisherActivity.class)));
        // 「全部 ›」进入作者分布二级页面（完整列表）
        b.tvAuthorsMore.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AuthorActivity.class)));
        // v4.3：改为 Room 响应式观察（与 v4.2 书架页同模式）——注册即推送首次数据，
        // 之后 books 表任何变化自动重算，切 Tab 回来无需手动刷新，视图重建也不会空白
        // v5.8.3：库没就绪时排队，等开库成功再注册（冷启动时本页早于开库完成）
        BookshelfApp.get().whenDbReady(this::observeBooks);
        return b.getRoot();
    }

    /** 统计分栏：藏书 / 阅读 / 购书 */
    private void setupStatsTabs() {
        b.tabBooks.setOnClickListener(v -> selectStatsTab(TAB_BOOKS));
        b.tabReading.setOnClickListener(v -> selectStatsTab(TAB_READING));
        b.tabPurchase.setOnClickListener(v -> selectStatsTab(TAB_PURCHASE));
        b.tabLoan.setOnClickListener(v -> selectStatsTab(TAB_LOAN));
        applyStatsTab();
    }

    /**
     * 切栏（v5.8.5）：fade-through 过渡 + 每栏各自记住滚动位置。
     *
     * <p>以前是 visibility 闪切且每次 scrollTo(0,0)，从购书翻回藏书还得重新往下滚。
     */
    private void selectStatsTab(int tab) {
        if (statsTab == tab || b == null || tabAnimating) {
            return;
        }
        final android.view.View content = b.scrollStats;
        tabScrollY[statsTab] = content.getScrollY();
        tabAnimating = true;
        content.animate().alpha(0f).setDuration(TAB_FADE_OUT_MS).withEndAction(() -> {
            statsTab = tab;
            applyStatsTab();
            ensureComputed();
            content.scrollTo(0, tabScrollY[tab]);
            content.setTranslationY(getResources().getDisplayMetrics().density * 6);
            content.animate().alpha(1f).translationY(0f)
                    .setDuration(TAB_FADE_IN_MS)
                    .withEndAction(() -> {
                        tabAnimating = false;
                        // v5.8.32：角标直达要等淡入结束、卡片真的可见了再滚
                        consumePendingOverdueFocus();
                    })
                    .start();
        }).start();
    }

    /** 角标直达的待办：视图没就绪（冷启动/刚挂载）时先记着，等 onResume / 切栏结束再消费 */
    private boolean pendingOverdueFocus = false;

    /** MainActivity 点角标 / 点到期通知调这里：切到借阅栏并滚到在借清单（v5.8.32） */
    public void requestOverdueFocus() {
        pendingOverdueFocus = true;
        if (b == null) {
            return;
        }
        if (statsTab == TAB_LOAN) {
            consumePendingOverdueFocus();
        } else {
            selectStatsTab(TAB_LOAN);
        }
    }

    private void consumePendingOverdueFocus() {
        if (!pendingOverdueFocus || b == null || statsTab != TAB_LOAN || statsEmpty) {
            return;
        }
        pendingOverdueFocus = false;
        ensureComputed();
        final View target = b.cardOpenLoans;
        b.scrollStats.post(() -> {
            if (!target.isShown()) {
                return;
            }
            android.graphics.Rect rect = new android.graphics.Rect();
            b.scrollStats.offsetDescendantRectToMyCoords(target, rect);
            int lift = (int) (getResources().getDisplayMetrics().density * 8);
            b.scrollStats.smoothScrollTo(0, Math.max(0, rect.top - lift));
        });
    }

    private void applyStatsTab() {
        if (b == null) {
            return;
        }
        boolean empty = statsEmpty;
        b.llStatsTabs.setVisibility(empty ? View.GONE : View.VISIBLE);
        b.emptyStateContainer.setVisibility(empty ? View.VISIBLE : View.GONE);
        boolean books = !empty && statsTab == TAB_BOOKS;
        boolean reading = !empty && statsTab == TAB_READING;
        boolean purchase = !empty && statsTab == TAB_PURCHASE;
        boolean loan = !empty && statsTab == TAB_LOAN;
        setCardVisible(b.cardStatsHero, books);
        setCardVisible(b.cardTreemap, books);
        setCardVisible(b.cardCohort, books);
        setCardVisible(b.sectionExtremes, books);
        setCardVisible(b.cardAuthors, books);
        setCardVisible(b.cardPublishers, books);
        setCardVisible(b.cardCountries, books);
        setCardVisible(b.cardSeries, books);
        setCardVisible(b.cardReadingGoal, reading);
        setCardVisible(b.cardReadingCalendar, reading);
        setCardVisible(b.cardHabit, reading);
        setCardVisible(b.cardNextPick, reading);
        setCardVisible(b.cardStructure, reading);
        setCardVisible(b.cardYearReport, reading);
        setCardVisible(b.cardPurchaseOverview, purchase);
        setCardVisible(b.cardReading, purchase);
        setCardVisible(b.cardChannel, purchase);
        setCardVisible(b.cardTrend, purchase);
        setCardVisible(b.cardOpenLoans, loan);
        setCardVisible(b.cardBorrowers, loan);
        // 洞察卡跟着分栏走：藏书看健康/结构，阅读看书债/速度，购书看花费/渠道
        setCardVisible(b.cardInsight, !empty);
        applyInsightForTab();
        styleStatsTab(b.tabBooks, statsTab == TAB_BOOKS);
        styleStatsTab(b.tabReading, statsTab == TAB_READING);
        styleStatsTab(b.tabPurchase, statsTab == TAB_PURCHASE);
        styleStatsTab(b.tabLoan, statsTab == TAB_LOAN);
        if (statsComputed && tabDirty[statsTab]) {
            renderTab(statsTab);
            tabDirty[statsTab] = false;
        } else {
            applyInsightForTab();
        }
    }

    private void applyInsightForTab() {
        if (b == null) {
            return;
        }
        // v5.8.14：样本不足时补一张小图和一句「再添几本」，而不是只有「数据不足」
        boolean lowSample = !statsEmpty && fTotal > 0
                && fTotal < com.bai.bookshelf.util.LibraryStats.MIN_SAMPLE;
        b.rowInsightLowSample.setVisibility(lowSample ? View.VISIBLE : View.GONE);
        if (lowSample) {
            int need = com.bai.bookshelf.util.LibraryStats.MIN_SAMPLE - fTotal;
            b.tvInsightLowSample.setText("再添 " + need + " 本，样本够了就能给出趋势");
        }
        switch (statsTab) {
            case TAB_READING:
                b.tvInsight.setText(insightReading);
                break;
            case TAB_PURCHASE:
                b.tvInsight.setText(insightPurchase);
                break;
            case TAB_LOAN:
                b.tvInsight.setText(insightLend);
                break;
            default:
                b.tvInsight.setText(insightBooks);
        }
    }

    private static void setCardVisible(View view, boolean visible) {
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void styleStatsTab(TextView tv, boolean selected) {
        int primary = com.bai.bookshelf.util.ThemeManager.resolveColor(
                requireContext(), com.bai.bookshelf.util.ThemeManager.ATTR_COLOR_PRIMARY);
        int normal = com.bai.bookshelf.util.ThemeManager.resolveColor(
                requireContext(), R.attr.on_surface_variant);
        tv.setBackgroundResource(selected ? R.drawable.bg_seg_selected_inset : 0);
        tv.setTextColor(selected ? primary : normal);
        tv.setTypeface(null, selected
                ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        // 读屏要靠这个状态播报「已选中」，光改颜色它不知道
        tv.setSelected(selected);
    }

    /** 阅读目标卡（v4.44，v5.8.4 改为跨年继承 + 1 月问一次） */
    private void bindReadingGoal(int finished, int avgDays) {
        if (b == null) {
            return;
        }
        // 跨年不再重置成 24，而是沿用去年的目标（ReadingGoal.current 内部做滚动）
        int goal = com.bai.bookshelf.util.ReadingGoal.current(requireContext());
        b.tvGoalSummary.setText("今年读完 " + finished + " 种 / 目标 " + goal + " 种");
        b.pbGoal.setProgress(Math.min(100, finished * 100 / goal));
        // v5.8：按当前速度给预测，落后就说清剩下几个月每月要读几本
        com.bai.bookshelf.util.StatsPace.Goal g = com.bai.bookshelf.util.StatsPace.Goal.of(
                finished, goal,
                java.util.Calendar.getInstance().get(java.util.Calendar.MONTH) + 1);
        b.tvGoalStreak.setText(g.line() + (avgDays > 0
                ? " · 平均 " + avgDays + " 天读完一种"
                : " · 把状态改成「在读 / 已读」，这里会自动记录开始与读完日期"));
        maybePromptGoalForNewYear(goal);
    }

    /** 1 月第一次打开统计页时问一句「今年还按 N 种吗」，一年只问一次 */
    private void maybePromptGoalForNewYear(int goal) {
        if (!isAdded() || goalPrompted) {
            return;
        }
        if (!com.bai.bookshelf.util.ReadingGoal.needsJanuaryPrompt(requireContext())) {
            return;
        }
        goalPrompted = true;
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("新的一年")
                .setMessage("去年定了 " + goal + " 种，今年还按这个来吗？")
                .setNegativeButton("改一下", (d, w) -> {
                    com.bai.bookshelf.util.ReadingGoal.markPrompted(requireContext());
                    showReadingGoalDialog();
                })
                .setPositiveButton("就按 " + goal + " 种", (d, w) ->
                        com.bai.bookshelf.util.ReadingGoal.markPrompted(requireContext()))
                .show();
    }

    private void showReadingGoalDialog() {
        ReadingGoalDialog.show(requireContext(),
                () -> bindReadingGoal(lastFinishedThisYear, lastAvgDays));
    }

    /** Room 推送回调（主线程）：books 已是最新快照，交给后台聚合 */
    /** 库就绪后注册 books 响应式观察 */
    private void observeBooks() {
        if (b == null || !isAdded() || observingBooks) {
            return;
        }
        try {
            observingBooks = true;
            BookshelfApp.get().db().bookDao().getAllLive()
                    .observe(getViewLifecycleOwner(), this::onBooksPushed);
        } catch (RuntimeException e) {
            observingBooks = false;
        }
    }

    private void onBooksPushed(java.util.List<Book> pushed) {
        statsBooks = pushed;
        statsComputed = false;
        if (statsVisible && b != null) {
            computeFrom(statsBooks);
        } else {
            // 不可见时不查库、不聚合：只把空态定下来，等切到统计页再算
            statsEmpty = com.bai.bookshelf.util.BookFlags.countOwned(pushed) == 0;
            applyStatsTab();
        }
    }

    /** 可见且没算过才算（数据变化后 Mark 成未计算） */
    private void ensureComputed() {
        if (!statsComputed && b != null) {
            computeFrom(statsBooks);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        statsVisible = !isHidden();
        if (statsVisible) {
            ensureComputed();
            consumePendingOverdueFocus();
        }
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        statsVisible = !hidden;
        if (!hidden) {
            ensureComputed();
            consumePendingOverdueFocus();
        }
    }

    /** 后台线程聚合数据，回主线程渲染（books 由 LiveData 推送给出） */
    private void computeFrom(java.util.List<Book> raw) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Book> books = BookSanitizer.normalizeAll(raw);

            // v5.8 口径地基：种 / 册 / 花费统一由 LibraryStats 算，页面只负责摆
            java.util.Map<Long, java.util.List<com.bai.bookshelf.data.entity.BookCopy>> copiesByBook;
            try {
                copiesByBook = com.bai.bookshelf.util.LibraryStats.groupCopies(
                        BookshelfApp.get().db().bookCopyDao().getAll());
            } catch (Exception e) {
                copiesByBook = new java.util.HashMap<>();
            }
            // v5.8.33：借出数据一次建好——阅读天数要扣掉「书在别人手上」的日子。
            // 这里把 loanRecords 的查询提前，下面的借阅栏仍复用同一份，不重复查库。
            java.util.List<com.bai.bookshelf.data.entity.BorrowRecord> loanRecords;
            try {
                loanRecords = BookshelfApp.get().db().borrowRecordDao().getAll();
            } catch (Exception e) {
                loanRecords = new ArrayList<>();
            }
            final java.util.Map<Long, List<int[]>> lendSpans =
                    com.bai.bookshelf.util.LendSpans.byBook(loanRecords, books, copiesByBook);
            final java.util.Map<Long, java.util.Set<Integer>> openCopiesByBook =
                    new java.util.HashMap<>();
            for (com.bai.bookshelf.data.entity.BorrowRecord r : loanRecords) {
                if (r == null || r.status != com.bai.bookshelf.data.entity.BorrowRecord.STATUS_OUT) {
                    continue;
                }
                java.util.Set<Integer> set = openCopiesByBook.get(r.bookId);
                if (set == null) {
                    set = new java.util.HashSet<>();
                    openCopiesByBook.put(r.bookId, set);
                }
                set.add(Math.max(1, r.copyIndex));
            }
            lendDateUnparsable = com.bai.bookshelf.util.LendSpans.countUnparsable(loanRecords);
            final com.bai.bookshelf.util.StatsHoldings.Holdings hold =
                    com.bai.bookshelf.util.StatsHoldings.holdings(
                            books, copiesByBook, openCopiesByBook);

            // ---- v5.8 洞察卡：花费 / 单位成本 / 阅读速度 / 书债 / 归还率 / 健康分 ----
            final com.bai.bookshelf.util.StatsSpend.Spend spend =
                    com.bai.bookshelf.util.StatsSpend.spend(hold);
            final com.bai.bookshelf.util.StatsHealth.Health health =
                    com.bai.bookshelf.util.StatsHealth.health(books);
            int todayKey = com.bai.bookshelf.util.DateKeys.todayKey();
            int fromKey = com.bai.bookshelf.util.DateKeys.shiftDayKey(todayKey, -364);
            int readingDays = 0;
            for (String day : com.bai.bookshelf.util.ReadingCalendar.titlesByDay(books).keySet()) {
                int k = com.bai.bookshelf.util.DateKeys.parseKey(day);
                if (k >= fromKey && k <= todayKey) {
                    readingDays++;
                }
            }
            final com.bai.bookshelf.util.StatsPace.Pace pace =
                    com.bai.bookshelf.util.StatsPace.pace(
                            books, copiesByBook, hold, readingDays, lendSpans);
            // v5.8.19：在读剩余页数与预计读完日，速度沿用上面的中位页/天
            final com.bai.bookshelf.util.StatsPace.Backlog backlog =
                    com.bai.bookshelf.util.StatsPace.backlog(books,
                            pace.medianPagesPerDay, todayKey);
            // v5.8.19：「下一本读什么」的候选（加权排序，抽哪一本在 UI 侧按种子决定）
            final java.util.List<com.bai.bookshelf.util.NextPick.Pick> nextRanked =
                    com.bai.bookshelf.util.NextPick.rank(books, todayKey);
            final com.bai.bookshelf.util.StatsLend.Lend lend =
                    com.bai.bookshelf.util.StatsLend.lend(loanRecords, todayKey);

            // 分类树状图与买入年份 cohort（v5.8）
            java.util.List<com.bai.bookshelf.data.entity.Category> cats;
            try {
                cats = BookshelfApp.get().db().categoryDao().getAll();
            } catch (Exception e) {
                cats = new ArrayList<>();
            }
            categoryTree = com.bai.bookshelf.util.StatsHoldings.byCategory(
                    books, copiesByBook, cats, "未分类");
            final java.util.List<com.bai.bookshelf.util.StatsHoldings.Cohort> cohorts =
                    com.bai.bookshelf.util.StatsHoldings.cohort(books, todayKey);

            // ---- 总览 ----
            int total = BookFlags.countOwned(books);
            double totalPrice = 0;
            double totalSpent = 0;   // 总花费（购入价求和）
            double totalWords = 0;   // 总字数（解析「28万字」「320千字」等写法）
            int finishedThisYear = 0;
            long readDaysSum = 0;
            int readDaysCount = 0;
            final int currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
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
                if (book.readStatus != Book.READ_DROPPED
                        && !book.readingFinishDate.isEmpty()) {
                    int finishKey = com.bai.bookshelf.util.BorrowReminder
                            .parseKey(book.readingFinishDate);
                    if (finishKey / 10000 == currentYear) {
                        finishedThisYear++;
                    }
                    int startKey = com.bai.bookshelf.util.BorrowReminder
                            .parseKey(book.readingStartDate);
                    if (startKey > 0 && finishKey >= startKey) {
                        readDaysSum += com.bai.bookshelf.util.BorrowReminder
                                .daysBetween(startKey, finishKey) + 1;
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

            fFinished = finishedThisYear;
            fAvgDays = readDaysCount > 0
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
            java.util.List<Integer> dayKeys = new ArrayList<>();
            for (String day : com.bai.bookshelf.util.ReadingCalendar.titlesByDay(books).keySet()) {
                int k = com.bai.bookshelf.util.DateKeys.parseKey(day);
                if (k > 0) {
                    dayKeys.add(k);
                }
            }
            final com.bai.bookshelf.util.StatsPace.Habit habit =
                    com.bai.bookshelf.util.StatsPace.Habit.of(dayKeys, currentYear, todayKey);
            final com.bai.bookshelf.util.StatsPace.Stale stale =
                    com.bai.bookshelf.util.StatsPace.stale(books, todayKey, 3);
            final com.bai.bookshelf.util.StatsHealth.Rating rating =
                    com.bai.bookshelf.util.StatsHealth.ratings(books);
            final com.bai.bookshelf.util.StatsHealth.Anomalies anomalies =
                    com.bai.bookshelf.util.StatsHealth.anomalies(books, todayKey);
            // v5.8.32：借阅孤儿体检。两次全表查询跟其它派生指标一样只在这段聚合里跑
            java.util.List<com.bai.bookshelf.data.entity.BookCopy> copyAll;
            try {
                copyAll = BookshelfApp.get().db().bookCopyDao().getAll();
            } catch (Exception e) {
                copyAll = new ArrayList<>();
            }
            java.util.List<com.bai.bookshelf.data.entity.NoteImage> imageAll;
            try {
                imageAll = BookshelfApp.get().db().noteImageDao().getAll();
            } catch (Exception e) {
                imageAll = new ArrayList<>();
            }
            final com.bai.bookshelf.util.StatsHealth.BorrowOrphans orphans =
                    com.bai.bookshelf.util.StatsHealth.borrowOrphans(
                            books, copyAll, loanRecords, imageAll);
            this.borrowOrphans = orphans;
            int boughtThisYear = 0;
            for (Book book : books) {
                int buy = com.bai.bookshelf.util.DateKeys.parseKey(book.purchaseDate);
                if (buy / 10000 == currentYear && !BookFlags.isBorrowSource(book)) {
                    boughtThisYear++;
                }
            }
            final com.bai.bookshelf.util.StatsPace.BuyRead buyRead =
                    com.bai.bookshelf.util.StatsPace.BuyRead.of(
                            boughtThisYear, finishedThisYear, hold.unread, hold.titles);
            final List<com.bai.bookshelf.util.StatsHoldings.Group> channels =
                    com.bai.bookshelf.util.StatsHoldings.groupBy(books, copiesByBook,
                            book -> book.sourceChannel, "未填渠道", false);
            // 同比 / 环比、读的结构 vs 藏的结构、借阅人可靠性
            final java.util.List<com.bai.bookshelf.util.StatsSpend.PeriodDelta> periods =
                    com.bai.bookshelf.util.StatsSpend.periods(books, loanRecords, todayKey);
            final java.util.List<com.bai.bookshelf.util.StatsHoldings.Share> structure =
                    com.bai.bookshelf.util.StatsHoldings.structureCompare(books, copiesByBook, cats);
            final java.util.List<com.bai.bookshelf.util.StatsLend.Lender> lenders =
                    com.bai.bookshelf.util.StatsLend.lenders(loanRecords, todayKey);
            final java.util.List<com.bai.bookshelf.util.StatsHoldings.Group> costByCategory =
                    com.bai.bookshelf.util.StatsHoldings.byCategory(books, copiesByBook, cats, "未分类");
            // 第三批：花费趋势、价格相关性、摘抄密度、想读转化、借出结构、系列完成度
            java.util.List<com.bai.bookshelf.data.entity.Excerpt> excerptAll;
            try {
                excerptAll = BookshelfApp.get().db().excerptDao().getAll();
            } catch (Exception e) {
                excerptAll = new ArrayList<>();
            }
            java.util.List<com.bai.bookshelf.data.entity.WishBook> wishAll;
            try {
                wishAll = BookshelfApp.get().db().wishBookDao().getAll();
            } catch (Exception e) {
                wishAll = new ArrayList<>();
            }
            final com.bai.bookshelf.util.StatsSpend.Trend trend =
                    com.bai.bookshelf.util.StatsSpend.trend(books, currentYear, todayKey);
            final com.bai.bookshelf.util.StatsSpend.Relation relation =
                    com.bai.bookshelf.util.StatsSpend.relations(books);
            final com.bai.bookshelf.util.StatsHealth.NoteStat noteStat =
                    com.bai.bookshelf.util.StatsHealth.notes(books, excerptAll);
            final com.bai.bookshelf.util.StatsHealth.WishStat wishStat =
                    com.bai.bookshelf.util.StatsHealth.wishes(wishAll, books);
            final com.bai.bookshelf.util.StatsPace.DropRecovery dropRecovery =
                    com.bai.bookshelf.util.StatsPace.dropRecovery(books);
            final java.util.List<com.bai.bookshelf.util.StatsHoldings.Group> lendByCategory =
                    com.bai.bookshelf.util.StatsLend.lendStructure(books, loanRecords,
                            book -> book.category, "未分类");
            final java.util.List<com.bai.bookshelf.util.StatsHoldings.Group> seriesGroups =
                    com.bai.bookshelf.util.StatsHoldings.groupBy(books, copiesByBook,
                            book -> book.seriesName, "未成系列", true);

            // v5.8.3：聚合结果落到字段里，切栏时直接复用，不再重复查库/重算
            this.hold = hold;
            this.spend = spend;
            this.pace = pace;
            this.backlog = backlog;
            this.nextRanked = nextRanked;
            // 种子不变 → 同一批书永远抽到同一本；点「换一本」才换种子
            this.nextPick = com.bai.bookshelf.util.NextPick.chooseFrom(nextRanked, nextPickSeed);
            this.lend = lend;
            this.health = health;
            this.rating = rating;
            this.anomalies = anomalies;
            this.habit = habit;
            this.stale = stale;
            this.buyRead = buyRead;
            this.relation = relation;
            this.noteStat = noteStat;
            this.wishStat = wishStat;
            this.dropRecovery = dropRecovery;
            this.channels = channels;
            this.costByCategory = costByCategory;
            this.structure = structure;
            this.trend = trend;
            this.seriesGroups = seriesGroups;
            this.cohorts = cohorts;
            this.lenders = lenders;
            this.lendByCategory = lendByCategory;
            this.periods = periods;
            this.books = books;
            this.fTotal = total;
            this.fTotalPrice = totalPrice;
            this.fTotalSpent = totalSpent;
            this.fTotalWords = totalWords;
            this.fMostExpensive = mostExpensive;
            this.fMostPages = mostPages;
            this.fMostWords = mostWords;
            this.fEarliest = earliest;
            this.fMostPrintings = mostPrintings;
            this.fRecent = recent.size() > 4 ? new ArrayList<>(recent.subList(0, 4)) : recent;
            this.topAuthors = topN(authorCount, 8);
            this.topPublishers = topN(publisherCount, 8);
            this.topCountries = topN(countryCount, 8);
            this.openLoans = buildOpenLoans(books, loanRecords, todayKey);

            app.ui(() -> {
                if (b == null) {
                    return;
                }
                statsComputed = true;
                // 数据变了：四栏都标脏，下次显示时各自重画一次
                java.util.Arrays.fill(tabDirty, true);
                statsEmpty = fTotal == 0;
                lastFinishedThisYear = fFinished;
                lastAvgDays = fAvgDays;
                bindReadingGoal(fFinished, fAvgDays);
                applyStatsTab();
            });
        });
    }

    /** 按分栏渲染：切栏只重画这一栏，另外三栏的视图不动（v5.8.3） */
    private void renderTab(int tab) {
        if (b == null) {
            return;
        }
        if (tab == TAB_BOOKS) {
            renderBooksTab();
        } else if (tab == TAB_READING) {
            renderReadingTab();
        } else if (tab == TAB_PURCHASE) {
            renderPurchaseTab();
        } else {
            renderLendTab();
        }
        applyInsightForTab();
    }

    private void renderBooksTab() {
        if (hold == null) {
            return;
        }
        // v4.30：藏书总数 600ms count-up，只在第一次进本页时跑
        if (!heroAnimated) {
            heroAnimated = true;
            android.animation.ValueAnimator countUp =
                    android.animation.ValueAnimator.ofInt(0, fTotal);
            countUp.setDuration(600);
            countUp.addUpdateListener(a ->
                    b.tvStatsTotal.setText(String.valueOf(a.getAnimatedValue())));
            countUp.start();
        } else {
            b.tvStatsTotal.setText(String.valueOf(fTotal));
        }
        b.tvStatsReadDone.setText(String.valueOf(hold.readDone));
        b.tvStatsReading.setText(String.valueOf(hold.reading));
        // v5.8.33：在读里有多少本正借在别人手上（M=0 时整段不显示）
        b.tvStatsReadingOut.setVisibility(hold.readingOut > 0 ? View.VISIBLE : View.GONE);
        b.tvStatsReadingOut.setText("其中 " + hold.readingOut + " 种在外借中");
        readingOutNames = hold.readingFullyOutNames;
        b.tvStatsUnread.setText(String.valueOf(hold.unread));
        b.tvStatsDropped.setText(String.valueOf(hold.dropped));
        // 种是主数字，册 / 在外 / 遗失按册另说，避免「19 册」这种既错又没信息量
        StringBuilder copies = new StringBuilder("共 ")
                .append(hold.copies).append(" 册");
        if (hold.copiesLent > 0) {
            copies.append(" · 在外 ").append(hold.copiesLent);
        }
        if (hold.copiesLost > 0) {
            copies.append(" · 遗失 ").append(hold.copiesLost);
        }
        b.tvStatsCopies.setText(copies);
        insightBooks = booksInsight(hold, health, rating, anomalies,
                readingOutNames, lendDateUnparsable);
        renderTreemap();
        renderCohort(cohorts);
        renderSeries(seriesGroups, books);
        buildExtremes(fMostExpensive, fMostPages, fMostWords, fEarliest, fMostPrintings);
        bindCoverFan(fRecent);
        DistRowBuilder rows = distRows();
        rows.fill(b.containerAuthors, topAuthors, DistRowBuilder.emptyHint("作者", null));
        rows.fill(b.containerPublishers, topPublishers, DistRowBuilder.emptyHint("出版社", null));
        rows.fill(b.containerCountries, topCountries, DistRowBuilder.emptyHint("国家/地区", null));
    }

    private void renderReadingTab() {
        if (pace == null) {
            return;
        }
        insightReading = readingInsight(pace, backlog, noteStat, dropRecovery);
        b.tvHabit.setText(habitText(habit, stale, buyRead));
        renderNextPick();
        renderStructure(structure);
    }

    /** 「下一本读什么」卡片（v5.8.19）：抽中的书 + 为什么是它 + 一键开读 */
    private void renderNextPick() {
        renderNextPick(-1L);
    }

    /**
     * @param excludeId 「换一本」时要避开的当前这本；负数表示不排除
     */
    private void renderNextPick(long excludeId) {
        if (b == null) {
            return;
        }
        com.bai.bookshelf.util.NextPick.Pick pick = nextPick;
        if (pick == null && !nextRanked.isEmpty()) {
            pick = com.bai.bookshelf.util.NextPick.chooseFrom(nextRanked, nextPickSeed, excludeId);
            nextPick = pick;
        }
        boolean has = pick != null;
        b.tvNextPick.setText(has
                ? com.bai.bookshelf.util.BookText.quotedTitle(pick.book.title)
                        + (pick.book.author == null || pick.book.author.isEmpty()
                        ? "" : " · " + pick.book.author)
                : "没有待读的书了 · 想读清单里的可以先加进书架");
        b.tvNextPickReason.setText(has ? pick.reasonLine() : "");
        b.btnNextPickReroll.setVisibility(has && nextRanked.size() > 1 ? View.VISIBLE : View.GONE);
        b.btnNextPickStart.setVisibility(has ? View.VISIBLE : View.GONE);
    }

    /** 一键把抽中的这本改成在读（8 秒内可撤销） */
    private void startPickedBook() {
        final com.bai.bookshelf.util.NextPick.Pick pick = nextPick;
        if (pick == null || b == null) {
            return;
        }
        final com.bai.bookshelf.data.entity.Book book = pick.book;
        final com.bai.bookshelf.data.entity.Book before =
                com.bai.bookshelf.util.BookSnapshot.copy(book);
        final BookshelfApp app = BookshelfApp.get();
        final android.view.View anchor = b.getRoot();
        app.io(() -> {
            // v5.8.31：「开始阅读」走会话状态机——已经在读就幂等返回，不再改写别场记录
            com.bai.bookshelf.util.ReadingFlow.start(book,
                    com.bai.bookshelf.util.DateKeys.formatKey(
                            com.bai.bookshelf.util.DateKeys.todayKey()));
            book.updatedAt = System.currentTimeMillis();
            book.dirty = true;
            app.db().bookDao().update(book);
            app.ui(() -> {
                // 改状态会触发 Room 推送，统计随之重算；这里先清掉当前选择，避免指向已读的书
                nextPick = null;
                renderNextPick();
                com.bai.bookshelf.util.UndoBar.offer(anchor,
                        "开始读" + com.bai.bookshelf.util.BookText.quotedTitle(book.title),
                        before == null ? null : () -> app.io(() -> {
                            app.db().bookDao().update(before);
                            app.ui(() -> renderNextPick());
                        }), null);
            });
        });
    }

    private void renderPurchaseTab() {
        if (spend == null) {
            return;
        }
        insightPurchase = purchaseInsight(spend, relation, periods, wishStat);
        b.tvStatsPrice.setText(String.format(Locale.getDefault(), "¥%.2f", fTotalPrice));
        b.tvStatsSpent.setText(String.format(Locale.getDefault(), "¥%.2f", fTotalSpent));
        b.tvStatsWords.setText(String.format(Locale.getDefault(),
                "%.1f 万字", fTotalWords / 10000.0));
        renderChannels(channels);
        renderCostRows(costByCategory);
        renderTrend(trend);
    }

    private void renderLendTab() {
        if (lend == null) {
            return;
        }
        insightLend = lendInsight(lend, lenders, lendByCategory, borrowOrphans);
        renderBorrowers(lenders);
        renderOpenLoans();
        bindOrphanRepair();
    }

    /**
     * 借阅孤儿的修复入口（v5.8.32）：查出问题才出现，点「修复」先逐条列出要改什么，
     * 执行后走 8 秒撤销（孤儿图片文件是删掉的垃圾，不在撤销范围内，弹窗里写明）。
     */
    private void bindOrphanRepair() {
        if (b == null) {
            return;
        }
        boolean has = borrowOrphans != null && !borrowOrphans.isEmpty();
        b.rowOrphanRepair.setVisibility(has ? View.VISIBLE : View.GONE);
        if (!has) {
            return;
        }
        b.tvOrphanRepair.setText(borrowOrphans.line());
        b.btnOrphanRepair.setOnClickListener(v -> {
            String plan = com.bai.bookshelf.util.BorrowLedger.describePlan(borrowOrphans);
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setTitle("修复借阅数据")
                    .setMessage(plan + "\n\n确认后立即执行，8 秒内可撤销（已删除的孤儿图片文件不恢复）。")
                    .setPositiveButton("执行修复", (d, w) -> {
                        BookshelfApp app = BookshelfApp.get();
                        final com.bai.bookshelf.util.StatsHealth.BorrowOrphans target =
                                borrowOrphans;
                        app.io(() -> {
                            com.bai.bookshelf.util.BorrowLedger.OrphanFix fix =
                                    com.bai.bookshelf.util.BorrowLedger
                                            .repairOrphans(app, target);
                            app.ui(() -> {
                                com.bai.bookshelf.util.BorrowLedger.afterChange(
                                        requireContext());
                                ensureComputed();
                                com.bai.bookshelf.util.UndoBar.offer(
                                        b.getRoot(), "已修复 " + fix.total() + " 处借阅数据",
                                        () -> app.io(() -> {
                                            com.bai.bookshelf.util.BorrowLedger
                                                    .undoOrphanFix(app, fix);
                                            app.ui(this::ensureComputed);
                                        }), null);
                            });
                        });
                    })
                    .setNegativeButton(com.bai.bookshelf.R.string.action_cancel, null)
                    .show();
        });
    }

    /** 在借清单：书名 · 借阅人 · 应还日期（逾期标红），点一行可直接收回（v5.8.3） */
    private void renderOpenLoans() {
        b.llOpenLoans.removeAllViews();
        if (openLoans == null || openLoans.isEmpty()) {
            addRow(b.llOpenLoans, "现在没有借出去的书", 13, true);
            b.tvOpenLoansHint.setText("在书详情页或逐本信息里可以登记借出");
            return;
        }
        java.text.SimpleDateFormat md = new java.text.SimpleDateFormat("M月d日",
                Locale.getDefault());
        int today = com.bai.bookshelf.util.DateKeys.todayKey();
        for (final OpenLoan loan : openLoans) {
            StringBuilder line = new StringBuilder();
            if (loan.book == null) {
                line.append("记录 #").append(loan.record.id).append("（书已删除）");
            } else {
                line.append("《").append(loan.title).append("》");
            }
            line.append(" · ").append(loan.borrower);
            if (loan.dueKey > 0) {
                line.append(" · 应还 ").append(formatKey(loan.dueKey));
                int overdue = com.bai.bookshelf.util.BorrowReminder.daysBetween(
                        loan.dueKey, today);
                if (overdue > 0) {
                    line.append("（逾期 ").append(overdue).append(" 天）");
                }
            } else {
                line.append(" · 未填应还日期");
            }
            boolean overdue = loan.dueKey > 0 && loan.dueKey < today;
            android.widget.TextView row = addRow(b.llOpenLoans, line.toString(), 13, false);
            if (overdue) {
                // 逾期单独用错误色，别和「次要文字」的浅灰混在一起
                row.setTextColor(com.bai.bookshelf.util.ThemeManager.resolveColor(
                        requireContext(), com.google.android.material.R.attr.colorError));
            }
            row.setOnClickListener(v -> confirmReturn(loan));
        }
        b.tvOpenLoansHint.setText("点一行可收回；逾期按应还日期和今天比较");
    }

    private static String formatKey(int yyyyMMdd) {
        return (yyyyMMdd / 100 % 100) + "月" + (yyyyMMdd % 100) + "日";
    }

    /** 收回一本：关闭借阅记录 + 把书/副本状态改回在库（与详情页同一套规则） */
    private void confirmReturn(final OpenLoan loan) {
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("收回《" + loan.title + "》？")
                .setMessage("借阅人：" + loan.borrower
                        + (loan.dueKey > 0 ? "\n应还日期：" + formatKey(loan.dueKey) : "")
                        + "\n\n收回后会记下归还日期，并把这一册改回在库。")
                .setNegativeButton("取消", null)
                .setPositiveButton("收回", (d, w) -> doReturn(loan))
                .show();
    }

    private void doReturn(final OpenLoan loan) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            try {
                loan.record.status = com.bai.bookshelf.data.entity.BorrowRecord.STATUS_RETURNED;
                loan.record.returnDate = com.bai.bookshelf.util.StatusText.today();
                app.db().borrowRecordDao().update(loan.record);
                if (loan.record.copyIndex == 1) {
                    Book book = loan.book;
                    if (book != null) {
                        book.stockStatus = Book.STOCK_IN;
                        if (BookFlags.isBorrowSource(book)
                                && !com.bai.bookshelf.util.BorrowSourceRecords.hasOpen(book)
                                && book.borrowReturnDate != null
                                && !book.borrowReturnDate.trim().isEmpty()) {
                            book.stockStatus = Book.STOCK_BOOKMARK;
                        }
                        book.updatedAt = System.currentTimeMillis();
                        app.db().bookDao().update(book);
                    }
                } else {
                    for (com.bai.bookshelf.data.entity.BookCopy c
                            : app.db().bookCopyDao().getByBook(loan.record.bookId)) {
                        if (c.copyIndex == loan.record.copyIndex) {
                            c.stockStatus = Book.STOCK_IN;
                            app.db().bookCopyDao().update(c);
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                app.ui(() -> android.widget.Toast.makeText(requireContext(),
                        "收回失败：" + e.getMessage(), android.widget.Toast.LENGTH_SHORT).show());
                return;
            }
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                android.widget.Toast.makeText(requireContext(),
                        "已收回《" + loan.title + "》", android.widget.Toast.LENGTH_SHORT).show();
                // 逐本借出改的是 book_copies，不会触发 books 的 LiveData，手动重算
                statsComputed = false;
                computeFrom(statsBooks);
            });
        });
    }

    /** 在借清单的一行：借阅记录 + 书 + 应还日键 */
    private static class OpenLoan {
        final com.bai.bookshelf.data.entity.BorrowRecord record;
        final Book book;
        final String title;
        final String borrower;
        final int dueKey;

        OpenLoan(com.bai.bookshelf.data.entity.BorrowRecord r, Book book) {
            this.record = r;
            this.book = book;
            this.title = book == null || book.title.isEmpty() ? "（未命名）" : book.title;
            this.borrower = r.borrower == null || r.borrower.isEmpty() ? "未填借阅人" : r.borrower;
            this.dueKey = com.bai.bookshelf.util.DateKeys.parseKey(r.dueDate);
        }
    }

    private static java.util.List<OpenLoan> buildOpenLoans(List<Book> books,
                                                           java.util.List<com.bai.bookshelf.data.entity.BorrowRecord> records,
                                                           int todayKey) {
        java.util.Map<Long, Book> byId = new java.util.HashMap<>();
        for (Book bk : books) {
            byId.put(bk.id, bk);
        }
        java.util.List<OpenLoan> out = new ArrayList<>();
        for (com.bai.bookshelf.data.entity.BorrowRecord r : records) {
            if (r.status == com.bai.bookshelf.data.entity.BorrowRecord.STATUS_OUT) {
                out.add(new OpenLoan(r, byId.get(r.bookId)));
            }
        }
        // 逾期的排最前，其余按应还日期
        out.sort((a, b) -> {
            boolean ao = a.dueKey > 0 && a.dueKey < todayKey;
            boolean bo = b.dueKey > 0 && b.dueKey < todayKey;
            if (ao != bo) {
                return ao ? -1 : 1;
            }
            return Integer.compare(a.dueKey, b.dueKey);
        });
        return out;
    }

    /** 画分类树状图：一级总览，或某个一级分类下的二级 */
    private void renderTreemap() {
        if (b == null || categoryTree == null) {
            return;
        }
        if (treemapDrill >= 0 && treemapDrill < categoryTree.size()) {
            com.bai.bookshelf.util.StatsHoldings.Group g = categoryTree.get(treemapDrill);
            b.treemapCategories.setData(g.children);
            b.tvTreemapHint.setText("「" + g.name + "」的二级分类 · 点此返回一级");
            renderTreemapLegend(g.children);
            return;
        }
        treemapDrill = -1;
        b.treemapCategories.setData(categoryTree);
        b.tvTreemapHint.setText(b.treemapCategories.metricHint() + " · 点一块看二级");
        renderTreemapLegend(categoryTree);
    }

    /** 图例：色点与块内取色同源（v5.8.15，构建逻辑见 TreemapLegend） */
    private void renderTreemapLegend(
            java.util.List<com.bai.bookshelf.util.StatsHoldings.Group> groups) {
        TreemapLegend.fill(b.llTreemapLegend, groups, b.treemapCategories::colorAt);
    }

    /** 买入年份 → 多久读完：一行一个年份，观察窗口没关满的不给 N 年比例 */
    private void renderCohort(java.util.List<com.bai.bookshelf.util.StatsHoldings.Cohort> cohorts) {
        b.llCohort.removeAllViews();
        if (cohorts == null || cohorts.isEmpty()) {
            b.tvCohortHint.setText("没有填购买日期的书，算不出买入年份");
            return;
        }
        int todayYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        for (com.bai.bookshelf.util.StatsHoldings.Cohort c : cohorts) {
            addRow(b.llCohort, c.line(todayYear), 13, false);
        }
        b.tvCohortHint.setText("口径：按买入年份分组（借阅不算买入）；窗口没满的年份不给几年内比例");
    }

    /** 往容器里加一行纯文字（cohort / 渠道榜共用，省掉每处重复的 TextView 拼装） */
    private android.widget.TextView addRow(android.widget.LinearLayout container, String text,
                                            float sp, boolean secondary) {
        android.widget.TextView tv = new android.widget.TextView(requireContext());
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(com.bai.bookshelf.util.ThemeManager.resolveColor(requireContext(),
                secondary ? R.attr.on_surface_variant : R.attr.on_surface));
        tv.setLineSpacing(0, 1.25f);
        tv.setText(text);
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(6 * getResources().getDisplayMetrics().density);
        container.addView(tv, lp);
        return tv;
    }

    /** 渠道 ROI：每个来源一行「N 种 · 花费 · 折扣 · 每页 · 读完率」 */
    private void renderChannels(
            java.util.List<com.bai.bookshelf.util.StatsHoldings.Group> channels) {
        b.llChannel.removeAllViews();
        if (channels == null || channels.isEmpty()) {
            b.tvChannelHint.setText("还没有填来源渠道的自购书");
            return;
        }
        int shown = 0;
        for (com.bai.bookshelf.util.StatsHoldings.Group g : channels) {
            if (shown++ >= 6) {
                break;
            }
            addRow(b.llChannel, g.statLine(), 13, false);
        }
        b.tvChannelHint.setText("口径：只算自购（赠书与借阅不计花费），花费按册、读完率按种");
    }

    /** 每页成本对比：只列页数与花费都齐的分类，按每页单价从高到低 */
    private void renderCostRows(
            java.util.List<com.bai.bookshelf.util.StatsHoldings.Group> categories) {
        b.llCost.removeAllViews();
        java.util.List<com.bai.bookshelf.util.StatsHoldings.Group> priced = new ArrayList<>();
        for (com.bai.bookshelf.util.StatsHoldings.Group g : categories) {
            if (g.perPage() > 0) {
                priced.add(g);
            }
        }
        if (priced.isEmpty()) {
            addRow(b.llCost, "页数与花费都填齐的分类还不够多（需要至少 "
                    + com.bai.bookshelf.util.LibraryStats.MIN_SAMPLE + " 种）", 13, true);
            return;
        }
        priced.sort((x, y) -> Double.compare(y.perPage(), x.perPage()));
        int shown = 0;
        for (com.bai.bookshelf.util.StatsHoldings.Group g : priced) {
            if (shown++ >= 5) {
                break;
            }
            addRow(b.llCost, g.name + " · 每页 " + money(g.perPage())
                    + "（" + g.titles + " 种 · 花费 " + money(g.paid) + "）", 13, false);
        }
    }

    /** 读的结构 vs 藏的结构：同一分类两个占比放一起，买得多不等于读得多 */
    private void renderStructure(
            java.util.List<com.bai.bookshelf.util.StatsHoldings.Share> structure) {
        b.llStructure.removeAllViews();
        if (structure == null || structure.isEmpty()) {
            b.tvStructureHint.setText("还没有分类，先给书归归类");
            return;
        }
        int shown = 0;
        for (com.bai.bookshelf.util.StatsHoldings.Share s : structure) {
            if (shown++ >= 6) {
                break;
            }
            addRow(b.llStructure, s.line(), 13, false);
        }
        b.tvStructureHint.setText("口径：占比按种数算，「已读」是读完的种数；只看一级分类");
    }

    /** 盘点：在架 / 在外 / 遗失（藏书栏；借出结构已移到借阅栏） */
    private static String stocktakeLine(com.bai.bookshelf.util.StatsHoldings.Holdings h) {
        StringBuilder sb = new StringBuilder("盘点：在架 ").append(h.copiesInStore()).append(" 册 · 在外 ")
                .append(h.copiesLent).append(" · 遗失 ").append(h.copiesLost);
        if (h.lossRate() > 0) {
            sb.append("（损耗 ").append(Math.round(h.lossRate() * 100)).append("%）");
        }
        return sb.toString();
    }

    /** 借阅栏洞察（v5.8.1 第 4 栏）：在途与按时归还、借得最多的人、借出结构 */
    private static String lendInsight(com.bai.bookshelf.util.StatsLend.Lend l,
                                      java.util.List<com.bai.bookshelf.util.StatsLend.Lender> lenders,
                                      java.util.List<com.bai.bookshelf.util.StatsHoldings.Group> lends,
                                      com.bai.bookshelf.util.StatsHealth.BorrowOrphans orphans) {
        StringBuilder sb = new StringBuilder(lendLine(l));
        if (orphans != null && !orphans.isEmpty()) {
            sb.append('\n').append(orphans.line());
        }
        if (lenders != null && !lenders.isEmpty()) {
            sb.append('\n').append(lenderLine(lenders.get(0)));
        }
        if (lends != null && !lends.isEmpty()) {
            com.bai.bookshelf.util.StatsHoldings.Group top = lends.get(0);
            sb.append('\n').append("借出最多的是「").append(top.name).append("」（")
                    .append(top.titles).append(" 笔");
            if (top.readDone > 0) {
                sb.append(" · 其中读完 ").append(top.readDone);
            }
            sb.append("）");
        }
        return sb.toString();
    }

    /** 逐月花费柱状图 + 外推与移动平均（复用统计页已有的柱状图组件） */
    private void renderTrend(com.bai.bookshelf.util.StatsSpend.Trend t) {
        java.util.List<Integer> values = new ArrayList<>();
        for (double v : t.spent) {
            values.add((int) Math.round(v));
        }
        b.chartTrend.setUnit("元");
        b.chartTrend.setData(t.months, values);
        StringBuilder sb = new StringBuilder("今年已花 ").append(money(t.yearSpent))
                .append(" · 买入 ").append(t.yearBought).append(" 种\n")
                .append(t.projectedText());
        if (t.monthsElapsed >= 3) {
            sb.append('\n').append("近三个月月均 ").append(money(t.threeMonthAvg));
        }
        b.tvTrend.setText(sb);
    }

    /** 系列与套装：两个及以上成系列的才列，顺带报缺卷 */
    private void renderSeries(
            java.util.List<com.bai.bookshelf.util.StatsHoldings.Group> groups,
            java.util.List<Book> books) {
        b.llSeries.removeAllViews();
        int shown = 0;
        for (com.bai.bookshelf.util.StatsHoldings.Group g : groups) {
            if ("未成系列".equals(g.name) || g.titles < 2) {
                continue;
            }
            if (shown++ >= 6) {
                break;
            }
            int missing = com.bai.bookshelf.util.StatsHoldings.missingVolumes(books, g.name);
            addRow(b.llSeries, g.name + " · " + g.titles + " 种 · 已读 " + g.readDone
                    + (g.paid > 0 ? " · 花费 " + money(g.paid) : "")
                    + (missing > 0 ? " · 疑似缺 " + missing + " 卷" : ""), 13, false);
        }
        b.tvSeriesHint.setText(shown == 0
                ? "还没有两个以上成系列的藏书；在编辑页填「系列名」就会归到这里"
                : "口径：按种数计；缺卷只在卷号是 1、2、3 这种数字时才判断");
    }

    /** 阅读习惯卡：连续天数、最勤的月份与星期、买读比、该处理的书 */
    private static String habitText(com.bai.bookshelf.util.StatsPace.Habit h,
                                    com.bai.bookshelf.util.StatsPace.Stale s,
                                    com.bai.bookshelf.util.StatsPace.BuyRead b) {
        StringBuilder sb = new StringBuilder("今年读过 ").append(h.daysReadThisYear).append(" 天");
        if (h.longestStreak > 0) {
            sb.append(" · 最长连续 ").append(h.longestStreak).append(" 天");
        }
        if (h.currentStreak > 0) {
            sb.append(" · 当前连着 ").append(h.currentStreak).append(" 天");
        }
        sb.append('\n').append("（按阅读记录的起止区间推算，在读且没填结束日期的书算每天在读）");
        if (h.bestMonth > 0) {
            sb.append('\n').append("最勤的是 ").append(h.bestMonth).append(" 月（")
                    .append(h.bestMonthDays).append(" 天）");
            if (h.bestWeekdayName != null) {
                sb.append(" · 一周里 ").append(h.bestWeekdayName).append(" 读得最多");
            }
        }
        sb.append('\n').append("买读比 ").append(b.ratioText());
        if (b.hoardIndex >= 0) {
            sb.append(" · 囤书指数 ").append(Math.round(b.hoardIndex * 100))
                    .append("%（未读 / 在架）");
        }
        if (s.readingOver90 > 0 || s.boughtUnreadCount > 0) {
            sb.append('\n').append("在读超 90 天 ").append(s.readingOver90)
                    .append(" 种 · 买来一年没读 ").append(s.boughtUnreadCount).append(" 种");
            StringBuilder names = new StringBuilder();
            for (String[] row : s.boughtUnread) {
                if (names.length() > 0) {
                    names.append("、");
                }
                names.append('《').append(row[0]).append('》').append(row[1]).append(" 天");
            }
            if (names.length() > 0) {
                sb.append("：").append(names);
            }
        }
        return sb.toString();
    }

    /** 评分与未评比例（藏书栏） */
    private static String ratingLine(com.bai.bookshelf.util.StatsHealth.Rating r) {
        StringBuilder sb = new StringBuilder();
        if (r.average > 0) {
            sb.append("平均评分 ")
                    .append(String.format(Locale.getDefault(), "%.1f", r.average))
                    .append("（").append(r.rated).append(" 种已打分）");
            if (r.bestCategory != null) {
                sb.append("，最偏爱「").append(r.bestCategory).append("」");
            }
            sb.append(" · 还有 ").append(Math.round(r.unratedRate() * 100)).append("% 没打分");
        } else {
            sb.append("还没开始打分（").append(r.total).append(" 种都没评分）");
        }
        return sb.toString();
    }

    /** 数据体检：重复/负价/未来日期这类记录问题（藏书栏） */
    private static String anomalyLine(com.bai.bookshelf.util.StatsHealth.Anomalies a,
                                      java.util.List<String> readingOutNames,
                                      int lendDateUnparsable) {
        // v5.8.33：借阅相关的两项单独算，凑不齐全 0 才算「没发现异常」
        int extra = (readingOutNames == null ? 0 : readingOutNames.size())
                + lendDateUnparsable;
        if (a.total() == 0 && extra == 0) {
            return "数据体检：没发现重复或异常记录";
        }
        if (a.total() == 0) {
            return "数据体检 " + extra + " 处要看看" + lendingAnomalyTail(
                    readingOutNames, lendDateUnparsable);
        }
        return "数据体检 " + a.total() + " 处要看看：重复 ISBN "
                + a.duplicateIsbn.size() + "、同名 " + a.duplicateTitle.size()
                + "、负价 " + a.negativePrice + "、未来日期 "
                + a.futureDate + "、价格离群 " + a.overpriced
                + "、页数异常 " + a.oddPages
                // v5.8.31：旧单会话模型把「弃读→再读→读完」压成一条，时长会跨掉弃读空窗
                + (a.longReadWithDrop == 0 ? ""
                : "、阅读时长超 " + com.bai.bookshelf.util.StatsHealth.LONG_READ_DAYS
                + " 天且中途有弃读 " + a.longReadWithDrop + " 本")
                + lendingAnomalyTail(readingOutNames, lendDateUnparsable);
    }

    /** 体检里借阅那两项：只列清单与说明，不给自动修复（改了等于改借阅数据） */
    private static String lendingAnomalyTail(java.util.List<String> readingOutNames,
                                             int lendDateUnparsable) {
        StringBuilder sb = new StringBuilder();
        int n = readingOutNames == null ? 0 : readingOutNames.size();
        if (n > 0) {
            sb.append("、在读但全部册在外 ").append(n).append(" 本（这些天不算阅读时长）");
            StringBuilder names = new StringBuilder();
            for (String name : readingOutNames) {
                if (names.length() > 0) {
                    names.append("、");
                }
                names.append('《').append(name).append('》');
            }
            sb.append("：").append(names);
        }
        if (lendDateUnparsable > 0) {
            sb.append("、借出/归还日期残缺 ").append(lendDateUnparsable)
                    .append(" 条（借出区间算不出来，这段没扣）");
        }
        return sb.toString();
    }

    /**
     * 洞察卡文案（v5.8）：一行一个结论，都带口径与样本数。
     *
     * <p>算不出来的行写「数据不足」并说明原因，绝不显示 0 或 NaN——
     * 对个人藏书来说，「只有 6 笔归还记录」本身就是有用的信息。
     */
    private static String holdingsLine(com.bai.bookshelf.util.StatsHoldings.Holdings h) {
        StringBuilder sb = new StringBuilder("藏书 ");
        sb.append(com.bai.bookshelf.util.Units.holdings(h.titles, h.copies));
        sb.append("；已读 ").append(h.readDone).append(" 种");
        if (h.readRate() >= 0) {
            sb.append("（").append(Math.round(h.readRate() * 100)).append("%）");
        }
        return sb.toString();
    }

    private static String spendLine(com.bai.bookshelf.util.StatsSpend.Spend s) {
        StringBuilder sb = new StringBuilder("累计花费 ").append(money(s.paid))
                .append("（全部自购，不看购买日期），定价合计 ").append(money(s.listValue));
        if (s.discount >= 0) {
            sb.append("，平均 ").append(s.discountText()).append("，累计省下 ").append(money(s.saved));
        } else {
            sb.append("，折扣数据不足（仅 ").append(s.discountSample).append(" 种同时有定价与实付）");
        }
        return sb.toString();
    }

    private static String costLine(com.bai.bookshelf.util.StatsSpend.Spend s) {
        StringBuilder sb = new StringBuilder();
        if (s.perPage >= 0 || s.per10kWords >= 0) {
            sb.append("单位成本：");
            if (s.perPage >= 0) {
                sb.append("每页 ").append(money(s.perPage));
            }
            if (s.perPage >= 0 && s.per10kWords >= 0) {
                sb.append(" · ");
            }
            if (s.per10kWords >= 0) {
                sb.append("每一万字 ").append(money(s.per10kWords));
            }
            sb.append("（按册，样本 ").append(Math.max(s.pageSample, s.wordSample)).append(" 种）");
        } else {
            sb.append("单位成本数据不足（每页 ").append(s.pageSample)
                    .append(" 种 / 每万字 ").append(s.wordSample).append(" 种）");
        }
        return sb.toString();
    }

    private static String speedLine(com.bai.bookshelf.util.StatsPace.Pace p) {
        if (p.medianDays <= 0) {
            return "阅读速度数据不足（没有起止日期都齐全的书）";
        }
        StringBuilder sb = new StringBuilder("阅读速度：中位读完 ")
                .append(Math.round(p.medianDays)).append(" 天");
        if (p.medianPagesPerDay > 0) {
            sb.append("，").append(Math.round(p.medianPagesPerDay)).append(" 页/天");
        }
        sb.append("（样本 ").append(p.daySample).append(" 种）");
        return sb.toString();
    }

    private static String debtLine(com.bai.bookshelf.util.StatsPace.Pace p) {
        StringBuilder sb = new StringBuilder("书债：未读 ")
                .append(com.bai.bookshelf.util.Units.holdings(p.unreadTitles, p.unreadCopies));
        sb.append("，约 ").append(p.unreadPages).append(" 页、实付 ").append(money(p.unreadPaid));
        if (p.monthsToClear >= 0) {
            sb.append("，按当前速度约 ").append(Math.max(1, Math.round(p.monthsToClear))).append(" 个月清完");
        } else if (p.unknownReason != null) {
            sb.append("；清完时间算不出：").append(p.unknownReason);
        }
        return sb.toString();
    }

    private static String lendLine(com.bai.bookshelf.util.StatsLend.Lend l) {
        StringBuilder sb = new StringBuilder("借阅：在外 ").append(l.out).append(" 笔");
        if (l.overdue > 0) {
            sb.append("，").append(l.overdue).append(" 笔已超期");
        }
        sb.append("；按时归还率 ")
                .append(l.onTimeRate >= 0 ? Math.round(l.onTimeRate * 100) + "%" : "数据不足")
                .append("（样本 ").append(l.onTimeSample).append(" 笔）");
        return sb.toString();
    }

    private static String healthLine(com.bai.bookshelf.util.StatsHealth.Health g) {
        if (g.score < 0) {
            return "书房健康分数据不足";
        }
        StringBuilder sb = new StringBuilder("书房健康分 ").append(g.score).append("/100");
        if (g.weakest != null) {
            sb.append("，最该补的是").append(g.weakest)
                    .append("（").append(g.weakestMissing).append(" 种缺）");
        }
        return sb.toString();
    }

    private static String lenderLine(com.bai.bookshelf.util.StatsLend.Lender top) {
        StringBuilder sb = new StringBuilder("借得最多的是 ").append(top.name)
                .append("（").append(top.loans).append(" 笔");
        if (top.outNow > 0) {
            sb.append(" · 还在他手上 ").append(top.outNow).append(" 册");
        }
        if (top.overdue > 0) {
            sb.append(" · ").append(top.overdue).append(" 笔超期");
        }
        sb.append("）");
        return sb.toString();
    }

    /**
     * 藏书栏洞察：规模、健康、评分、体检、借阅与盘点。
     *
     * <p>v5.8.1 起洞察卡按分栏拆开——以前三个分栏显示的是同一段十几行，
     * 在「阅读」栏里读花费、在「购书」栏里读健康分，翻来翻去全是重复。
     */
    private static String booksInsight(com.bai.bookshelf.util.StatsHoldings.Holdings h,
                                       com.bai.bookshelf.util.StatsHealth.Health g,
                                       com.bai.bookshelf.util.StatsHealth.Rating r,
                                       com.bai.bookshelf.util.StatsHealth.Anomalies a,
                                       java.util.List<String> readingOutNames,
                                       int lendDateUnparsable) {
        StringBuilder sb = new StringBuilder(holdingsLine(h)).append('\n');
        sb.append(healthLine(g)).append('\n');
        sb.append(ratingLine(r)).append('\n');
        sb.append(anomalyLine(a, readingOutNames, lendDateUnparsable));
        if (h.copiesLost > 0) {
            sb.append('\n').append("损耗：遗失 ").append(h.copiesLost).append(" 册、")
                    .append(money(h.lostValue));
        }
        sb.append('\n').append(stocktakeLine(h));
        return sb.toString();
    }

    /** 借阅人榜（第 4 栏）：借出笔数、还在手上的册数、超期笔数 */
    private void renderBorrowers(
            java.util.List<com.bai.bookshelf.util.StatsLend.Lender> lenders) {
        b.llBorrowers.removeAllViews();
        if (lenders == null || lenders.isEmpty()) {
            addRow(b.llBorrowers, "还没有借出记录；在书架里长按一本书就能登记借出", 13, true);
            b.tvBorrowersHint.setText("借阅记录在书详情页的「借出记录」里管理");
            return;
        }
        int shown = 0;
        for (com.bai.bookshelf.util.StatsLend.Lender l : lenders) {
            if (shown++ >= 6) {
                break;
            }
            StringBuilder line = new StringBuilder(l.name)
                    .append(" · 借出 ").append(l.loans).append(" 笔");
            if (l.outNow > 0) {
                line.append(" · 还在手上 ").append(l.outNow).append(" 册");
            }
            if (l.overdue > 0) {
                line.append(" · 超期 ").append(l.overdue).append(" 笔");
            }
            addRow(b.llBorrowers, line.toString(), 13, false);
        }
        b.tvBorrowersHint.setText("口径：按借出笔数排序；按时归还率见上方洞察");
    }

    /** 阅读栏洞察：速度、书债、在读剩余页数与摘抄覆盖（习惯卡单独成卡，这里不重复） */
    private static String readingInsight(com.bai.bookshelf.util.StatsPace.Pace p,
                                         com.bai.bookshelf.util.StatsPace.Backlog backlog,
                                         com.bai.bookshelf.util.StatsHealth.NoteStat n,
                                         com.bai.bookshelf.util.StatsPace.DropRecovery drops) {
        return speedLine(p) + "\n" + debtLine(p) + "\n" + backlog.line()
                + "\n" + drops.line() + "\n" + n.line();
    }


    /** 购书栏洞察：花费、单位成本、同比环比、价格相关性与想读转化 */
    private static String purchaseInsight(com.bai.bookshelf.util.StatsSpend.Spend s,
                                          com.bai.bookshelf.util.StatsSpend.Relation r,
                                          java.util.List<com.bai.bookshelf.util.StatsSpend.PeriodDelta> periods,
                                          com.bai.bookshelf.util.StatsHealth.WishStat w) {
        StringBuilder sb = new StringBuilder(spendLine(s)).append('\n');
        sb.append(costLine(s)).append('\n');
        for (com.bai.bookshelf.util.StatsSpend.PeriodDelta p : periods) {
            sb.append(p.line()).append('\n');
        }
        sb.append(r.line()).append('\n');
        sb.append(w.line());
        return sb.toString();
    }

    /**
     * 金额自适应精度：每页成本这类几分钱一位的数，用 0 位小数会被抹成「¥0」，
     * 读起来像「这书不要钱」。小额给两位，大额给整数。
     */
    private static String money(double v) {
        return v < 10 ? String.format(Locale.getDefault(), "¥%.2f", v)
                : String.format(Locale.getDefault(), "¥%.0f", v);
    }

    /** hero 封面拼贴：最近 4 本书的封面扇形叠放（v4.31） */
    private void bindCoverFan(List<Book> recent) {
        ImageView[] views = {b.ivFan1, b.ivFan2, b.ivFan3, b.ivFan4};
        for (int i = 0; i < views.length; i++) {
            if (i >= recent.size()) {
                views[i].setVisibility(View.GONE);
                continue;
            }
            views[i].setVisibility(View.VISIBLE);
            com.bai.bookshelf.util.CoverLoader.load(views[i], recent.get(i));
        }
    }

    /** 藏书之最：三张带封面的 mini 卡 + 最早出版/印次最多的补充行（v4.25） */
    private void buildExtremes(Book mostExpensive, Book mostPages, Book mostWords,
                               Book earliest, Book mostPrintings) {
        b.llExtremes.removeAllViews();
        List<Extreme> items = new ArrayList<>();
        if (mostExpensive != null && mostExpensive.listPrice > 0) {
            items.add(new Extreme("最贵", mostExpensive,
                    String.format(Locale.getDefault(), "¥%.2f", mostExpensive.listPrice)));
        }
        if (mostPages != null && mostPages.pages > 0) {
            items.add(new Extreme("最厚", mostPages, mostPages.pages + " 页"));
        }
        if (mostWords != null) {
            items.add(new Extreme("字数最多", mostWords,
                    String.format(Locale.getDefault(), "%.1f 万字",
                            BookSanitizer.parseWords(mostWords.wordCount) / 10000.0)));
        }

        boolean hasExtremes = !items.isEmpty();
        b.tvExtremesTitle.setVisibility(hasExtremes ? View.VISIBLE : View.GONE);
        b.llExtremes.setVisibility(hasExtremes ? View.VISIBLE : View.GONE);
        if (!hasExtremes) {
            b.tvExtremesExtra.setVisibility(View.GONE);
            return;
        }

        for (int i = 0; i < items.size() && i < 3; i++) {
            Extreme item = items.get(i);
            ItemStatsExtremeBinding card =
                    ItemStatsExtremeBinding.inflate(getLayoutInflater(), b.llExtremes, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                lp.setMarginStart(dp(8));
            }
            card.getRoot().setLayoutParams(lp);
            card.tvLabel.setText(item.label);
            card.tvTitle.setText(BookText.bareTitle(item.book.title));
            card.tvMetric.setText(item.metric);
            loadCover(card.ivCover, item.book);
            card.getRoot().setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), BookDetailActivity.class);
                intent.putExtra(BookDetailActivity.EXTRA_BOOK, item.book);
                startActivity(intent);
            });
            b.llExtremes.addView(card.getRoot());
        }
        // 不足 3 张时补占位，保持三列等宽，避免单张卡被拉成整屏
        for (int i = items.size(); i < 3; i++) {
            View spacer = new View(requireContext());
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            b.llExtremes.addView(spacer);
        }

        StringBuilder extra = new StringBuilder();
        if (earliest != null && !earliest.publishDate.isEmpty()) {
            extra.append("最早出版：").append(BookText.bareTitle(earliest.title))
                    .append(" · ").append(earliest.publishDate);
        }
        if (mostPrintings != null) {
            if (extra.length() > 0) {
                extra.append("\n");
            }
            extra.append("印次最多：").append(BookText.bareTitle(mostPrintings.title))
                    .append(" · 第 ").append(BookSanitizer.parsePrintingCount(mostPrintings.printing))
                    .append(" 次印刷");
        }
        b.tvExtremesExtra.setText(extra.toString().trim());
        b.tvExtremesExtra.setVisibility(extra.length() > 0 ? View.VISIBLE : View.GONE);
    }

    /** mini 卡封面：统一走 CoverLoader */
    private void loadCover(ImageView iv, Book book) {
        com.bai.bookshelf.util.CoverLoader.load(iv, book);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final class Extreme {
        final String label;
        final Book book;
        final String metric;

        Extreme(String label, Book book, String metric) {
            this.label = label;
            this.book = book;
            this.metric = metric;
        }
    }

    /** 分布行渲染器（v3.0 抽出公共组件，点击行跳书架搜索） */
    private DistRowBuilder distRows() {
        return new DistRowBuilder(requireContext(), name -> ShelfNavigator.search(requireActivity(), name));
    }

    /** 计数 Map → 按数量倒序的前 N 名 */
    private List<Map.Entry<String, Integer>> topN(Map<String, Integer> map, int n) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
        list.sort((x, y) -> y.getValue() - x.getValue());
        return list.size() > n ? list.subList(0, n) : list;
    }
}
