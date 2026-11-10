package com.bai.bookshelf.ui.checkin;

import static org.junit.Assert.assertEquals;

import com.bai.bookshelf.util.HolidayCalendar;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/** 月历读屏文案：从 MonthGridView 内联方法搬出来后锁住原有拼装顺序。 */
public class MonthCellTextTest {

    @Test
    public void 普通日期的状态按邻月选中今天未来顺序拼接() {
        assertEquals("11月5日，未打卡",
                MonthCellText.describe(null, 20261105, false, 0, 0,
                        false, false, false, false));
        assertEquals("11月5日，今天，未打卡",
                MonthCellText.describe(null, 20261105, true, 0, 0,
                        false, false, false, false));
        assertEquals("11月5日，已选中，今天，未打卡",
                MonthCellText.describe(null, 20261105, true, 0, 0,
                        false, true, false, false));
        assertEquals("11月5日，邻月，已选中，今天，未来日期，未打卡",
                MonthCellText.describe(null, 20261105, true, 0, 0,
                        true, true, true, false));
    }

    @Test
    public void 节日名放在状态之后且补卡单列() {
        Map<Integer, String> names = new HashMap<>();
        names.put(20261105, "立冬");
        HolidayCalendar hols = HolidayCalendar.ofFallback(names);
        assertEquals("11月5日，立冬，已打卡（3 本 · 含 1 笔补卡）",
                MonthCellText.describe(hols, 20261105, false, 3, 1,
                        false, false, false, false));
        assertEquals("11月5日，立冬，已打卡（2 本）",
                MonthCellText.describe(hols, 20261105, false, 2, 0,
                        false, false, false, false));
    }

    @Test
    public void 今天没有格内点数但已打卡时仍念已打卡() {
        assertEquals("11月5日，今天，已打卡",
                MonthCellText.describe(null, 20261105, true, 0, 0,
                        false, false, false, true));
        assertEquals("11月5日，今天，未打卡",
                MonthCellText.describe(null, 20261105, true, 0, 0,
                        false, false, false, false));
    }

    @Test
    public void 选择器按不可选已选参考区间的优先级朗读() {
        assertEquals("11月5日，今天，框选起点，已选",
                MonthCellText.describePick(20261105, true, false, 20261105, true, false));
        assertEquals("11月5日，框选起点，不可选",
                MonthCellText.describePick(20261105, false, true, 20261105, true, true));
        assertEquals("11月5日，参考区间",
                MonthCellText.describePick(20261105, false, false, 0, false, true));
        assertEquals("11月5日，未选",
                MonthCellText.describePick(20261105, false, false, 0, false, false));
    }

    @Test
    public void 月日不补零() {
        assertEquals("1月1日，未打卡",
                MonthCellText.describe(null, 20260101, false, 0, 0,
                        false, false, false, false));
        assertEquals("12月9日，未打卡",
                MonthCellText.describe(null, 20261209, false, 0, 0,
                        false, false, false, false));
    }
}
