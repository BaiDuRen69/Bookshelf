package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** 打卡页页头文案：固定「今天」= 20261107。 */
public class CheckInHeaderTest {

    private static final int TODAY = 20261107;

    @Test
    public void 当年只显示月() {
        assertEquals("11月", CheckInHeader.title(202611, TODAY));
        assertEquals("1月", CheckInHeader.title(202601, TODAY));
    }

    @Test
    public void 跨年带年份() {
        assertEquals("2025 / 7", CheckInHeader.title(202507, TODAY));
        assertEquals("2027 / 3", CheckInHeader.title(202703, TODAY));
    }

    @Test
    public void 过去按今天同号日算整天数() {
        assertEquals("488天前", CheckInHeader.relative(202507, TODAY));
    }

    @Test
    public void 未来按月内同号日算天数() {
        assertEquals("30天后", CheckInHeader.relative(202612, TODAY));
    }

    @Test
    public void 当前月不显示相对时间() {
        assertNull(CheckInHeader.relative(202611, TODAY));
    }

    @Test
    public void 该月没有这一天时夹到月末() {
        // 6 月没有 31 日，锚点取 6-30；6-30 → 7-31 正好 31 天
        assertEquals("31天前", CheckInHeader.relative(202606, 20260731));
        // 2 月没有 31 日，锚点取 2-28；2-28 → 3-31 正好 31 天
        assertEquals("31天前", CheckInHeader.relative(202602, 20260331));
        // 今天 8 号时按同号 8 号算，不做月末夹取
        assertEquals("28天前", CheckInHeader.relative(202602, 20260308));
    }

    @Test
    public void 非法年月返回空值() {
        assertEquals("", CheckInHeader.title(202600, TODAY));
        assertEquals("", CheckInHeader.title(202613, TODAY));
        assertEquals("", CheckInHeader.title(0, TODAY));
        assertNull(CheckInHeader.relative(202600, TODAY));
        assertNull(CheckInHeader.relative(202613, TODAY));
        assertNull(CheckInHeader.relative(202611, 0));
    }
}
