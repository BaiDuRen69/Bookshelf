package com.bai.bookshelf.ui.checkin;

import android.os.Bundle;

import com.bai.bookshelf.databinding.ActivityCheckinYearBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.DateKeys;

import java.util.Locale;

/**
 * 年视图（一期第 ② 步骨架）：先提供可进入、可返回、带年份的真实页面，
 * 让「点月份标题 → 年视图」在本步就能编译并在真机验证。
 * 第 ④ 步再填 12 月缩略网格、左右滑切年份与点月回传。
 */
public class CheckInYearActivity extends BaseActivity {

    public static final String EXTRA_YEAR_MONTH = "checkin_year_month";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityCheckinYearBinding b = ActivityCheckinYearBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());

        int ym = getIntent().getIntExtra(EXTRA_YEAR_MONTH,
                CheckInMonthGrid.yearMonthOf(DateKeys.todayKey()));
        b.tvYear.setText(String.format(Locale.ROOT, "%d 年",
                CheckInMonthGrid.yearOf(ym)));
    }
}
