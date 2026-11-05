package com.bai.bookshelf.ui.stats;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Top 分布卡分段模型（v5.9.1 三榜合一）：文案与非法值兜底。
 */
public class DistSegmentTest {

    @Test
    public void fieldLabelCoversThreeSegments() {
        assertEquals("作者", DistSegment.fieldLabel(DistSegment.AUTHORS));
        assertEquals("出版社", DistSegment.fieldLabel(DistSegment.PUBLISHERS));
        assertEquals("国家/地区", DistSegment.fieldLabel(DistSegment.COUNTRIES));
    }

    @Test
    public void outOfRangeFallsBackToAuthor() {
        assertEquals(DistSegment.AUTHORS, DistSegment.clamp(-1));
        assertEquals(DistSegment.AUTHORS, DistSegment.clamp(3));
        assertEquals(DistSegment.AUTHORS, DistSegment.clamp(99));
    }

    @Test
    public void validSegmentsSurviveClamp() {
        assertEquals(DistSegment.PUBLISHERS, DistSegment.clamp(DistSegment.PUBLISHERS));
        assertEquals(DistSegment.COUNTRIES, DistSegment.clamp(DistSegment.COUNTRIES));
    }

    @Test
    public void moreLabelNamesTheDestination() {
        assertEquals("全部作者", DistSegment.moreLabel(DistSegment.AUTHORS));
        assertEquals("全部出版社", DistSegment.moreLabel(DistSegment.PUBLISHERS));
        // 非法值也要给出可用文案，入口不能空着
        assertEquals("全部作者", DistSegment.moreLabel(7));
    }

    @Test
    public void defaultSegmentIsAuthor() {
        assertEquals(DistSegment.AUTHORS, DistSegment.DEFAULT);
    }
}
