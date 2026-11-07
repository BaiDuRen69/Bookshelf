package com.bai.bookshelf.ui.checkin;

import android.os.Bundle;
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
import com.bai.bookshelf.util.CheckInHabit;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.CheckInStore;
import com.bai.bookshelf.util.CheckInYearGrid;
import com.bai.bookshelf.util.CheckInUiState;
import com.bai.bookshelf.util.DateKeys;
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
        implements MonthGridView.Listener, CheckInAdapter.Listener, TabRefresh {

    private FragmentCheckinBinding b;
    private CheckInAdapter adapter;
    private int selectedDayKey;
    private int todayKey;
    private long lastReloadMs;
    private CheckInViewSwitcher switcher;
    private List<CheckIn> yearAll = new ArrayList<>();

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

        switcher = new CheckInViewSwitcher(b.viewSwitchHost, b.monthContent,
                b.yearContent, b.yearView);
        b.yearView.setListener(new CheckInYearView.Listener() {
            @Override
            public void onPick(int yearMonth, int dayKey) {
                exitYearToMonth(yearMonth, dayKey);
            }

            @Override
            public void onYearChanged(int year) {
                updateYearHeader(year);
            }
        });

        setToolbarMonth(b.monthGrid.getYearMonth());
        b.toolbar.setOnClickListener(v -> toggleYearView());
        b.tvYear.setOnClickListener(v -> toggleYearView());
        b.fabToday.setOnClickListener(v -> {
            if (switcher.isAnimating()) {
                return;
            }
            if (switcher.isYearVisible()) {
                switcher.toMonth(b.monthGrid.selectedCellCenterIn(b.viewSwitchHost), () -> {
                    selectedDayKey = DateKeys.todayKey();
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

        b.emptyState.tvEmptyTitle.setText("还没有在读的书");
        b.emptyState.tvEmptyDesc.setText("把一本书标成在读，就能每天打卡");
        b.emptyState.btnEmptyCta.setText("打开书架");
        b.emptyState.btnEmptyCta.setOnClickListener(v -> {
            if (getActivity() instanceof MainActivity) {
                ((MainActivity) requireActivity()).showTab(MainActivity.TAB_SHELF);
            }
        });

        b.monthGrid.setListener(this);
        renderLegend();
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
        final int ym = b.monthGrid.getYearMonth();
        final int[] pivot = b.monthGrid.selectedCellCenterIn(b.viewSwitchHost);
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<CheckIn> all;
            try {
                all = app.db().checkInDao().getInRange(0, 99999999);
            } catch (Exception ignored) {
                all = new ArrayList<>();
            }
            final List<CheckIn> list = all;
            final int today = DateKeys.todayKey();
            final int currentYear = today / 10000;
            final int min = CheckInYearGrid.minYear(list, currentYear);
            final int max = CheckInYearGrid.maxYear(currentYear);
            app.ui(() -> {
                if (b == null || !isAdded()) {
                    return;
                }
                yearAll = list;
                int year = Math.max(min, Math.min(max, ym / 100));
                b.yearView.bind(year, min, max, list, today);
                updateYearHeader(year);
                switcher.toYear(pivot, null);
            });
        });
    }

    /** 点某月：卡片放大、其它淡出，动画结束后回月视图并定位该月（点格则同时选中该日） */
    private void exitYearToMonth(int yearMonth, int dayKey) {
        if (b == null || switcher == null || switcher.isAnimating()) {
            return;
        }
        switcher.zoomMonthCard(CheckInMonthGrid.monthOf(yearMonth), () -> {
            if (b == null) {
                return;
            }
            if (dayKey > 0) {
                selectedDayKey = dayKey;
                b.monthGrid.setSelectedDay(dayKey);
            }
            if (b.monthGrid.getYearMonth() != yearMonth) {
                b.monthGrid.setYearMonth(yearMonth); // 内部触发 onMonthChanged → loadMonth
            } else {
                loadMonth(yearMonth, false);
            }
            switcher.toMonth(b.monthGrid.selectedCellCenterIn(b.viewSwitchHost), null);
        });
    }

    private void updateYearHeader(int year) {
        if (b == null) {
            return;
        }
        b.tvYearTitle.setText(String.valueOf(year));
        int[] summary = CheckInStore.yearSummary(yearAll, year);
        b.llYearSummary.setContentDescription(CheckInUiState.yearLine(summary));
        CheckInSummaryRow.fill(b.llYearSummary, CheckInUiState.yearUnits(summary));
    }

    /** 页头分层：主标题只留「11月」，年份降到副标题，右侧不再挤「今天」 */
    private void setToolbarMonth(int yearMonth) {
        b.toolbar.setTitle(CheckInMonthGrid.monthOf(yearMonth) + "月");
        b.toolbar.setSubtitle(String.valueOf(CheckInMonthGrid.yearOf(yearMonth)));
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
            final int[] summary;
            final int[] streak;
            final int roundDays;
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
                for (Book book : BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
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
                    rows.add(row);
                }
                summary = CheckInStore.monthSummary(monthList, y, m);
                streak = CheckInStore.streakDays(all, todayKey);
                roundDays = selectedRoundDays(reading, all, day);
            } catch (Exception ignored) {
                return; // 库未就绪 / 修复中，等下一次回调
            }
            final Map<Integer, int[]> dayMarks =
                    com.bai.bookshelf.util.CheckInDots.marks(monthList, preciseSeq);
            final boolean todayChecked = CheckInHabit.dayKeys(all).contains(todayKey);
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                if (refreshGrid) {
                    b.monthGrid.setCheckInMarks(dayMarks);
                }
                renderSummary(summary, streak, todayChecked, roundDays);
                adapter.submit(rows, day, todayKey);
                boolean empty = rows.isEmpty();
                b.emptyState.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
                b.rvCheckin.setVisibility(empty ? View.GONE : View.VISIBLE);
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
    public void onMonthChanged(int yearMonth) {
        if (b == null) {
            return;
        }
        setToolbarMonth(yearMonth);
        loadMonth(yearMonth);
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
        loadMonth(b.monthGrid.getYearMonth());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (b != null) {
            b.monthGrid.setListener(null);
        }
        if (adapter != null) {
            adapter.setListener(null);
        }
        b = null;
    }

    /** 网格下方图例：圈 / 实心点 / 半实心点 / 方框各指什么，下面再补一句点的语义 */
    private void renderLegend() {
        CheckInLegendView.fill(b.llCheckinLegend,
                com.bai.bookshelf.util.CheckInLegend.monthItems());
        CheckInLegendView.fill(b.llYearLegend,
                com.bai.bookshelf.util.CheckInLegend.yearItems());
        b.tvLegendNote.setText(com.bai.bookshelf.util.CheckInLegend.monthNote());
        b.tvLegendNote.setVisibility(View.VISIBLE);
    }
}
