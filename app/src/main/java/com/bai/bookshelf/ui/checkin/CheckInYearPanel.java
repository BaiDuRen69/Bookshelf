package com.bai.bookshelf.ui.checkin;

import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.CheckInStore;
import com.bai.bookshelf.util.CheckInUiState;
import com.bai.bookshelf.util.CheckInYearGrid;

import java.util.ArrayList;
import java.util.List;

/**
 * 年视图面板：承接原 CheckInYearActivity 的年份标题、今年小结、图例与 12 月网格。
 * 点月 / 点格 / 切年 / 「今」全部回调给 Fragment，不再 setResult / finish。
 */
public final class CheckInYearPanel {

    public interface Listener {
        void onPick(int yearMonth, int dayKey);

        void onYearChanged(int year);
    }

    private final View root;
    private final TextView yearTitle;
    private final LinearLayout summary;
    private final LinearLayout legend;
    private final CheckInYearView yearView;
    private List<CheckIn> all = new ArrayList<>();
    @Nullable
    private Listener listener;

    public CheckInYearPanel(View root) {
        this.root = root;
        yearTitle = root.findViewById(R.id.tv_year_title);
        summary = root.findViewById(R.id.ll_year_summary);
        legend = root.findViewById(R.id.ll_year_legend);
        yearView = root.findViewById(R.id.year_view);
        yearView.setListener(new CheckInYearView.Listener() {
            @Override
            public void onPick(int yearMonth, int dayKey) {
                if (listener != null) {
                    listener.onPick(yearMonth, dayKey);
                }
            }

            @Override
            public void onYearChanged(int year) {
                updateHeader(year);
                if (listener != null) {
                    listener.onYearChanged(year);
                }
            }
        });
    }

    public View root() {
        return root;
    }

    public CheckInYearView yearView() {
        return yearView;
    }

    public void setListener(@Nullable Listener l) {
        this.listener = l;
    }

    public void renderLegend() {
        CheckInLegendView.fill(legend, com.bai.bookshelf.util.CheckInLegend.yearItems());
    }

    /** Fragment 在 io 线程取完全量打卡后调用：算年份边界 → bind 网格 → 刷标题 */
    public void bind(int yearMonth, List<CheckIn> checkIns, int todayKey) {
        all = checkIns == null ? new ArrayList<>() : new ArrayList<>(checkIns);
        int currentYear = todayKey / 10000;
        int min = CheckInYearGrid.minYear(all, currentYear);
        int max = CheckInYearGrid.maxYear(currentYear);
        int year = Math.max(min, Math.min(max, yearMonth / 100));
        yearView.bind(year, min, max, all, todayKey);
        updateHeader(year);
    }

    public void updateHeader(int year) {
        yearTitle.setText(String.valueOf(year));
        int[] s = CheckInStore.yearSummary(all, year);
        summary.setContentDescription(CheckInUiState.yearLine(s));
        CheckInSummaryRow.fill(summary, CheckInUiState.yearUnits(s));
    }

    /** 「今」在年视图里的语义：回月视图并选中今天 */
    public int[] todayPick(int todayKey) {
        return new int[]{CheckInMonthGrid.yearMonthOf(todayKey), todayKey};
    }
}
