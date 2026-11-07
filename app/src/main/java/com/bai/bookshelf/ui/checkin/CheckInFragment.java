package com.bai.bookshelf.ui.checkin;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.databinding.FragmentCheckinBinding;
import com.bai.bookshelf.ui.MainActivity;
import com.bai.bookshelf.ui.TabRefresh;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.CheckInCalendarState;
import com.bai.bookshelf.util.CheckInDayDetail;
import com.bai.bookshelf.util.CheckInHabit;
import com.bai.bookshelf.util.CheckInHeader;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.CheckInStore;
import com.bai.bookshelf.util.CheckInUiState;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.HolidayCalendar;
import com.bai.bookshelf.util.HolidayStore;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ReadDays;
import com.bai.bookshelf.util.ReadingRecords;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 打卡页（第 ③ 步）：月视图 + 在读书籍列表 + 打卡/补卡/撤销。
 *
 * <p>所有 DB 读写都走 BookshelfApp.io()，UI 更新回主线程；toggle 本身不碰
 * readStatus 与阅读会话。打卡成功后只重绑受影响的一行与一个日历格，不整表重建。
 */
public class CheckInFragment extends Fragment
        implements MonthGridView.Listener, CheckInSwipeRoot.Listener,
        CheckInAdapter.Listener, TabRefresh {

    private FragmentCheckinBinding b;
    private CheckInAdapter adapter;
    private int selectedDayKey;
    private int todayKey;
    private long lastReloadMs;
    private CheckInViewSwitcher switcher;
    private CheckInYearPanel yearPanel;
    private CheckInMonthDayAdapter monthDayAdapter;
    /** 在读清单本身为空：没有任何「在读」的书 */
    private boolean readingEmpty;
    /** 在读清单不为空，但选中日的行全部早于开读日而被过滤光 */
    private boolean dayEmpty;
    /** 年→月转场期间把列表/小结/图例淡掉，数据回来后再显现 */
    private boolean monthChromeHidden;

    // 三态（本轮只接 MONTH↔WEEK；DETAIL 等逐日列表规格）
    private CheckInCalendarState.Mode mode = CheckInCalendarState.Mode.MONTH;
    private int toolbarFullPx;
    private int toolbarCompactPx;
    private float titleFullPx;
    private float titleCompactPx;
    private float relFullPx;
    private float relCompactPx;
    /** 当前已套用的基准高度档（false = 常规 / true = 紧凑），避免每帧 setBaseHeight */
    private boolean headerCompactBase;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentCheckinBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        todayKey = DateKeys.todayKey();
        selectedDayKey = todayKey;
        mode = CheckInCalendarState.Mode.MONTH; // 新视图从整月开始
        headerCompactBase = false;

        View yearContentView = b.getRoot().findViewById(R.id.year_content);
        yearPanel = new CheckInYearPanel(yearContentView);
        yearPanel.setListener(new CheckInYearPanel.Listener() {
            @Override
            public void onPick(int yearMonth, int dayKey) {
                exitYearToMonth(yearMonth, dayKey);
            }

            @Override
            public void onYearChanged(int year) {
                // 年份标题与今年小结由 CheckInYearPanel 内部刷新；节日表按新年份补一次
                refreshHolidaysForYear(year);
            }
        });
        switcher = new CheckInViewSwitcher(b.viewSwitchHost, b.monthContent,
                yearContentView, yearPanel.yearView(), b.monthGrid);
        yearPanel.yearView().setYearSwitcher(switcher);

        // 纵向手势的唯一入口：整页可滑，列表/悬浮按钮/年视图分别让行
        b.swipeRoot.setListener(this);
        b.swipeRoot.setGuard(() -> switcher == null
                || switcher.isYearVisible() || switcher.isAnimating());
        b.swipeRoot.setScrollList(b.rvCheckin);
        b.swipeRoot.setExcludedList(b.rvMonthDays);
        b.swipeRoot.setExcludedView(b.fabToday);

        InsetsHelper.applyTopInset(b.toolbar);
        toolbarFullPx = getResources().getDimensionPixelSize(R.dimen.toolbar_h);
        toolbarCompactPx = getResources().getDimensionPixelSize(R.dimen.toolbar_h_compact);
        titleFullPx = b.tvMonthTitle.getTextSize();
        titleCompactPx = getResources().getDimension(R.dimen.text_body);
        relFullPx = b.tvMonthRelative.getTextSize();
        relCompactPx = getResources().getDimension(R.dimen.text_caption_compact);
        // 页头与月历折叠共用同一个 220ms 进度：每帧由网格回调，不另起动画
        b.monthGrid.setCollapseProgressListener(this::applyHeaderProgress);
        setToolbarMonth(b.monthGrid.getYearMonth());
        b.toolbar.setOnClickListener(v -> toggleYearView());
        b.fabToday.setOnClickListener(v -> {
            if (switcher.isAnimating()) {
                return;
            }
            if (switcher.isYearVisible()) {
                final int[] todayPick = yearPanel.todayPick(DateKeys.todayKey());
                // 「今」返回时缩放中心落在今天格，不是上一次选中的日子
                b.monthGrid.setSelectedDay(todayPick[1]);
                switcher.toMonth(b.monthGrid.selectedCellCenterIn(b.viewSwitchHost), () -> {
                    selectedDayKey = todayPick[1];
                    b.monthGrid.moveToToday();
                });
            } else {
                b.monthGrid.moveToToday();
            }
        });

        adapter = new CheckInAdapter();
        adapter.setListener(this);
        b.rvCheckin.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.rvCheckin.setAdapter(adapter);

        monthDayAdapter = new CheckInMonthDayAdapter();
        monthDayAdapter.setListener(this::onMonthDayClick);
        b.rvMonthDays.setLayoutManager(new LinearLayoutManager(requireContext()));
        b.rvMonthDays.setAdapter(monthDayAdapter);
        b.llMonthDaysHandle.setOnClickListener(v -> toggleMonthDays());

        // 空态两套文案：「还没有在读的书」与「这一天还没有可打卡的书」共用同一个 include
        renderEmptyState();
        b.emptyState.btnEmptyCta.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).showTab(MainActivity.TAB_SHELF);
            }
        });

        b.monthGrid.setListener(this);
        renderLegend();
        applyMode(mode, false); // 初始档位：整月 + 书目列表
        lastReloadMs = android.os.SystemClock.elapsedRealtime();
        BookshelfApp.get().whenDbReady(() -> loadMonth(b.monthGrid.getYearMonth()));
    }

    /** 标题栏在年视图里就是返回月视图的入口 */
    private void toggleYearView() {
        if (switcher == null || switcher.isAnimating()) {
            return;
        }
        if (switcher.isYearVisible()) {
            switcher.toMonth(b.monthGrid.selectedCellCenterIn(b.viewSwitchHost), null);
        } else {
            openYearView();
        }
    }

    /**
     * 年视图同页内切换：先异步取数（动画前完成，避免边动边查库掉帧），
     * 再以当前选中日期格为缩放中心放大进入。
     */
    private void openYearView() {
        if (b == null || switcher == null || switcher.isAnimating()
                || switcher.isYearVisible()) {
            return;
        }
        // 进年视图前先展开整月（不带动画），缩放中心按展开后的格子算
        applyMode(CheckInCalendarState.Mode.MONTH, false);
        final int ym = b.monthGrid.getYearMonth();
        BookshelfApp app = BookshelfApp.get();
        final int year = CheckInMonthGrid.yearOf(ym);
        app.io(() -> {
            List<CheckIn> all;
            try {
                all = app.db().checkInDao().getInRange(0, 99999999);
            } catch (Exception ignored) {
                all = new ArrayList<>();
            }
            final List<CheckIn> list = all;
            // 先把该年节日表放进内存，年视图 bind 时的 HolidaySource 就能同步命中
            HolidayStore.load(app, year);
            final int today = DateKeys.todayKey();
            app.ui(() -> {
                if (b == null || !isAdded()) {
                    return;
                }
                // 年份边界、网格 bind、标题与小结全部收进 CheckInYearPanel
                yearPanel.bind(ym, list, today);
                switcher.toYear(b.monthGrid.selectedCellCenterIn(b.viewSwitchHost), null);
                refreshHolidaysForYear(year);
            });
        });
    }

    /**
     * 年视图点某月：一次转场连续缩小回月视图，不再先放大卡片再切月。
     *
     * <p>转场开始前先用 setYearMonthSilently 把目标月铺好（不播报、不回调），
     * 动画期间看到的就是新月份；列表/小结/图例先淡出，数据回来后由 loadMonth 恢复。
     */
    private void exitYearToMonth(int yearMonth, int dayKey) {
        if (b == null || switcher == null || switcher.isAnimating()) {
            return;
        }
        final int month = CheckInMonthGrid.monthOf(yearMonth);
        final int day = dayKey > 0
                ? dayKey : CheckInMonthGrid.clampDayKey(selectedDayKey, yearMonth);
        selectedDayKey = day;
        applyMode(CheckInCalendarState.Mode.MONTH, false);
        b.monthGrid.setYearMonthSilently(yearMonth, day);
        setToolbarMonth(yearMonth);
        setMonthChromeAlpha(0f);
        monthChromeHidden = true;
        switcher.toMonthFromCard(month, () -> {
            if (b == null) {
                return;
            }
            loadMonth(yearMonth, true);
        });
    }

    /**
     * 空态：分「还没有在读的书」与「这一天还没有可打卡的书」两套文案。
     * 后者只是翻到了开读日之前，没有可做的事，所以不给 CTA。
     */
    private void renderEmptyState() {
        if (b == null) {
            return;
        }
        if (dayEmpty) {
            b.emptyState.tvEmptyTitle.setText("这一天还没有可打卡的书");
            b.emptyState.tvEmptyDesc.setText("换一天看看");
            b.emptyState.btnEmptyCta.setVisibility(View.GONE);
        } else {
            b.emptyState.tvEmptyTitle.setText("还没有在读的书");
            b.emptyState.tvEmptyDesc.setText("把一本书标成在读，就能每天打卡");
            b.emptyState.btnEmptyCta.setText("打开书架");
            b.emptyState.btnEmptyCta.setVisibility(View.VISIBLE);
        }
    }

    /** 两种空态对外只是一个「列表没有行」的结果，可见性判断统一走这里 */
    private boolean listEmpty() {
        return readingEmpty || dayEmpty;
    }

    /** 年→月转场期间先藏起来的月视图装饰：摘要/图例/列表/空态 */
    private void setMonthChromeAlpha(float alpha) {
        if (b == null) {
            return;
        }
        b.llCheckinSummary.setAlpha(alpha);
        b.llCheckinLegend.setAlpha(alpha);
        b.tvLegendNote.setAlpha(alpha);
        b.rvCheckin.setAlpha(alpha);
        b.emptyState.getRoot().setAlpha(alpha);
    }

    /** 月视图列表与空态的可见性：DETAIL 显示逐日明细，其余按两种空态决定 */
    private void updateMonthListVisibility() {
        if (b == null) {
            return;
        }
        boolean detail = mode == CheckInCalendarState.Mode.DETAIL;
        boolean empty = listEmpty();
        b.rvCheckin.setVisibility(!detail && !empty ? View.VISIBLE : View.GONE);
        b.emptyState.getRoot().setVisibility(!detail && empty ? View.VISIBLE : View.GONE);
        b.rvMonthDays.setVisibility(detail ? View.VISIBLE : View.GONE);
    }

    private void restoreMonthChrome() {
        if (!monthChromeHidden) {
            return;
        }
        monthChromeHidden = false;
        setMonthChromeAlpha(1f);
    }

    /** 页头：今年只说「11月」，其他年份「2025 / 7」+ 右下角小字相对时间 */
    private void setToolbarMonth(int yearMonth) {
        b.tvMonthTitle.setText(CheckInHeader.title(yearMonth, todayKey));
        String rel = CheckInHeader.relative(yearMonth, todayKey);
        b.tvMonthRelative.setText(rel);
        b.tvMonthRelative.setVisibility(rel == null ? View.GONE : View.VISIBLE);
        b.toolbar.setContentDescription(
                b.tvMonthTitle.getText() + (rel == null ? "" : "，" + rel) + "，打开年视图");
    }

    /**
     * 5.3：三态切换。MONTH ↔ WEEK 由月历折叠动画驱动头部；DETAIL 额外把
     * 书目列表换成逐日明细（同一容器里两套列表互斥）。
     */
    private void applyMode(CheckInCalendarState.Mode next, boolean animate) {
        if (b == null) {
            return;
        }
        mode = next;
        boolean detail = next == CheckInCalendarState.Mode.DETAIL;
        b.monthGrid.setWeekMode(next != CheckInCalendarState.Mode.MONTH, animate);
        applyCompactHeader(next != CheckInCalendarState.Mode.MONTH, animate);
        updateMonthListVisibility();
        b.ivMonthDaysArrow.setRotation(detail ? 180f : 0f);
        b.llMonthDaysHandle.setContentDescription(detail
                ? "收起逐日明细，回到书目" : "查看本月逐日明细");
    }

    /** 把手是手势之外的第二入口：MONTH/WEEK ↔ DETAIL */
    private void toggleMonthDays() {
        if (b == null || switcher == null || switcher.isAnimating()
                || switcher.isYearVisible()) {
            return;
        }
        if (mode == CheckInCalendarState.Mode.DETAIL) {
            applyMode(CheckInCalendarState.Mode.MONTH, true);
        } else {
            applyMode(CheckInCalendarState.Mode.DETAIL, true);
            loadMonth(b.monthGrid.getYearMonth(), false);
        }
    }

    /** 点逐日明细某天：选中该日、回到整月，并刷新当天的书单 */
    private void onMonthDayClick(CheckInDayDetail.Day day) {
        if (b == null || day == null || switcher == null || switcher.isAnimating()) {
            return;
        }
        selectedDayKey = day.dayKey;
        b.monthGrid.setSelectedDay(day.dayKey);
        applyMode(CheckInCalendarState.Mode.MONTH, true);
        loadMonth(b.monthGrid.getYearMonth(), false);
    }

    /**
     * 5.4：页头在两档高度间收起。真正的逐帧进度来自网格的同一条 220ms 动画，
     * 这里只负责把最终基准高度写回 InsetsHelper（动画中不 requestApplyInsets）。
     */
    private void applyCompactHeader(boolean compact, boolean animate) {
        if (!animate) {
            applyHeaderProgress(compact ? 1f : 0f);
        }
    }

    /** p = 0 整月 / 1 单周；Toolbar 48↔40dp、标题 17↔15sp、相对时间 11↔10sp、图例淡出 */
    private void applyHeaderProgress(float p) {
        if (b == null) {
            return;
        }
        float t = Math.max(0f, Math.min(1f, p));
        b.tvMonthTitle.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                titleFullPx + (titleCompactPx - titleFullPx) * t);
        b.tvMonthRelative.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                relFullPx + (relCompactPx - relFullPx) * t);
        int contentH = Math.round(toolbarFullPx + (toolbarCompactPx - toolbarFullPx) * t);
        int topInset = InsetsHelper.topInset(b.toolbar);
        ViewGroup.LayoutParams lp = b.toolbar.getLayoutParams();
        if (lp != null && lp.height != contentH + topInset) {
            lp.height = contentH + topInset;
            b.toolbar.setLayoutParams(lp);
        }
        float legendAlpha = 1f - t;
        b.llCheckinLegend.setAlpha(legendAlpha);
        b.tvLegendNote.setAlpha(legendAlpha);
        int legendVis = t >= 0.999f ? View.GONE : View.VISIBLE;
        if (b.llCheckinLegend.getVisibility() != legendVis) {
            b.llCheckinLegend.setVisibility(legendVis);
        }
        if (b.tvLegendNote.getVisibility() != legendVis) {
            b.tvLegendNote.setVisibility(legendVis);
        }
        // 动画的起点/终点各写一次基准，让 inset 监听器以后按新高度重算
        boolean compact = t >= 0.999f;
        if ((t <= 0.001f || compact) && compact != headerCompactBase) {
            headerCompactBase = compact;
            InsetsHelper.setBaseHeight(b.toolbar, compact ? toolbarCompactPx : toolbarFullPx);
        }
    }

    /**
     * 摘要行三单元（本月打卡 / 连续 / 补卡）：数字与单位走 StatsText.metric 的
     * span 方案，不再用两段 TextView 对基线；整句 summaryLine 留给读屏。
     */
    private void renderSummary(int[] summary, int[] streak, boolean todayChecked,
                               int roundDays) {
        int ym = b.monthGrid.getYearMonth();
        String note = CheckInUiState.summaryNote(ym, todayKey, todayChecked, roundDays);
        b.llCheckinSummary.setContentDescription(
                CheckInUiState.summaryLine(ym, summary, streak)
                        + (note == null ? "" : " · " + note));
        CheckInSummaryRow.fill(b.llCheckinSummary,
                CheckInUiState.summaryUnits(summary, streak, note));
    }

    /** 只用那条已有的按日查询数出 {本数, 补卡本数}，不新增 DAO */
    private static int[] dayMark(List<CheckIn> dayList, int dayKey,
                                 Map<Long, Integer> preciseSeq) {
        int[] mark = com.bai.bookshelf.util.CheckInDots.marks(dayList, preciseSeq).get(dayKey);
        return mark == null ? new int[]{0, 0} : mark;
    }

    /** 当天有记录的每本书 → 当前轮 seq（只收精确轮；模糊轮不进表 = 全量聚合） */
    private static Map<Long, Integer> preciseSeqOf(BookshelfApp app, List<CheckIn> rows) {
        Map<Long, Integer> out = new HashMap<>();
        Set<Long> ids = new HashSet<>();
        for (CheckIn c : rows) {
            ids.add(c.bookId);
        }
        for (long id : ids) {
            Book book = app.db().bookDao().getById(id);
            ReadingRecords.Record current = currentRecord(book);
            if (current != null && current.mode == ReadingRecords.MODE_PRECISE) {
                out.put(id, Math.max(1, current.seq));
            }
        }
        return out;
    }

    /** 当前该记进哪一轮：进行中那场优先，没有就取最后一场 */
    private static ReadingRecords.Record currentRecord(Book book) {
        if (book == null) {
            return null;
        }
        List<ReadingRecords.Record> records = ReadingRecords.load(book);
        if (records.isEmpty()) {
            return null;
        }
        ReadingRecords.Record ongoing = ReadingRecords.ongoingReading(records);
        return ongoing != null ? ongoing : ReadingRecords.lastOf(records);
    }

    /**
     * 选中日落在精确轮的日集合里时，这一轮已记几天（v5.9.1 第六步）。
     * 多本书同一天都命中时不猜，返回 0 = 摘要不追加。
     */
    private static int selectedRoundDays(List<Book> reading, List<CheckIn> all, int dayKey) {
        int hits = 0;
        int days = 0;
        for (Book book : reading) {
            ReadingRecords.Record current = currentRecord(book);
            if (current == null || current.mode != ReadingRecords.MODE_PRECISE) {
                continue;
            }
            int n = CheckInStore.roundDaysAt(all, book.id, current.seq, dayKey);
            if (n >= 0) {
                hits++;
                days = n;
            }
        }
        return hits == 1 ? days : 0;
    }

    // ==================== 取数 ====================

    private void loadMonth(int yearMonth) {
        loadMonth(yearMonth, true);
    }

    /** @param refreshGrid true = 切月/首载要重画整张月历；false = 只刷列表与小结 */
    private void loadMonth(int yearMonth, boolean refreshGrid) {
        if (b == null || !isAdded()) {
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        final int y = CheckInMonthGrid.yearOf(yearMonth);
        final int m = CheckInMonthGrid.monthOf(yearMonth);
        final int start = DateKeys.keyOf(y, m, 1);
        final int end = DateKeys.keyOf(y, m, DateKeys.daysInMonth(y, m));
        final int day = selectedDayKey;
        app.io(() -> {
            final List<CheckIn> monthList;
            final List<CheckIn> all;
            final List<CheckInAdapter.Row> rows = new ArrayList<>();
            final Map<Long, Integer> preciseSeq = new HashMap<>();
            final List<CheckInDayDetail.Day> monthDays;
            final int[] summary;
            final int[] streak;
            final int roundDays;
            final HolidayCalendar holidays;
            final boolean anyReading;
            try {
                monthList = app.db().checkInDao().getInRange(start, end);
                all = app.db().checkInDao().getInRange(0, 99999999);
                Set<Long> marked = new HashSet<>();
                for (CheckIn c : monthList) {
                    marked.add(c.bookId);
                }
                Set<Long> checked = new HashSet<>();
                for (CheckIn c : app.db().checkInDao().getByDay(day)) {
                    checked.add(c.bookId);
                }
                List<Book> reading = new ArrayList<>();
                // 逐日明细的书名取全部藏书（不只在读），删掉的书才能落成「（已删除）」
                Map<Long, String> bookTitles = new HashMap<>();
                for (Book book : BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                    bookTitles.put(book.id, book.title);
                    if (book.readStatus == Book.READING) {
                        reading.add(book);
                    }
                    if (!marked.contains(book.id)) {
                        continue;
                    }
                    ReadingRecords.Record current = currentRecord(book);
                    if (current != null && current.mode == ReadingRecords.MODE_PRECISE) {
                        preciseSeq.put(book.id, Math.max(1, current.seq));
                    }
                }
                reading.sort((x, z) -> Long.compare(z.updatedAt, x.updatedAt));
                anyReading = !reading.isEmpty();
                // 每本书的阅读天数走唯一口径：精确轮吃日集合，模糊轮按区间算
                Map<Long, Map<Integer, List<Integer>>> daysByBook = ReadDays.byBook(all);
                for (Book book : reading) {
                    CheckInAdapter.Row row = new CheckInAdapter.Row(
                            book, checked.contains(book.id));
                    List<BorrowRecord> open =
                            app.db().borrowRecordDao().findOpenListByBook(book.id);
                    if (!open.isEmpty()) {
                        row.borrower = open.get(0).borrower;
                    }
                    ReadingRecords.Record current = currentRecord(book);
                    if (current != null) {
                        row.seq = Math.max(1, current.seq);
                        Map<Integer, List<Integer>> perSeq = daysByBook.get(book.id);
                        row.readDays = ReadingRecords.readDays(current,
                                perSeq == null ? null : perSeq.get(row.seq), null);
                    }
                    // 选中日早于开读日：该书当天整行不出现；已打卡的历史行仍保留，允许撤销
                    int recorded = CheckInStore.recordedDays(all, book.id, row.seq);
                    if (CheckInUiState.hiddenBeforeStart(row.checked, day,
                            CheckInStore.earliestCheckInKey(book, recorded))) {
                        continue;
                    }
                    rows.add(row);
                }
                summary = CheckInStore.monthSummary(monthList, y, m);
                streak = CheckInStore.streakDays(all, todayKey);
                roundDays = selectedRoundDays(reading, all, day);
                holidays = HolidayStore.load(app, y);
                monthDays = CheckInDayDetail.ofMonth(monthList, bookTitles, y * 100 + m, todayKey);
            } catch (Exception ignored) {
                app.ui(this::restoreMonthChrome); // 别让转场藏起来的 chrome 永远停在 0
                return; // 库未就绪 / 修复中，等下一次回调
            }
            final Map<Integer, int[]> dayMarks =
                    com.bai.bookshelf.util.CheckInDots.marks(monthList, preciseSeq);
            final boolean todayChecked = CheckInHabit.dayKeys(all).contains(todayKey);
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                b.monthGrid.setHolidays(holidays);
                if (refreshGrid) {
                    b.monthGrid.setCheckInMarks(dayMarks);
                }
                renderSummary(summary, streak, todayChecked, roundDays);
                adapter.submit(rows, day, todayKey);
                monthDayAdapter.submit(monthDays, todayKey);
                readingEmpty = !anyReading;
                dayEmpty = anyReading && rows.isEmpty();
                renderEmptyState();
                updateMonthListVisibility();
                restoreMonthChrome();
                refreshHolidaysForMonth(y);
            });
        });
    }

    /** 打卡/撤销后只重算小结数字，不动网格与列表 */
    private void refreshSummaryOnly(int yearMonth) {
        BookshelfApp app = BookshelfApp.get();
        final int y = CheckInMonthGrid.yearOf(yearMonth);
        final int m = CheckInMonthGrid.monthOf(yearMonth);
        final int day = selectedDayKey;
        app.io(() -> {
            final int[] summary;
            final int[] streak;
            final int roundDays;
            final boolean todayChecked;
            try {
                summary = CheckInStore.monthSummary(app.db().checkInDao().getInRange(
                        DateKeys.keyOf(y, m, 1),
                        DateKeys.keyOf(y, m, DateKeys.daysInMonth(y, m))), y, m);
                List<CheckIn> all = app.db().checkInDao().getInRange(0, 99999999);
                streak = CheckInStore.streakDays(all, todayKey);
                List<Book> reading = new ArrayList<>();
                for (Book book : BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                    if (book.readStatus == Book.READING) {
                        reading.add(book);
                    }
                }
                roundDays = selectedRoundDays(reading, all, day);
                todayChecked = CheckInHabit.dayKeys(all).contains(todayKey);
            } catch (Exception ignored) {
                return;
            }
            app.ui(() -> {
                if (b != null) {
                    renderSummary(summary, streak, todayChecked, roundDays);
                }
            });
        });
    }

    // ==================== 交互 ====================

    @Override
    public void onSelectDay(int dayKey) {
        selectedDayKey = dayKey;
        loadMonth(b.monthGrid.getYearMonth(), false);
    }

    @Override
    public void onMoveToDay(int dayKey) {
        selectedDayKey = dayKey;
        b.monthGrid.setSelectedDay(dayKey);
        b.monthGrid.setYearMonth(CheckInMonthGrid.yearMonthOf(dayKey));
        loadMonth(b.monthGrid.getYearMonth(), false);
    }

    @Override
    public void onFutureDay(int dayKey) {
        // 未来日现在可以选中查看，只做无障碍播报，不再弹拒绝 Toast
        if (b != null) {
            b.getRoot().announceForAccessibility(
                    CheckInUiState.dayText(dayKey) + "，未来日期，暂不能打卡");
        }
    }

    @Override
    public void onMonthChanged(int yearMonth, int selectedDayKey) {
        if (b == null) {
            return;
        }
        this.selectedDayKey = selectedDayKey;
        setToolbarMonth(yearMonth);
        loadMonth(yearMonth);
    }

    /** 5.2：上滑收周、下滑展开；5.5：MONTH 再下滑进逐日明细，DETAIL 上滑回整月 */
    @Override
    public void onVerticalSwipe(boolean up) {
        if (b == null || switcher == null || switcher.isAnimating()
                || switcher.isYearVisible()) {
            return;
        }
        CheckInCalendarState.Mode next = up
                ? CheckInCalendarState.afterUp(mode)
                : CheckInCalendarState.afterDown(mode);
        if (next == mode) {
            return; // WEEK 上滑 / DETAIL 下滑：没有下一步
        }
        applyMode(next, true);
        if (next == CheckInCalendarState.Mode.DETAIL) {
            // 月份可能刚切过，进明细时用当前月重建一次逐日行
            loadMonth(b.monthGrid.getYearMonth(), false);
        }
    }

    @Override
    public void onToggle(int position, CheckInAdapter.Row row) {
        if (b == null || row == null) {
            return;
        }
        final int dayKey = selectedDayKey;
        if (!CheckInUiState.actionable(dayKey, todayKey) && !row.checked) {
            toast(CheckInUiState.rejectText(CheckInStore.Result.REJECTED_FUTURE));
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            final CheckInStore.Result result =
                    CheckInStore.toggle(app.db(), row.book, dayKey, true, row.seq);
            if (result != CheckInStore.Result.ADDED
                    && result != CheckInStore.Result.REMOVED) {
                app.ui(() -> toast(CheckInUiState.rejectText(result)));
                return;
            }
            final boolean added = result == CheckInStore.Result.ADDED;
            final int[] mark;
            try {
                List<CheckIn> dayRows = app.db().checkInDao().getByDay(dayKey);
                mark = dayMark(dayRows, dayKey, preciseSeqOf(app, dayRows));
            } catch (Exception ignored) {
                return;
            }
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                b.getRoot().performHapticFeedback(
                        android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                adapter.updateRow(position, added);
                b.monthGrid.setDayMarks(dayKey, mark[0], mark[1]);
                refreshSummaryOnly(b.monthGrid.getYearMonth());
                // 打卡与静默撤销都不再弹撤销条（Snackbar）；数据仍可反向 toggle 回退
            });
        });
    }

    @Override
    public void onBackfillLongPress(int dayKey) {
        toast(CheckInUiState.backfillHint(dayKey));
    }

    private void toast(String text) {
        if (getContext() != null) {
            Toast.makeText(getContext(), text, Toast.LENGTH_SHORT).show();
        }
    }

    // ==================== 返回 / 切 Tab 的刷新 ====================

    /** MainActivity 的统一可见性钩子：切 Tab 与二级页返回都走这里（节流合并重复调用） */
    @Override
    public void onTabShown() {
        reloadCurrentMonth();
    }

    /** 详情页改完书状态回到本页时，Fragment 实例的旧数据必须重取 */
    @Override
    public void onResume() {
        super.onResume();
        reloadCurrentMonth();
    }

    /** MainActivity 用 show/hide 复用四个 Tab，切回来时 onResume 不会再走一遍 */
    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        if (!hidden) {
            reloadCurrentMonth();
        }
    }

    /** 月视图节日表刷新：网络结果只回贴到仍在显示的那一年 */
    private void refreshHolidaysForMonth(int year) {
        if (b == null || getContext() == null) {
            return;
        }
        HolidayStore.refreshIfNeeded(requireContext(), year, (y, cal) -> {
            if (b == null) {
                return;
            }
            if (CheckInMonthGrid.yearOf(b.monthGrid.getYearMonth()) == y) {
                b.monthGrid.setHolidays(cal);
            }
        });
    }

    /** 年视图节日表刷新：CheckInYearView 自己会忽略年份对不上的迟到回调 */
    private void refreshHolidaysForYear(int year) {
        if (b == null || getContext() == null) {
            return;
        }
        HolidayStore.refreshIfNeeded(requireContext(), year, (y, cal) -> {
            if (b != null) {
                yearPanel.refreshHolidays();
            }
        });
    }

    private void reloadCurrentMonth() {
        if (b == null || !isAdded()) {
            return;
        }
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - lastReloadMs < 300L) {
            return; // 首载 / 连续切 Tab 的重复刷新并掉
        }
        lastReloadMs = now;
        todayKey = DateKeys.todayKey();
        b.monthGrid.refreshTodayKey(); // 只更新今天/未来分界，不动选中日
        setToolbarMonth(b.monthGrid.getYearMonth()); // 跨天后相对时间/年份归属可能变
        loadMonth(b.monthGrid.getYearMonth());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (b != null) {
            b.monthGrid.setCollapseProgressListener(null);
            b.monthGrid.setListener(null);
        }
        if (adapter != null) {
            adapter.setListener(null);
        }
        if (monthDayAdapter != null) {
            monthDayAdapter.setListener(null);
        }
        b = null;
    }

    /** 网格下方图例：圈 / 实心点 / 半实心点 / 方框各指什么，下面再补一句点的语义 */
    private void renderLegend() {
        CheckInLegendView.fill(b.llCheckinLegend,
                com.bai.bookshelf.util.CheckInLegend.monthItems());
        yearPanel.renderLegend();
        b.tvLegendNote.setText(com.bai.bookshelf.util.CheckInLegend.monthNote());
        // 收周时图例被折叠进度隐藏，这里不要在重新渲染时又亮起来
        b.tvLegendNote.setVisibility(mode == CheckInCalendarState.Mode.MONTH
                ? View.VISIBLE : View.GONE);
    }
}
