package com.bai.bookshelf.ui.checkin;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.databinding.FragmentCheckinBinding;
import com.bai.bookshelf.ui.MainActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.CheckInStore;
import com.bai.bookshelf.util.DateKeys;

import java.util.List;
import java.util.Map;

/**
 * 打卡页（一期第 ② 步骨架）：月份标题（点进年视图）+ 月视图 + 在读书籍列表位 + 「今」。
 *
 * <p>打卡写入 / 撤销按钮在第 ③ 步接入；本步已经把当月打卡点、在读书空态、
 * 选日、点邻月跳月、切月重取数全部打通，便于真机逐项验证。
 */
public class CheckInFragment extends Fragment implements MonthGridView.Listener {

    private FragmentCheckinBinding b;
    private int selectedDayKey;

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentCheckinBinding.inflate(inflater, container, false);
        return b.getRoot();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        selectedDayKey = DateKeys.todayKey();

        b.toolbar.setTitle(CheckInMonthGrid.titleOf(b.monthGrid.getYearMonth()));
        b.toolbar.setOnClickListener(v -> {
            Intent intent = new Intent(requireContext(), CheckInYearActivity.class);
            intent.putExtra(CheckInYearActivity.EXTRA_YEAR_MONTH,
                    b.monthGrid.getYearMonth());
            startActivity(intent);
        });
        b.tvToday.setOnClickListener(v -> b.monthGrid.moveToToday());
        b.fabToday.setOnClickListener(v -> b.monthGrid.moveToToday());
        b.rvCheckin.setLayoutManager(new LinearLayoutManager(requireContext()));

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

    /** 取当月打卡点与「在读书」数量；冷启动等库就绪后才跑 */
    private void loadMonth(int yearMonth) {
        if (b == null || !isAdded()) {
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        final int y = CheckInMonthGrid.yearOf(yearMonth);
        final int m = CheckInMonthGrid.monthOf(yearMonth);
        final int start = DateKeys.keyOf(y, m, 1);
        final int end = DateKeys.keyOf(y, m, DateKeys.daysInMonth(y, m));
        app.io(() -> {
            final List<CheckIn> list;
            final int readingCount;
            try {
                list = app.db().checkInDao().getInRange(start, end);
                int count = 0;
                for (Book book : BookSanitizer.normalizeAll(app.db().bookDao().getAll())) {
                    if (book.readStatus == Book.READING) {
                        count++;
                    }
                }
                readingCount = count;
            } catch (Exception ignored) {
                return; // 库未就绪 / 修复中，等下一次回调
            }
            Map<Integer, Integer> counts = CheckInStore.countByDayInRange(list);
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                b.monthGrid.setCheckInCounts(counts);
                boolean empty = readingCount == 0;
                b.emptyState.getRoot().setVisibility(empty ? View.VISIBLE : View.GONE);
                b.rvCheckin.setVisibility(empty ? View.GONE : View.VISIBLE);
            });
        });
    }

    @Override
    public void onSelectDay(int dayKey) {
        selectedDayKey = dayKey; // 第 ③ 步据此刷新打卡列表
    }

    @Override
    public void onMoveToDay(int dayKey) {
        selectedDayKey = dayKey;
        b.monthGrid.setSelectedDay(dayKey);
        b.monthGrid.setYearMonth(CheckInMonthGrid.yearMonthOf(dayKey));
    }

    @Override
    public void onMonthChanged(int yearMonth) {
        if (b == null) {
            return;
        }
        b.toolbar.setTitle(CheckInMonthGrid.titleOf(yearMonth));
        loadMonth(yearMonth);
    }

    public int getSelectedDayKey() {
        return selectedDayKey;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (b != null) {
            b.monthGrid.setListener(null);
        }
        b = null;
    }
}
