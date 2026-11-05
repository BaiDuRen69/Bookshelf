package com.bai.bookshelf.ui.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.util.StatsHoldings;
import com.bai.bookshelf.util.StatsLend;
import com.bai.bookshelf.util.StatsPace;
import com.bai.bookshelf.util.StatsSpend;

import org.junit.Test;

/**
 * 栏首摘要行的取数与占位（v5.9.1）：每栏固定三个单元，
 * 算不出来给「—」而不是 0，这是它与洞察行的关键区别。
 */
public class StatsSummaryTest {

    private static StatsHoldings.Holdings holdings() {
        StatsHoldings.Holdings h = new StatsHoldings.Holdings();
        h.titles = 121;
        h.copies = 128;
        h.readDone = 7;
        h.reading = 35;
        h.copiesLent = 6;
        return h;
    }

    @Test
    public void everyColumnHasThreeUnits() {
        StatsSpend.Spend s = new StatsSpend.Spend();
        s.paid = 3523;
        assertEquals(3, StatsSummary.books(holdings(), s).length);
        assertEquals(3, StatsSummary.reading(holdings(), new StatsPace.Habit()).length);
        assertEquals(3, StatsSummary.purchase(s).length);
        assertEquals(3, StatsSummary.loan(holdings(), new StatsLend.Lend()).length);
    }

    @Test
    public void booksColumnDoesNotRepeatTitles() {
        String[][] u = StatsSummary.books(holdings(), new StatsSpend.Spend());
        // 「种」留给 hero 大数字，摘要行第一格是册
        assertEquals("册", u[0][0]);
        assertEquals("128", u[0][1]);
        assertEquals("", u[0][2]);
        assertEquals("已读率", u[2][0]);
        assertEquals("6", u[2][1]);
        assertEquals("%", u[2][2]);
    }

    @Test
    public void unknownNumbersShowDashNotZero() {
        StatsSpend.Spend s = new StatsSpend.Spend();   // paid=0、perPage/discount 都是 -1
        String[][] u = StatsSummary.purchase(s);
        assertEquals("—", u[0][1]);
        assertEquals("—", u[1][1]);
        assertEquals("—", u[2][1]);
        assertEquals("占位时不带单位", "", u[0][2]);

        StatsLend.Lend l = new StatsLend.Lend();
        l.onTimeRate = -1;
        assertEquals("—", StatsSummary.loan(holdings(), l)[2][1]);

        StatsPace.Habit habit = new StatsPace.Habit();
        habit.currentStreak = 0;
        assertEquals("—", StatsSummary.reading(holdings(), habit)[2][1]);
    }

    @Test
    public void loanColumnUsesCopiesForOutAndRecordsForOverdue() {
        StatsLend.Lend l = new StatsLend.Lend();
        l.overdue = 3;
        String[][] u = StatsSummary.loan(holdings(), l);
        assertEquals("在外", u[0][0]);
        assertEquals("6", u[0][1]);      // hold.copiesLent：按册
        assertEquals("册", u[0][2]);
        assertEquals("逾期", u[1][0]);
        assertEquals("3", u[1][1]);      // lend.overdue：按笔，与洞察同口径
        assertEquals("笔", u[1][2]);
    }

    @Test
    public void nullAggregatesNeverCrashAndNeverShowZero() {
        String[][] u = StatsSummary.books(null, null);
        for (String[] cell : u) {
            assertEquals("—", cell[1]);
        }
        assertEquals("—", StatsSummary.purchase(null)[0][1]);
        assertEquals("—", StatsSummary.loan(null, null)[0][1]);
        assertEquals("—", StatsSummary.reading(null, null)[0][1]);
    }
}
