package com.bai.bookshelf.ui.checkin;

import androidx.annotation.Nullable;

import com.bai.bookshelf.util.HolidayCalendar;

import java.util.Collections;
import java.util.Map;

/**
 * 一个月的日历数据：节日表 + 打卡点。
 *
 * <p>从 MonthGridView 的嵌套类搬出来：页面 {@link MonthPage} 只消费它，不该为了拿一份数据
 * 回头引用门面。依赖方向因此是 MonthPage → MonthData。
 */
public final class MonthData {

    @Nullable
    public final HolidayCalendar holidays;

    public final Map<Integer, int[]> dayMarks;

    public MonthData(@Nullable HolidayCalendar holidays,
                     @Nullable Map<Integer, int[]> dayMarks) {
        this.holidays = holidays;
        this.dayMarks = dayMarks == null ? Collections.<Integer, int[]>emptyMap() : dayMarks;
    }
}
