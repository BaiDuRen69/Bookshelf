package com.bai.bookshelf.ui.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class StatsRankViewTest {

    @Test
    public void dayRankSkipsEmptyDaysAndSortsDescending() {
        int[] daily = {0, 2, 0, 3, 1};
        List<StatsRankView.Entry> rank = StatsRankView.dayRank(4, daily); // 5 月
        assertEquals(3, rank.size());
        assertEquals("5月4日", rank.get(0).label);
        assertEquals(3, rank.get(0).count);
        assertEquals("5月2日", rank.get(1).label);
        assertEquals("5月5日", rank.get(2).label);
    }

    @Test
    public void monthRankSkipsEmptyMonthsAndKeepsTieOrder() {
        int[] monthly = new int[12];
        monthly[0] = 2;
        monthly[5] = 5;
        monthly[11] = 5;
        List<StatsRankView.Entry> rank = StatsRankView.monthRank(monthly);
        assertEquals(3, rank.size());
        // 并列时保持原月份顺序（稳定排序）
        assertEquals("6月", rank.get(0).label);
        assertEquals("12月", rank.get(1).label);
        assertEquals("1月", rank.get(2).label);
    }

    @Test
    public void emptyInputProducesEmptyRank() {
        assertTrue(StatsRankView.dayRank(0, new int[31]).isEmpty());
        assertTrue(StatsRankView.monthRank(new int[12]).isEmpty());
    }
}
