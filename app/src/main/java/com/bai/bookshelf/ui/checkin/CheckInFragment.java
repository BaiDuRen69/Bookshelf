package com.bai.bookshelf.ui.checkin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.databinding.FragmentCheckinBinding;
import com.bai.bookshelf.ui.MainActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.CheckInStore;
import com.bai.bookshelf.util.CheckInUiState;
import com.bai.bookshelf.util.DateKeys;
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

        b.toolbar.setTitle(CheckInMonthGrid.titleOf(b.monthGrid.getYearMonth()));
        b.toolbar.setOnClickListener(v -> openYearView());
        b.tvYear.setOnClickListener(v -> openYearView());
        b.tvToday.setOnClickListener(v -> b.monthGrid.moveToToday());
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
        BookshelfApp.get().whenDbReady(() -> loadMonth(b.monthGrid.getYearMonth()));
    }

    private void openYearView() {
        Intent intent = new Intent(requireContext(), CheckInYearActivity.class);
        intent.putExtra(CheckInYearActivity.EXTRA_YEAR_MONTH, b.monthGrid.getYearMonth());
        yearLauncher.launch(intent);
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
            Map<Integer, Integer> counts = CheckInStore.countByDayInRange(monthList);
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                if (refreshGrid) {
                    b.monthGrid.setCheckInCounts(counts);
                }
                b.tvCheckinSummary.setText(
                        CheckInUiState.summaryLine(yearMonth, summary, streak));
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
            try {
                summary = CheckInStore.monthSummary(app.db().checkInDao().getInRange(
                        DateKeys.keyOf(y, m, 1),
                        DateKeys.keyOf(y, m, DateKeys.daysInMonth(y, m))), y, m);
                streak = CheckInStore.streakDays(
                        app.db().checkInDao().getInRange(0, 99999999), todayKey);
            } catch (Exception ignored) {
                return;
            }
            app.ui(() -> {
                if (b != null) {
                    b.tvCheckinSummary.setText(
                            CheckInUiState.summaryLine(yearMonth, summary, streak));
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
    public void onMonthChanged(int yearMonth) {
        if (b == null) {
            return;
        }
        b.toolbar.setTitle(CheckInMonthGrid.titleOf(yearMonth));
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
            final boolean dayHasAny;
            try {
                dayHasAny = !app.db().checkInDao().getByDay(dayKey).isEmpty();
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
                b.monthGrid.setDayChecked(dayKey, dayHasAny);
                refreshSummaryOnly(b.monthGrid.getYearMonth());

                final boolean wasAdded = added;
                Runnable undo = () -> app.io(() -> {
                    CheckInStore.toggle(app.db(), row.book, dayKey, true);
                    final boolean anyLeft;
                    try {
                        anyLeft = !app.db().checkInDao().getByDay(dayKey).isEmpty();
                    } catch (Exception ignored) {
                        return;
                    }
                    app.ui(() -> {
                        if (b == null) {
                            return;
                        }
                        adapter.updateRow(position, !wasAdded);
                        b.monthGrid.setDayChecked(dayKey, anyLeft);
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
}
