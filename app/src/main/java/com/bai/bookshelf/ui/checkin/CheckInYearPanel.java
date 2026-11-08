package com.bai.bookshelf.ui.checkin;

import android.graphics.Rect;
import android.view.TouchDelegate;
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
import com.bai.bookshelf.util.HolidayStore;
import com.bai.bookshelf.util.InsetsHelper;

import java.util.ArrayList;
import java.util.List;

/**
 * 年视图面板：承接原 CheckInYearActivity 的年份标题、今年小结、图例与 12 月网格。
 * 点月 / 点格 / 切年 / 「今」全部回调给 Fragment，不再 setResult / finish。
 */
public final class CheckInYearPanel {

    /** 标题点击区的最小高度（视觉高约 28dp，只扩上下） */
    private static final int TITLE_TOUCH_DP = 48;

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
    private int minYear;
    private int maxYear;
    private int todayYear;
    @Nullable
    private Listener listener;

    public CheckInYearPanel(View root) {
        this.root = root;
        yearTitle = root.findViewById(R.id.tv_year_title);
        summary = root.findViewById(R.id.ll_year_summary);
        legend = root.findViewById(R.id.ll_year_legend);
        yearView = root.findViewById(R.id.year_view);
        InsetsHelper.applyDockReservePadding(yearView);
        // v5.9.2：点年份标题直接跳到目标年，不用一路左右滑
        yearTitle.setOnClickListener(v -> showYearPicker());
        // 标题缩到文字宽后只有 ~28dp 高：用 TouchDelegate 上下补到 48dp。
        // 这里不能用 post —— 视图 attach 时 RunQueue 就会执行，那时还没量测，高宽都是 0。
        yearTitle.addOnLayoutChangeListener((v, left, top, right, bottom,
                                             oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top <= 0) {
                return;
            }
            Rect hit = new Rect();
            yearTitle.getHitRect(hit);
            int target = Math.round(TITLE_TOUCH_DP
                    * root.getResources().getDisplayMetrics().density);
            int grow = Math.max(0, target - hit.height()) / 2;
            if (grow <= 0) {
                return;
            }
            hit.inset(0, -grow);
            root.setTouchDelegate(new TouchDelegate(hit, yearTitle));
        });
        // 切年新层同步取该年节日表：内存命中 API/兜底，网络结果回来后 refreshHolidays()
        yearView.setHolidaySource(year -> HolidayStore.get(root.getContext(), year));
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

    /** 网络结果写回 HolidayStore 内存后重绘当前激活层 */
    public void refreshHolidays() {
        yearView.refreshHolidays();
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
        minYear = min;
        maxYear = max;
        todayYear = currentYear;
        int year = Math.max(min, Math.min(max, yearMonth / 100));
        yearView.bind(year, min, max, all, todayKey);
        updateHeader(year);
    }

    public void updateHeader(int year) {
        yearTitle.setText(String.valueOf(year));
        yearTitle.setContentDescription(year + "年，点击选择年份");
        int[] s = CheckInStore.yearSummary(all, year);
        summary.setContentDescription(CheckInUiState.yearLine(s));
        CheckInSummaryRow.fill(summary, CheckInUiState.yearUnits(s));
    }

    /** 年份快选：候选项与左右滑切的年份边界完全一致，最新一年排最上面 */
    private void showYearPicker() {
        int[] years = CheckInYearGrid.yearsDescending(minYear, maxYear);
        if (years.length == 0) {
            return;
        }
        String[] labels = new String[years.length];
        int checked = 0;
        for (int i = 0; i < years.length; i++) {
            labels[i] = years[i] == todayYear ? years[i] + " 年（今年）" : years[i] + " 年";
            if (years[i] == yearView.getYear()) {
                checked = i;
            }
        }
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(root.getContext())
                .setTitle("选择年份")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    yearView.jumpToYear(years[which]);
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 「今」在年视图里的语义：回月视图并选中今天 */
    public int[] todayPick(int todayKey) {
        return new int[]{CheckInMonthGrid.yearMonthOf(todayKey), todayKey};
    }
}
