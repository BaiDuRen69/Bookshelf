package com.bai.bookshelf.ui.checkin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
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
import com.bai.bookshelf.ui.stats.StatsText;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.CheckInHabit;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.CheckInStore;
import com.bai.bookshelf.util.CheckInUiState;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.ThemeManager;
import com.bai.bookshelf.util.UndoBar;

import java.util.ArrayList;
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
        implements MonthGridView.Listener, CheckInAdapter.Listener {

    private FragmentCheckinBinding b;
    private CheckInAdapter adapter;
    private int selectedDayKey;
    private int todayKey;
    private ActivityResultLauncher<Intent> yearLauncher;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 年视图返回：点月份标题只定位月，点小日历格同时选中该日
        yearLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(), result -> {
                    if (result.getResultCode() != Activity.RESULT_OK
                            || result.getData() == null || b == null) {
                        return;
                    }
                    Intent data = result.getData();
                    int ym = data.getIntExtra(
                            CheckInYearActivity.EXTRA_RESULT_YEAR_MONTH, 0);
                    int day = data.getIntExtra(
                            CheckInYearActivity.EXTRA_RESULT_DAY_KEY, 0);
                    if (ym == 0) {
                        return;
                    }
                    if (day > 0) {
                        selectedDayKey = day;
                        b.monthGrid.setSelectedDay(day);
                    }
                    b.monthGrid.setYearMonth(ym);
                    loadMonth(ym);
                });
    }

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

        setToolbarMonth(b.monthGrid.getYearMonth());
        b.toolbar.setOnClickListener(v -> openYearView());
        b.tvYear.setOnClickListener(v -> openYearView());
        b.fabToday.setOnClickListener(v -> b.monthGrid.moveToToday());

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
        BookshelfApp.get().whenDbReady(() -> loadMonth(b.monthGrid.getYearMonth()));
    }

    private void openYearView() {
        Intent intent = new Intent(requireContext(), CheckInYearActivity.class);
        intent.putExtra(CheckInYearActivity.EXTRA_YEAR_MONTH, b.monthGrid.getYearMonth());
        yearLauncher.launch(intent);
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
    private void renderSummary(int[] summary, int[] streak, boolean todayChecked) {
        b.llCheckinSummary.removeAllViews();
        int ym = b.monthGrid.getYearMonth();
        b.llCheckinSummary.setContentDescription(
                CheckInUiState.summaryLine(ym, summary, streak));
        int variant = ThemeManager.resolveColor(requireContext(), R.attr.on_surface_variant);
        CheckInUiState.Unit[] units =
                CheckInUiState.summaryUnits(summary, streak,
                        CheckInUiState.todayNote(ym, todayKey, todayChecked));
        for (int i = 0; i < units.length; i++) {
            b.llCheckinSummary.addView(summaryUnitView(units[i], variant), summaryUnitLp(i));
        }
    }

    private View summaryUnitView(CheckInUiState.Unit u, int variant) {
        LinearLayout cell = new LinearLayout(requireContext());
        cell.setOrientation(LinearLayout.VERTICAL);
        cell.setGravity(Gravity.CENTER_HORIZONTAL);
        cell.addView(overline(u.label));
        TextView value = new TextView(requireContext());
        value.setTextAppearance(requireContext(), R.style.Text_Bookshelf_Metric_Small);
        value.setGravity(Gravity.CENTER_HORIZONTAL);
        value.setText(StatsText.metric(u.value, u.unit, variant));
        if (!u.accent) {
            // 连续 0 天不该抢眼
            value.setTextColor(variant);
        }
        cell.addView(value, wrapCenter());
        if (u.note != null) {
            LinearLayout.LayoutParams noteLp = wrapCenter();
            noteLp.topMargin = getResources().getDimensionPixelSize(R.dimen.space_sm);
            cell.addView(overline(u.note), noteLp);
        }
        return cell;
    }

    private TextView overline(String text) {
        TextView tv = new TextView(requireContext());
        tv.setTextAppearance(requireContext(), R.style.Text_Bookshelf_Overline);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        tv.setText(text);
        return tv;
    }

    private LinearLayout.LayoutParams wrapCenter() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /** 三单元等分，单元间 space_md */
    private LinearLayout.LayoutParams summaryUnitLp(int index) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        if (index > 0) {
            lp.setMarginStart(getResources().getDimensionPixelSize(R.dimen.space_md));
        }
        return lp;
    }

    /** 只用那条已有的按日查询数出 {本数, 补卡本数}，不新增 DAO */
    private static int[] dayMark(List<CheckIn> dayList, int dayKey) {
        int[] mark = com.bai.bookshelf.util.CheckInDots.marks(dayList).get(dayKey);
        return mark == null ? new int[]{0, 0} : mark;
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
            final int[] summary;
            final int[] streak;
            try {
                monthList = app.db().checkInDao().getInRange(start, end);
                all = app.db().checkInDao().getInRange(0, 99999999);
                Set<Long> checked = new HashSet<>();
                for (CheckIn c : app.db().checkInDao().getByDay(day)) {
                    checked.add(c.bookId);
                }
                List<Book> reading = new ArrayList<>();
                for (Book book : BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                    if (book.readStatus == Book.READING) {
                        reading.add(book);
                    }
                }
                reading.sort((x, z) -> Long.compare(z.updatedAt, x.updatedAt));
                for (Book book : reading) {
                    CheckInAdapter.Row row = new CheckInAdapter.Row(
                            book, checked.contains(book.id));
                    List<BorrowRecord> open =
                            app.db().borrowRecordDao().findOpenListByBook(book.id);
                    if (!open.isEmpty()) {
                        row.borrower = open.get(0).borrower;
                    }
                    rows.add(row);
                }
                summary = CheckInStore.monthSummary(monthList, y, m);
                streak = CheckInStore.streakDays(all, todayKey);
            } catch (Exception ignored) {
                return; // 库未就绪 / 修复中，等下一次回调
            }
            final Map<Integer, int[]> dayMarks =
                    com.bai.bookshelf.util.CheckInDots.marks(monthList);
            final boolean todayChecked = CheckInHabit.dayKeys(all).contains(todayKey);
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                if (refreshGrid) {
                    b.monthGrid.setCheckInMarks(dayMarks);
                }
                renderSummary(summary, streak, todayChecked);
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
        app.io(() -> {
            final int[] summary;
            final int[] streak;
            final boolean todayChecked;
            try {
                summary = CheckInStore.monthSummary(app.db().checkInDao().getInRange(
                        DateKeys.keyOf(y, m, 1),
                        DateKeys.keyOf(y, m, DateKeys.daysInMonth(y, m))), y, m);
                List<CheckIn> all = app.db().checkInDao().getInRange(0, 99999999);
                streak = CheckInStore.streakDays(all, todayKey);
                todayChecked = CheckInHabit.dayKeys(all).contains(todayKey);
            } catch (Exception ignored) {
                return;
            }
            app.ui(() -> {
                if (b != null) {
                    renderSummary(summary, streak, todayChecked);
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
        toast(CheckInUiState.rejectText(CheckInStore.Result.REJECTED_FUTURE));
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
                    CheckInStore.toggle(app.db(), row.book, dayKey, true);
            if (result != CheckInStore.Result.ADDED
                    && result != CheckInStore.Result.REMOVED) {
                app.ui(() -> toast(CheckInUiState.rejectText(result)));
                return;
            }
            final boolean added = result == CheckInStore.Result.ADDED;
            final int[] mark;
            try {
                mark = dayMark(app.db().checkInDao().getByDay(dayKey), dayKey);
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

                final boolean wasAdded = added;
                Runnable undo = () -> app.io(() -> {
                    CheckInStore.toggle(app.db(), row.book, dayKey, true);
                    final int[] left;
                    try {
                        left = dayMark(app.db().checkInDao().getByDay(dayKey), dayKey);
                    } catch (Exception ignored) {
                        return;
                    }
                    app.ui(() -> {
                        if (b == null) {
                            return;
                        }
                        adapter.updateRow(position, !wasAdded);
                        b.monthGrid.setDayMarks(dayKey, left[0], left[1]);
                        refreshSummaryOnly(b.monthGrid.getYearMonth());
                        UndoBar.offer(b.getRoot(), "已撤销打卡", null, null);
                    });
                });
                String message = CheckInUiState.undoMessage(
                        true, CheckInStore.isBackfill(dayKey, todayKey),
                        row.book.title, dayKey);
                UndoBar.offer(b.getRoot(), message, undo, null);
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

    /** 网格下方图例：圈 / 实心点 / 半实心点 / 方框各指什么 */
    private void renderLegend() {
        CheckInLegendView.fill(b.llCheckinLegend,
                com.bai.bookshelf.util.CheckInLegend.monthItems());
    }
}
