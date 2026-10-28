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
    public void daysInMonthHandlesLeapYear() {
        assertEquals(29, DateKeys.daysInMonth(2024, 2));
        assertEquals(28, DateKeys.daysInMonth(2026, 2));
        assertEquals(31, DateKeys.daysInMonth(2026, 12));
    }
}
