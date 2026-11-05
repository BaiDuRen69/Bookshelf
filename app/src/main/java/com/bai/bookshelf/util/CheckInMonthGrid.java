package com.bai.bookshelf.util;

import java.util.Calendar;
import java.util.Locale;

/**
 * 打卡月视图的排布规则（纯 JVM，可单测）：周一开头、前后补邻月、最多 6 行。
 *
 * <p>刻意不复用 ui/widget/CalendarPicker 的排布代码：硬约束要求 CalendarPicker
 * 的现有调用点与观感不变，第一版允许重复，第二期再谈合并。
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

    public static int rows(int year, int month1) {
        int total = leadDays(year, month1) + DateKeys.daysInMonth(year, month1);
        return Math.min(MAX_ROWS, (total + DAYS_PER_WEEK - 1) / DAYS_PER_WEEK);
    }

    /** 长度 rows×7，含前后邻月补齐；第一个一定是周一 */
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
}
