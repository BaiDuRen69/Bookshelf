package com.bai.bookshelf.util;

import static com.bai.bookshelf.util.CheckInCalendarState.Mode.DETAIL;
import static com.bai.bookshelf.util.CheckInCalendarState.Mode.MONTH;
import static com.bai.bookshelf.util.CheckInCalendarState.Mode.WEEK;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 日历三档高度的手势迁移表。 */
public class CheckInCalendarStateTest {

    @Test
    public void 上拉逐档收起到周视图为止() {
        assertEquals(MONTH, CheckInCalendarState.afterUp(DETAIL));
        assertEquals(WEEK, CheckInCalendarState.afterUp(MONTH));
        assertEquals(WEEK, CheckInCalendarState.afterUp(WEEK));
    }

    @Test
    public void 下拉逐档展开到详情为止() {
        assertEquals(MONTH, CheckInCalendarState.afterDown(WEEK));
        assertEquals(DETAIL, CheckInCalendarState.afterDown(MONTH));
        assertEquals(DETAIL, CheckInCalendarState.afterDown(DETAIL));
    }

    @Test
    public void 只有整月视图保留完整页头() {
        assertFalse(CheckInCalendarState.compactHeader(MONTH));
        assertTrue(CheckInCalendarState.compactHeader(WEEK));
        assertTrue(CheckInCalendarState.compactHeader(DETAIL));
    }

    @Test
    public void 空状态回到月视图() {
        assertEquals(MONTH, CheckInCalendarState.afterUp(null));
        assertEquals(MONTH, CheckInCalendarState.afterDown(null));
        assertFalse(CheckInCalendarState.compactHeader(null));
    }

    @Test
    public void 折叠进度两端取整月与单周高度() {
        assertEquals(900, CheckInCalendarState.heightAt(900, 300, 0f));
        assertEquals(300, CheckInCalendarState.heightAt(900, 300, 1f));
        assertEquals(600, CheckInCalendarState.heightAt(900, 300, 0.5f));
    }

    @Test
    public void 折叠进度越界时夹在两端() {
        assertEquals(900, CheckInCalendarState.heightAt(900, 300, -0.4f));
        assertEquals(300, CheckInCalendarState.heightAt(900, 300, 1.7f));
    }
}
