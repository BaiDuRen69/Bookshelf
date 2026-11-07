package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 图例数据：四项固定、文案与用色档位不漂移。 */
public class CheckInLegendTest {

    @Test
    public void 月视图四项图例() {
        CheckInLegend.Item[] items = CheckInLegend.monthItems();
        assertEquals(4, items.length);
        assertEquals("○", items[0].glyph);
        assertEquals("今天", items[0].label);
        assertEquals(CheckInLegend.TONE_TODAY, items[0].tone);
        assertEquals("已打卡", items[1].label);
        assertEquals("含补卡", items[2].label);
        assertEquals("▭", items[3].glyph);
        assertEquals("已选中", items[3].label);
        assertEquals(CheckInLegend.TONE_OUTLINE, items[3].tone);
    }

    @Test
    public void 年视图只有一项() {
        CheckInLegend.Item[] items = CheckInLegend.yearItems();
        assertEquals(1, items.length);
        assertEquals("●", items[0].glyph);
        assertEquals("该月有打卡", items[0].label);
    }
}
