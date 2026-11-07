package com.bai.bookshelf.util;

/**
 * 年视图小日历的格子排布（纯 JVM，可单测）：周一开头、固定 6 行。
 *
 * <p>与 {@link CheckInMonthGrid#dayKeys(int, int)} 的区别：邻月补位返回 0 而不是
 * 邻月日期，小日历只画本月日号，前后留白。
 */
public final class MiniMonthLayout {

    public static final int CELLS = 42;

    private MiniMonthLayout() {
    }

    /** 长度恒为 42；0 = 空白（邻月/未用），其余为本月 yyyyMMdd */
    public static int[] cells(int year, int month1) {
        int[] out = new int[CELLS];
        if (year <= 0 || month1 < 1 || month1 > 12) {
            return out;
        }
        int lead = CheckInMonthGrid.leadDays(year, month1);
        int days = DateKeys.daysInMonth(year, month1);
        for (int day = 1; day <= days && lead + day - 1 < CELLS; day++) {
            out[lead + day - 1] = DateKeys.keyOf(year, month1, day);
        }
        return out;
    }
}
