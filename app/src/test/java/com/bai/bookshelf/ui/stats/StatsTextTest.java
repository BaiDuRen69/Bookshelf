package com.bai.bookshelf.ui.stats;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class StatsTextTest {

    @Test
    public void parseYmdAcceptsStandardDate() {
        assertArrayEquals(new int[]{2026, 9, 29}, StatsText.parseYmd("2026-10-29"));
    }

    @Test
    public void parseYmdRejectsDirtyInput() {
        assertNull(StatsText.parseYmd(null));
        assertNull(StatsText.parseYmd(""));
        assertNull(StatsText.parseYmd("2026/10/29"));
        assertNull(StatsText.parseYmd("2026-13-01"));
        assertNull(StatsText.parseYmd("2026-00-10"));
        assertNull(StatsText.parseYmd("2026-10-32"));
    }

    @Test
    public void formatMoneyDropsTrailingZeros() {
        assertEquals("¥0", StatsText.formatMoney(0));
        assertEquals("¥0", StatsText.formatMoney(-3));
        assertEquals("¥128", StatsText.formatMoney(128));
        assertEquals("¥128.50", StatsText.formatMoney(128.5));
        assertEquals("¥128.46", StatsText.formatMoney(128.456));
    }

    @Test
    public void weekLabelUsesMondayFirst() {
        // 2026-10-29 是周四
        assertEquals("周四", StatsText.weekLabel(2026, 9, 29));
        // 2026-11-01 是周日
        assertEquals("周日", StatsText.weekLabel(2026, 10, 1));
    }

    @Test
    public void dayDetailTextIncludesCountOrEmptyHint() {
        assertEquals("2026年10月29日 周四 · 购买 3 本",
                StatsText.dayDetailText(2026, 9, 29, 3));
        assertEquals("2026年10月29日 周四 · 没有购买记录",
                StatsText.dayDetailText(2026, 9, 29, 0));
    }

    @Test
    public void daysInMonthHandlesLeapYear() {
        assertEquals(29, StatsText.daysInMonth(2024, 1));
        assertEquals(28, StatsText.daysInMonth(2025, 1));
        assertEquals(31, StatsText.daysInMonth(2026, 9));
    }
}
