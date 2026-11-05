package com.bai.bookshelf.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Calendar;
import java.util.Locale;

/** 打卡月视图的 ISO 周号与网格排布：纯 JVM，不依赖 Android。 */
public class CheckInMonthGridTest {

    @Test
    public void isoWeekOfYear_用户指定的两个边界() {
        assertEquals("2026-01-01 是 ISO 第 1 周", 1,
                DateKeys.isoWeekOfYear(20260101));
        assertEquals("2026-12-31 是 ISO 第 53 周", 53,
                DateKeys.isoWeekOfYear(20261231));
    }

    @Test
    public void isoWeekOfYear_周一周日边界() {
        assertEquals("1/4 周日仍属第 1 周", 1, DateKeys.isoWeekOfYear(20260104));
        assertEquals("1/5 周一起是第 2 周", 2, DateKeys.isoWeekOfYear(20260105));
        assertEquals(23, DateKeys.isoWeekOfYear(20260601));
    }

    @Test
    public void 二零二六年十一月周日开头补六天共六行() {
        assertEquals(6, CheckInMonthGrid.leadDays(2026, 11));
        assertEquals(6, CheckInMonthGrid.rows(2026, 11));
        int[] keys = CheckInMonthGrid.dayKeys(2026, 11);
        assertEquals(42, keys.length);
        assertEquals("首格是上一周周一 10/26", 20261026, keys[0]);
        assertEquals("第 7 格是 11/1", 20261101, keys[6]);
        assertEquals("末格补到 12/6", 20261206, keys[41]);
        assertTrue("10/28 属于邻月灰显", CheckInMonthGrid.isAdjacent(20261028, 2026, 11));
        assertFalse("11/5 属于本月", CheckInMonthGrid.isAdjacent(20261105, 2026, 11));
    }

    @Test
    public void 二零二六年六月周一开头不补天共五行() {
        assertEquals(0, CheckInMonthGrid.leadDays(2026, 6));
        assertEquals(5, CheckInMonthGrid.rows(2026, 6));
        int[] keys = CheckInMonthGrid.dayKeys(2026, 6);
        assertEquals(35, keys.length);
        assertEquals(20260601, keys[0]);
        assertEquals(20260705, keys[34]);
    }

    @Test
    public void 二零二六年二月补六天共五行() {
        assertEquals(6, CheckInMonthGrid.leadDays(2026, 2));
        assertEquals(5, CheckInMonthGrid.rows(2026, 2));
        int[] keys = CheckInMonthGrid.dayKeys(2026, 2);
        assertEquals(20260126, keys[0]);
    }

    @Test
    public void 网格首格一定是周一且日键连续() {
        for (int ym : new int[]{202511, 202602, 202606, 202611, 202612, 202701}) {
            int y = CheckInMonthGrid.yearOf(ym);
            int m = CheckInMonthGrid.monthOf(ym);
            int[] keys = CheckInMonthGrid.dayKeys(y, m);
            Calendar c = Calendar.getInstance(Locale.ROOT);
            c.clear();
            c.set(keys[0] / 10000, keys[0] / 100 % 100 - 1, keys[0] % 100, 12, 0, 0);
            assertEquals(ym + " 首格必须是周一", Calendar.MONDAY,
                    c.get(Calendar.DAY_OF_WEEK));
            for (int i = 1; i < keys.length; i++) {
                assertEquals(ym + " 第 " + i + " 格连续",
                        DateKeys.shiftDayKey(keys[i - 1], 1), keys[i]);
            }
        }
    }

    @Test
    public void 每行一个ISO周号且在合法范围() {
        int[] keys = CheckInMonthGrid.dayKeys(2026, 11);
        int[] weeks = CheckInMonthGrid.weekNumbers(keys);
        assertEquals(6, weeks.length);
        assertEquals(44, weeks[0]);
        for (int w : weeks) {
            assertTrue("周号应在 1..53，实际 " + w, w >= 1 && w <= 53);
        }
    }

    @Test
    public void shiftMonth跨年与倒退() {
        assertEquals(202612, CheckInMonthGrid.shiftMonth(202611, 1));
        assertEquals(202701, CheckInMonthGrid.shiftMonth(202612, 1));
        assertEquals(202512, CheckInMonthGrid.shiftMonth(202601, -1));
        assertEquals(202511, CheckInMonthGrid.shiftMonth(202611, -12));
    }

    @Test
    public void 所有月份都不超过六行() {
        for (int y = 2024; y <= 2028; y++) {
            for (int m = 1; m <= 12; m++) {
                int rows = CheckInMonthGrid.rows(y, m);
                assertTrue(y + "-" + m + " 行数 " + rows, rows >= 4 && rows <= 6);
                assertEquals(rows * 7, CheckInMonthGrid.dayKeys(y, m).length);
            }
        }
    }

    @Test
    public void 年月日打包与标题() {
        assertEquals(202611, CheckInMonthGrid.yearMonthOf(20261105));
        assertEquals(2026, CheckInMonthGrid.yearOf(202611));
        assertEquals(11, CheckInMonthGrid.monthOf(202611));
        assertEquals("2026年11月", CheckInMonthGrid.titleOf(202611));
        assertArrayEquals(new int[]{2026, 11},
                new int[]{CheckInMonthGrid.yearOf(202611),
                        CheckInMonthGrid.monthOf(202611)});
    }
}
