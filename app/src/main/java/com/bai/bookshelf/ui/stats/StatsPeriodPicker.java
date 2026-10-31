package com.bai.bookshelf.ui.stats;

import com.bai.bookshelf.util.Typo;
import android.app.Activity;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.DialogPickPeriodBinding;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.Calendar;
import java.util.List;

/**
 * 统计页的年/月快速选择器（v4.20）：
 * 月选择 = 年份快捷条 + 12 个月格子（未来月份禁用）；
 * 年选择 = 数据里出现过的年份网格。
 */
public class StatsPeriodPicker {

    public interface Host {
        /** 可选年份（倒序）；通常为购买记录里出现过的年份 ∪ 当前年 */
        List<Integer> availableYears();

        void onMonthPicked(int year, int month0);

        void onYearPicked(int year);
    }

    private final Activity activity;
    private final Host host;
    private AlertDialog dialog;

    public StatsPeriodPicker(Activity activity, Host host) {
        this.activity = activity;
        this.host = host;
    }

    /** 弹出月份选择器 */
    public void showMonth(int selectedYear, int selectedMonth) {
        DialogPickPeriodBinding db =
                DialogPickPeriodBinding.inflate(activity.getLayoutInflater());
        db.hsYearChips.setVisibility(View.VISIBLE);
        db.llYearGrid.setVisibility(View.GONE);
        db.gridMonths.setVisibility(View.VISIBLE);

        Calendar now = Calendar.getInstance();
        int nowYear = now.get(Calendar.YEAR);
        int nowMonth = now.get(Calendar.MONTH);

        // 当前选中的年份（点年份 Chip 会改它，随后点月份即跳到该年该月）
        final int[] pickedYear = {selectedYear};
        final int primary = ThemeManager.resolveColor(activity, ThemeManager.ATTR_COLOR_PRIMARY);
        final int textMain = ThemeManager.resolveColor(activity, R.attr.on_surface);

        final TextView[] monthViews = new TextView[]{
                db.m1, db.m2, db.m3, db.m4, db.m5, db.m6,
                db.m7, db.m8, db.m9, db.m10, db.m11, db.m12};

        // 年份变化时刷新月份格子的可用状态（未来月份禁用）与当前选中高亮
        Runnable refreshMonths = () -> {
            for (int i = 0; i < 12; i++) {
                final int m = i;
                TextView tv = monthViews[i];
                boolean future = pickedYear[0] > nowYear
                        || (pickedYear[0] == nowYear && m > nowMonth);
                tv.setEnabled(!future);
                tv.setAlpha(future ? 0.35f : 1f);
                boolean cur = pickedYear[0] == selectedYear && m == selectedMonth;
                tv.setTextColor(cur ? primary : textMain);
                tv.setTypeface(cur ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                tv.setOnClickListener(v -> {
                    host.onMonthPicked(pickedYear[0], m);
                    dismiss();
                });
            }
        };

        // 年份快捷条：数据里出现过的年份 + 当前年，倒序
        List<Integer> years = host.availableYears();
        ChipGroup group = db.chipYearGroup;
        for (int y : years) {
            Chip chip = new Chip(activity);
            chip.setId(View.generateViewId());
            chip.setText(String.valueOf(y));
            chip.setCheckable(true);
            group.addView(chip);
            if (y == selectedYear) {
                group.check(chip.getId());
            }
        }
        group.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty()) {
                return;
            }
            View v = g.findViewById(ids.get(0));
            if (v instanceof Chip) {
                try {
                    pickedYear[0] = Integer.parseInt(((Chip) v).getText().toString());
                    refreshMonths.run();
                } catch (NumberFormatException ignored) {
                    // 理论上不会发生，忽略
                }
            }
        });

        refreshMonths.run();

        dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("选择月份")
                .setView(db.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.show();
    }

    /** 弹出年份选择器 */
    public void showYear(int selectedYear) {
        DialogPickPeriodBinding db =
                DialogPickPeriodBinding.inflate(activity.getLayoutInflater());
        db.hsYearChips.setVisibility(View.GONE);
        db.gridMonths.setVisibility(View.GONE);
        db.llYearGrid.setVisibility(View.VISIBLE);

        List<Integer> years = host.availableYears();
        int perRow = 4;
        for (int i = 0; i < years.size(); i += perRow) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int j = 0; j < perRow && i + j < years.size(); j++) {
                final int y = years.get(i + j);
                TextView tv = new TextView(activity);
                tv.setText(String.valueOf(y));
                Typo.text(tv, R.dimen.text_body);
                tv.setGravity(Gravity.CENTER);
                tv.setTextColor(y == selectedYear
                        ? ThemeManager.resolveColor(activity, ThemeManager.ATTR_COLOR_PRIMARY)
                        : ThemeManager.resolveColor(activity, R.attr.on_surface));
                if (y == selectedYear) {
                    tv.setTypeface(Typeface.DEFAULT_BOLD);
                }
                tv.setBackgroundResource(R.drawable.bg_chip);
                tv.setPadding(dp(8), dp(12), dp(8), dp(12));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
                lp.setMargins(dp(3), dp(3), dp(3), dp(3));
                tv.setLayoutParams(lp);
                tv.setOnClickListener(v -> {
                    host.onYearPicked(y);
                    dismiss();
                });
                row.addView(tv);
            }
            db.llYearGrid.addView(row);
        }

        dialog = new MaterialAlertDialogBuilder(activity)
                .setTitle("选择年份")
                .setView(db.getRoot())
                .setNegativeButton(R.string.action_cancel, null)
                .create();
        dialog.show();
    }

    private void dismiss() {
        if (dialog != null) {
            dialog.dismiss();
        }
    }

    private int dp(int v) {
        return Math.round(v * activity.getResources().getDisplayMetrics().density);
    }
}
