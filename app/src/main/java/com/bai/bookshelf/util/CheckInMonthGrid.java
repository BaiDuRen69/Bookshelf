package com.bai.bookshelf.util;

import java.util.Calendar;
import java.util.Locale;

/**
 * 打卡月视图的排布规则（纯 JVM，可单测）：周一开头、前后补邻月、固定 6 行。
 *
 * <p>固定 6 行是为了切月时月历高度不变：5 行的月份在第 6 行补下月日期（灰显），
 * 不再让整张月历随月份抽动。刻意不复用 ui/widget/CalendarPicker 的排布代码。
 */
public final class CheckInMonthGrid {

    public static final int DAYS_PER_WEEK = 7;
    public static final int MAX_ROWS = 6;

    private CheckInMonthGrid() {
    }

    /** 月初所在周的周一，距月初几天（周一开头：周一=0 … 周日=6） */
    public static int leadDays(int year, int month1) {
        Calendar c = Calendar.getInstance(Locale.ROOT);
        c.clear();
        c.set(year, month1 - 1, 1, 12, 0, 0);
        return (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
    }

    /** 固定 6 行：切月不再改变月历高度 */
    public static int rows(int year, int month1) {
        return MAX_ROWS;
    }

    /** 长度恒为 42，含前后邻月补齐；第一个一定是周一 */
    public static int[] dayKeys(int year, int month1) {
        int count = rows(year, month1) * DAYS_PER_WEEK;
        int start = DateKeys.shiftDayKey(DateKeys.keyOf(year, month1, 1),
                -leadDays(year, month1));
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            out[i] = DateKeys.shiftDayKey(start, i);
        }
        return out;
    }

    /** 每行一个 ISO 周号，取该行第一格（周一） */
    public static int[] weekNumbers(int[] dayKeys) {
        int rows = dayKeys.length / DAYS_PER_WEEK;
        int[] out = new int[rows];
        for (int r = 0; r < rows; r++) {
            out[r] = DateKeys.isoWeekOfYear(dayKeys[r * DAYS_PER_WEEK]);
        }
        return out;
    }

    /** 该日是否属于指定年月（false = 本月，true = 邻月灰显） */
    public static boolean isAdjacent(int dayKey, int year, int month1) {
        return dayKey / 100 != year * 100 + month1;
    }

    /** yyyymm 打包值按月偏移，跨年正确 */
    public static int shiftMonth(int yearMonth, int delta) {
        Calendar c = Calendar.getInstance(Locale.ROOT);
        c.clear();
        c.set(yearMonth / 100, yearMonth % 100 - 1, 1, 12, 0, 0);
        c.add(Calendar.MONTH, delta);
        return c.get(Calendar.YEAR) * 100 + (c.get(Calendar.MONTH) + 1);
    }

    public static int yearMonthOf(int dayKey) {
        return dayKey / 100;
    }

    public static int yearOf(int yearMonth) {
        return yearMonth / 100;
    }

    public static int monthOf(int yearMonth) {
        return yearMonth % 100;
    }

    public static String titleOf(int yearMonth) {
        return String.format(Locale.ROOT, "%d年%d月", yearOf(yearMonth), monthOf(yearMonth));
    }

    /** 切月后把选中日夹到新月份：同号，没有这一天就取月末；无选中返回 0 */
    public static int clampDayKey(int dayKey, int yearMonth) {
        int year = yearOf(yearMonth);
        int month = monthOf(yearMonth);
        if (dayKey <= 0 || year <= 0 || month < 1 || month > 12) {
            return 0;
        }
        int day = Math.min(dayKey % 100, DateKeys.daysInMonth(year, month));
        return DateKeys.keyOf(year, month, day);
    }
}
