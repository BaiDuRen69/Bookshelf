package com.bai.bookshelf.ui.checkin;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.databinding.ActivityCheckinYearBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.CheckInLegend;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.CheckInStore;
import com.bai.bookshelf.util.CheckInUiState;
import com.bai.bookshelf.util.CheckInYearGrid;
import com.bai.bookshelf.util.DateKeys;

import java.util.Collections;
import java.util.List;

/**
 * 年视图（第 ④ 步）：12 个月缩略网格、左右滑切年份、点月/点格回月视图定位。
 * 年份下界取库里最早一条打卡，上界为当前年 +1；越界只回弹不换年。
 */
public class CheckInYearActivity extends BaseActivity {

    public static final String EXTRA_YEAR_MONTH = "checkin_year_month";
    public static final String EXTRA_RESULT_YEAR_MONTH = "checkin_result_year_month";
    public static final String EXTRA_RESULT_DAY_KEY = "checkin_result_day_key";

    private ActivityCheckinYearBinding b;
    private List<CheckIn> all = Collections.emptyList();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityCheckinYearBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());
        CheckInLegendView.fill(b.llYearLegend, CheckInLegend.yearItems());
        b.fabToday.setOnClickListener(v -> {
            int today = DateKeys.todayKey();
            finishWith(CheckInMonthGrid.yearMonthOf(today), today);
        });
        b.yearView.setListener(new CheckInYearView.Listener() {
            @Override
            public void onPick(int yearMonth, int dayKey) {
                finishWith(yearMonth, dayKey);
            }

            @Override
            public void onYearChanged(int year) {
                updateHeader(year);
            }
        });
        BookshelfApp.get().whenDbReady(this::load);
    }

    private void load() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            final List<CheckIn> list;
            try {
                list = app.db().checkInDao().getInRange(0, 99999999);
            } catch (Exception ignored) {
                return;
            }
            final int today = DateKeys.todayKey();
            final int currentYear = today / 10000;
            final int min = CheckInYearGrid.minYear(list, currentYear);
            final int max = CheckInYearGrid.maxYear(currentYear);
            final int requested = getIntent().getIntExtra(EXTRA_YEAR_MONTH,
                    CheckInMonthGrid.yearMonthOf(today));
            app.ui(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                all = list;
                int year = Math.max(min, Math.min(max, requested / 100));
                b.yearView.bind(year, min, max, list, today);
                updateHeader(year);
            });
        });
    }

    private void updateHeader(int year) {
        if (b == null) {
            return;
        }
        b.tvYear.setText(String.valueOf(year));
        b.tvYearSummary.setText(
                CheckInUiState.yearLine(CheckInStore.yearSummary(all, year)));
    }

    private void finishWith(int yearMonth, int dayKey) {
        Intent data = new Intent();
        data.putExtra(EXTRA_RESULT_YEAR_MONTH, yearMonth);
        data.putExtra(EXTRA_RESULT_DAY_KEY, dayKey);
        setResult(Activity.RESULT_OK, data);
        finish();
    }
}
