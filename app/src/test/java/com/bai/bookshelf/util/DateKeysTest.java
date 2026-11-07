package com.bai.bookshelf.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class DateKeysTest {

    @Test
    public void purchaseSpanExpandsYearAndMonth() {
        assertArrayEquals(new int[]{20260101, 20261231}, DateKeys.purchaseSpan("2026"));
        assertArrayEquals(new int[]{20260201, 20260228}, DateKeys.purchaseSpan("2026-02"));
        assertArrayEquals(new int[]{20260215, 20260215}, DateKeys.purchaseSpan("2026/2/15"));
        assertArrayEquals(new int[]{20261201, 20261231}, DateKeys.purchaseSpan("2026年12月"));
    }

    @Test
    public void purchaseSpanReturnsNullForBadInput() {
        assertNull(DateKeys.purchaseSpan(null));
        assertNull(DateKeys.purchaseSpan(""));
        assertNull(DateKeys.purchaseSpan("没有日期"));
    }

    @Test
    public void parseKeyAcceptsDashAndSlash() {
        assertEquals(20261027, DateKeys.parseKey("2026-10-27"));
        assertEquals(20260103, DateKeys.parseKey("2026/1/3"));
        assertEquals(0, DateKeys.parseKey(null));
        assertEquals(0, DateKeys.parseKey("2026-13-01"));
        assertEquals(0, DateKeys.parseKey("2026"));
    }

    @Test
    public void shiftDayKeyCrossesMonthAndYear() {
        assertEquals(20251231, DateKeys.shiftDayKey(20260101, -1));
        assertEquals(20261031, DateKeys.shiftDayKey(20261001, 30));
        assertEquals(20260301, DateKeys.shiftDayKey(20260228, 1));
    }

    @Test
    public void parseStartKeyForPartialDates() {
        assertEquals(20210101, DateKeys.parseStartKey("2021"));
        assertEquals(20210301, DateKeys.parseStartKey("2021-03"));
        assertEquals(20210305, DateKeys.parseStartKey("2021-03-05"));
        assertEquals(0, DateKeys.parseStartKey(""));
    }

    @Test
    public void daysInMonthHandlesLeapYear() {
        assertEquals(29, DateKeys.daysInMonth(2024, 2));
        assertEquals(28, DateKeys.daysInMonth(2026, 2));
        assertEquals(31, DateKeys.daysInMonth(2026, 12));
    }

    @Test
    public void formatKeyPadsMonthAndDay() {
        assertEquals("2026-10-29", DateKeys.formatKey(20261029));
        assertEquals("2026-01-03", DateKeys.formatKey(20260103));
    }

    @Test
    public void daysBetweenInclusiveCountsBothEnds() {
        assertEquals(1, DateKeys.daysBetweenInclusive(20261029, 20261029));
        assertEquals(31, DateKeys.daysBetweenInclusive(20260101, 20260131));
        assertEquals(4, DateKeys.daysBetweenInclusive(20260227, 20260302));
    }

    @Test
    public void keyRangeExpandsInclusiveAndAscending() {
        assertEquals(java.util.Arrays.asList(20261103, 20261104, 20261105),
                DateKeys.keyRange(20261103, 20261105));
        assertEquals("同一天就是一天", java.util.Collections.singletonList(20261103),
                DateKeys.keyRange(20261103, 20261103));
        assertEquals("跨月要按日历走，不是按整数加", java.util.Arrays.asList(
                        20260227, 20260228, 20260301, 20260302),
                DateKeys.keyRange(20260227, 20260302));
    }

    @Test
    public void keyRangeReturnsEmptyForReversedOrBadKeys() {
        assertEquals(java.util.Collections.emptyList(), DateKeys.keyRange(20261105, 20261103));
        assertEquals(java.util.Collections.emptyList(), DateKeys.keyRange(0, 20261103));
        assertEquals(java.util.Collections.emptyList(), DateKeys.keyRange(20261103, 0));
    }

    @Test
    public void presetRangeFollowsCalendar() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        now.clear();
        now.set(2026, 9, 29); // 2026-10-29
        assertArrayEquals(new int[]{20261001, 20261029}, DateKeys.presetRange(0, now));
        assertArrayEquals(new int[]{20260801, 20261029}, DateKeys.presetRange(1, now));
        assertArrayEquals(new int[]{20260101, 20261029}, DateKeys.presetRange(2, now));
        assertArrayEquals(new int[]{20250101, 20251231}, DateKeys.presetRange(3, now));
    }
}
