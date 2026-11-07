package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.CheckIn;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/** 阅读习惯事实源 + 年视图年份边界：纯 JVM。 */
public class CheckInAnalyticsTest {

    private static CheckIn ci(long bookId, int dayKey) {
        CheckIn c = new CheckIn();
        c.bookId = bookId;
        c.dayKey = dayKey;
        return c;
    }

    @Test
    public void 某本书打卡三次才启用打卡事实源() {
        List<CheckIn> two = Arrays.asList(ci(1, 20261101), ci(1, 20261102));
        assertFalse(CheckInHabit.useCheckIns(two));
        List<CheckIn> three = Arrays.asList(
                ci(1, 20261101), ci(1, 20261102), ci(1, 20261103));
        assertTrue(CheckInHabit.useCheckIns(three));
        List<CheckIn> spread = Arrays.asList(
                ci(1, 20261101), ci(2, 20261101), ci(3, 20261101));
        assertFalse("分散在三本书各 1 条不算", CheckInHabit.useCheckIns(spread));
        assertFalse(CheckInHabit.useCheckIns(null));
        assertFalse(CheckInHabit.useCheckIns(Collections.emptyList()));
    }

    @Test
    public void 打卡自然日去重且一天多本只算一天() {
        Collection<Integer> days = CheckInHabit.dayKeys(Arrays.asList(
                ci(1, 20261103), ci(2, 20261103), ci(1, 20261105)));
        assertEquals(2, days.size());
        assertTrue(days.contains(20261103));
        assertTrue(days.contains(20261105));
        assertTrue(CheckInHabit.dayKeys(null).isEmpty());
    }

    @Test
    public void 回退口径末尾必须标按区间估算() {
        assertTrue(CheckInHabit.sourceNote(false).contains("按区间估算"));
        assertFalse(CheckInHabit.sourceNote(true).contains("按区间估算"));
        assertTrue(CheckInHabit.sourceNote(true).contains("打卡"));
    }

    @Test
    public void 年份下界取最早打卡上界为当前年加一() {
        List<CheckIn> all = new ArrayList<>(Arrays.asList(
                ci(1, 20231105), ci(1, 20261103), ci(2, 20250101)));
        // 新口径：无历史记录/空库也要能往前翻 5 年，否则左滑切年根本滑不动
        assertEquals(2021, CheckInYearGrid.minYear(all, 2026));
        assertEquals(2027, CheckInYearGrid.maxYear(2026));
        assertEquals(2021, CheckInYearGrid.minYear(null, 2026));
        assertEquals(2021, CheckInYearGrid.minYear(Collections.emptyList(), 2026));
        assertEquals("未来异常数据不能把下界拉高", 2021,
                CheckInYearGrid.minYear(Arrays.asList(ci(1, 20231105), ci(1, 20991231)), 2026));
        assertEquals("比当前年早 10 年的打卡记录仍决定下界", 2016,
                CheckInYearGrid.minYear(Arrays.asList(ci(1, 20161105)), 2026));
    }

    @Test
    public void 左右滑年份与越界回弹() {
        assertEquals(2025, CheckInYearGrid.shiftYear(2026, -1, 2023, 2027));
        assertEquals(2027, CheckInYearGrid.shiftYear(2026, 1, 2023, 2027));
        assertFalse(CheckInYearGrid.boundaryHit(2026, -1, 2023, 2027));
        assertEquals(2023, CheckInYearGrid.shiftYear(2023, -1, 2023, 2027));
        assertTrue("再往上一年要回弹", CheckInYearGrid.boundaryHit(2023, -1, 2023, 2027));
        assertEquals(2027, CheckInYearGrid.shiftYear(2027, 1, 2023, 2027));
        assertTrue("再往下一/年要回弹", CheckInYearGrid.boundaryHit(2027, 1, 2023, 2027));
        assertEquals(24, CheckInYearGrid.RUBBER_BAND_DP);
    }

    @Test
    public void 左滑上一年右滑下一年() {
        assertEquals(-1, CheckInYearGrid.deltaForSwipe(-120f));
        assertEquals(1, CheckInYearGrid.deltaForSwipe(120f));
        assertEquals(2025, CheckInYearGrid.shiftYear(2026,
                CheckInYearGrid.deltaForSwipe(-120f), 2023, 2027));
        assertEquals(2027, CheckInYearGrid.shiftYear(2026,
                CheckInYearGrid.deltaForSwipe(120f), 2023, 2027));
    }

    @Test
    public void 当前年当前月高亮判定() {
        assertTrue(CheckInYearGrid.isCurrentMonth(2026, 11, 20261105));
        assertFalse(CheckInYearGrid.isCurrentMonth(2026, 10, 20261105));
        assertFalse(CheckInYearGrid.isCurrentMonth(2025, 11, 20261105));
    }
}
