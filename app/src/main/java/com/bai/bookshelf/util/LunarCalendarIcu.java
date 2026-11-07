package com.bai.bookshelf.util;

import android.icu.util.Calendar;
import android.icu.util.ChineseCalendar;
import android.icu.util.TimeZone;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 平台 ICU 农历换算（API 24+ 引入，minSdk 24 正好覆盖），不引入任何新依赖。
 *
 * <p>android.icu 的类在 JVM 单测里是 stub，只会在真机运行；单测请用
 * {@link LunarCalendar} 注入自己的 Provider。公历时刻取当天正午，避开时区边界。
 */
public final class LunarCalendarIcu implements LunarCalendar.Provider {

    private static final int MIN_YEAR = 1900;
    private static final int MAX_YEAR = 2100;

    private final Map<Integer, int[]> cache = new HashMap<>();

    @Override
    public int[] lunar(int dayKey) {
        if (dayKey <= 0) {
            return null;
        }
        int[] hit = cache.get(dayKey);
        if (hit != null) {
            return hit;
        }
        int year = dayKey / 10000;
        int month = dayKey / 100 % 100;
        int day = dayKey % 100;
        if (year < MIN_YEAR || year > MAX_YEAR || month < 1 || month > 12
                || day < 1 || day > 31) {
            return null;
        }
        try {
            java.util.Calendar gregorian = java.util.Calendar.getInstance();
            gregorian.clear();
            gregorian.set(year, month - 1, day, 12, 0, 0);
            ChineseCalendar lunar = new ChineseCalendar(TimeZone.getDefault(), Locale.CHINA);
            lunar.setTimeInMillis(gregorian.getTimeInMillis());
            int[] value = new int[]{
                    lunar.get(Calendar.MONTH) + 1,
                    lunar.get(Calendar.DAY_OF_MONTH),
                    lunar.get(Calendar.IS_LEAP_MONTH) == 1 ? 1 : 0};
            cache.put(dayKey, value);
            return value;
        } catch (Throwable ignored) {
            return null; // 平台不支持时退成「不显示农历」，不能让日历崩
        }
    }
}
