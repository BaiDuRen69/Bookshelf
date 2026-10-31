package com.bai.bookshelf.ui.stats;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HeatmapMathTest {

    @Test
    public void colorForUsesFiveLevels() {
        int primary = 0xFF336699;
        int empty = 0xFFEEEEEE;
        assertEquals(empty, HeatmapMath.colorFor(0, primary, empty));
        assertEquals(empty, HeatmapMath.colorFor(-1, primary, empty));
        assertEquals((120 << 24) | (primary & 0x00FFFFFF),
                HeatmapMath.colorFor(2, primary, empty));
        assertEquals((255 << 24) | (primary & 0x00FFFFFF),
                HeatmapMath.colorFor(4, primary, empty));
        assertEquals((255 << 24) | (primary & 0x00FFFFFF),
                HeatmapMath.colorFor(99, primary, empty));
    }

    @Test
    public void firstOfMonthIndexMatchesYearLayout() {
        assertEquals(0, HeatmapMath.dayIndexOfFirstOfMonth(2026, 0));
        assertEquals(31, HeatmapMath.dayIndexOfFirstOfMonth(2026, 1));
        assertEquals(31, HeatmapMath.dayIndexOfFirstOfMonth(2024, 1));
        assertEquals(334, HeatmapMath.dayIndexOfFirstOfMonth(2026, 11));
    }
}
