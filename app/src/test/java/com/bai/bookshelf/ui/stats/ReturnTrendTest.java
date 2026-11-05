package com.bai.bookshelf.ui.stats;

import static org.junit.Assert.assertEquals;

import com.bai.bookshelf.data.entity.BorrowRecord;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 归还趋势分桶（v5.9.1 三步）：窗口、跨年、非法日期与文案。
 */
public class ReturnTrendTest {

    private static BorrowRecord returned(String returnDate) {
        BorrowRecord r = new BorrowRecord();
        r.status = BorrowRecord.STATUS_RETURNED;
        r.returnDate = returnDate;
        return r;
    }

    @Test
    public void bucketsLastSixMonthsEndingWithCurrent() {
        ReturnTrend.Series s = ReturnTrend.of(
                Arrays.asList(returned("2026-11-02"), returned("2026-11-20"),
                        returned("2026-06-01")), 20261105, 6);
        assertEquals(Arrays.asList("6月", "7月", "8月", "9月", "10月", "11月"), s.labels);
        assertEquals(Arrays.asList(1, 0, 0, 0, 0, 2), s.values);
        assertEquals(3, s.inWindow);
    }

    @Test
    public void olderThanWindowIsIgnored() {
        ReturnTrend.Series s = ReturnTrend.of(
                Arrays.asList(returned("2025-11-30"), returned("2026-10-01")), 20261105, 6);
        assertEquals(Arrays.asList(0, 0, 0, 0, 1, 0), s.values);
        assertEquals(1, s.inWindow);
    }

    @Test
    public void windowCrossesYearBoundary() {
        ReturnTrend.Series s = ReturnTrend.of(
                Arrays.asList(returned("2025-12-31"), returned("2026-01-02")), 20260210, 6);
        assertEquals(Arrays.asList("9月", "10月", "11月", "12月", "1月", "2月"), s.labels);
        assertEquals(Arrays.asList(0, 0, 0, 1, 1, 0), s.values);
    }

    @Test
    public void unparsableAndStillOutAreSkipped() {
        BorrowRecord out = new BorrowRecord();
        out.status = BorrowRecord.STATUS_OUT;
        out.returnDate = "2026-11-01";
        ReturnTrend.Series s = ReturnTrend.of(
                Arrays.asList(returned("待定"), out, returned(""), null), 20261105, 6);
        assertEquals(0, s.inWindow);
        assertEquals(Arrays.asList(0, 0, 0, 0, 0, 0), s.values);
    }

    @Test
    public void emptyRecordsStillGiveFullLabelRow() {
        ReturnTrend.Series s = ReturnTrend.of(new ArrayList<BorrowRecord>(), 20261105, 6);
        assertEquals(6, s.labels.size());
        assertEquals(6, s.values.size());
        assertEquals(0, s.inWindow);
    }

    @Test
    public void notEnoughTextSeparatesNoDataFromThinSample() {
        assertEquals("数据不足：还没有归还记录，收回借出的书后这里开始累计",
                ReturnTrend.notEnoughText(0, 10, 6));
        assertEquals("数据不足：已归还 4 笔，少于 10 笔不画近 6 个月趋势",
                ReturnTrend.notEnoughText(4, 10, 6));
    }
}
