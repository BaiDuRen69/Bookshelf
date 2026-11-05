package com.bai.bookshelf.ui.stats;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

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

    // ==================== metric：数字 + 单位（v5.9.1） ====================

    // android.text 的 span 在纯 JVM 单测里是 stub，所以断言落在「拆分与降级」这段
    // 纯函数上：单位段边界唯一决定 span 边界，比例常量与真机渲染另看。

    @Test
    public void metricPartsSplitsValueAndUnit() {
        assertArrayEquals(new String[]{"19", "种"}, StatsText.metricParts("19", "种"));
        // span 的起点就是数字段长度，边界由这里唯一决定
        assertEquals(2, StatsText.metricParts("19", "种")[0].length());
    }

    @Test
    public void metricMinusOneSaysNoDataWithoutSpan() {
        CharSequence cs = StatsText.metric("-1", "种");
        assertEquals("数据不足", cs.toString());
        assertTrue("数据不足不该带 span", !(cs instanceof android.text.Spannable));
        assertEquals("数据不足", StatsText.metric(null, "种"));
        assertEquals("数据不足", StatsText.metric("  ", "种"));
    }

    @Test
    public void metricPartsKeepsDecimalsAndThousands() {
        assertArrayEquals(new String[]{"¥0.06", "/页"}, StatsText.metricParts("¥0.06", "/页"));
        assertArrayEquals(new String[]{"¥1,240", " 已花"}, StatsText.metricParts("¥1,240", " 已花"));
        assertArrayEquals(new String[]{"128.5", "页"}, StatsText.metricParts("128.5", "页"));
    }

    @Test
    public void metricWithoutUnitIsPlainText() {
        CharSequence cs = StatsText.metric("¥1,240", "");
        assertEquals("¥1,240", cs.toString());
        assertTrue(!(cs instanceof android.text.Spannable));
        assertEquals("¥1,240", StatsText.metric("¥1,240", null));
    }

    @Test
    public void metricColorVariantKeepsPlainTextForNoData() {
        // 「数据不足」与无单位都不该被降色：没有 span 可加，直接回纯文本
        assertEquals("数据不足", StatsText.metric("-1", "种", 0xFF888888));
        assertEquals("¥1,240", StatsText.metric("¥1,240", "", 0xFF888888));
    }

    @Test
    public void metricUnitScaleIsRatioNotPixels() {
        // 只依赖比例：系统字体 1.0 / 1.3 / 2.0 下数字与单位的相对大小不变，
        // 也不出现绝对字号，所以不会再靠 dp 去顶基线
        assertEquals(0.45f, StatsText.UNIT_SCALE, 0.0001f);
        assertTrue(StatsText.UNIT_SCALE > 0f && StatsText.UNIT_SCALE < 1f);
    }
}
