package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 借出区间扣阅读天数（v5.8.33）：书在别人手上的日子不该算进阅读时长，
 * 但「部分册在外」的书仍然能读，不能整本扣掉。
 */
public class LendSpansTest {

    private static BorrowRecord loan(long id, long bookId, int copy, String lend,
                                     String back, int status) {
        BorrowRecord r = new BorrowRecord();
        r.id = id;
        r.bookId = bookId;
        r.copyIndex = copy;
        r.borrower = "同学甲";
        r.lendDate = lend;
        r.returnDate = back;
        r.status = status;
        return r;
    }

    // ==================== minusDays ====================

    @Test
    public void minusDaysNoOverlapKeepsAll() {
        assertEquals(31, LendSpans.minusDays(20260101, 20260131,
                Arrays.asList(new int[]{20260301, 20260310})));
    }

    @Test
    public void minusDaysSubtractsIntersectionInclusive() {
        // 1 月整月 31 天，借出 10 日~19 日共 10 天
        assertEquals(21, LendSpans.minusDays(20260101, 20260131,
                Arrays.asList(new int[]{20260110, 20260119})));
    }

    @Test
    public void minusDaysClampsAtZeroAndIgnoresBadSpans() {
        assertEquals(0, LendSpans.minusDays(20260101, 20260103,
                Arrays.asList(new int[]{20251201, 20261231})));
        assertEquals(3, LendSpans.minusDays(20260101, 20260103, new ArrayList<>()));
        assertEquals(3, LendSpans.minusDays(20260101, 20260103, null));
        assertEquals(0, LendSpans.minusDays(20260103, 20260101, null));
    }

    @Test
    public void minusDaysSumsSeveralSpans() {
        assertEquals(16, LendSpans.minusDays(20260101, 20260131, Arrays.asList(
                new int[]{20260105, 20260109}, new int[]{20260120, 20260129})));
    }

    // ==================== of / byBook / 坏日期 ====================

    @Test
    public void openLoanEndsTodayAndReturnedUsesReturnDate() {
        List<BorrowRecord> all = Arrays.asList(
                loan(1, 7, 1, "2026-01-01", "", BorrowRecord.STATUS_OUT),
                loan(2, 7, 1, "2026-02-01", "2026-02-10", BorrowRecord.STATUS_RETURNED));
        List<int[]> spans = LendSpans.of(all, 7);
        assertEquals(2, spans.size());
        assertEquals(DateKeys.todayKey(), spans.get(0)[1]);   // 未还的结束日 = 今天
        assertEquals(20260201, spans.get(1)[0]);
        assertEquals(20260210, spans.get(1)[1]);
        assertEquals(0, LendSpans.countUnparsable(all));
    }

    @Test
    public void badDatesAreSkippedAndCounted() {
        List<BorrowRecord> all = Arrays.asList(
                loan(1, 7, 1, "", "", BorrowRecord.STATUS_RETURNED),          // 没借出日
                loan(2, 7, 1, "2026-01-01", "not-a-date", BorrowRecord.STATUS_RETURNED), // 归还日读不出
                loan(3, 7, 1, "2026-03-05", "2026-03-01", BorrowRecord.STATUS_RETURNED), // 倒挂
                loan(4, 7, 1, "2026-01-01", "", BorrowRecord.STATUS_OUT));    // 正常（未还）
        assertEquals(1, LendSpans.of(all, 7).size());
        assertEquals(3, LendSpans.countUnparsable(all));
    }

    @Test
    public void byBookGroupsPerBook() {
        Map<Long, List<int[]>> m = LendSpans.byBook(Arrays.asList(
                loan(1, 7, 1, "2026-01-01", "2026-01-10", BorrowRecord.STATUS_RETURNED),
                loan(2, 8, 1, "2026-01-01", "2026-01-20", BorrowRecord.STATUS_RETURNED)));
        assertEquals(2, m.size());
        assertEquals(21, LendSpans.minusDays(20260101, 20260131, m.get(7L)));
        assertEquals(11, LendSpans.minusDays(20260101, 20260131, m.get(8L)));
    }

    // ==================== ReadingRecords.days 重载 ====================

    @Test
    public void sessionDaysDeductLendDate() {
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = Book.READ_DONE;
        r.startDate = "2026-01-01";
        r.endDate = "2026-01-31";
        assertEquals(31, ReadingRecords.days(r));                 // 老口径不动
        assertEquals(21, ReadingRecords.days(r,
                Arrays.asList(new int[]{20260105, 20260114})));
    }

    @Test
    public void ongoingSessionDaysDeductToToday() {
        int today = DateKeys.todayKey();
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = Book.READING;
        r.startDate = keyText(shift(today, -9));   // 今天往前 9 天开始 → 含今天共 10 天
        r.endDate = "";
        assertEquals(10, ReadingRecords.days(r));
        // 最近 4 天借在外面，在读时长只剩 6 天
        assertEquals(6, ReadingRecords.days(r,
                Arrays.asList(new int[]{shift(today, -3), today})));
    }

    private static String keyText(int key) {
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d",
                key / 10000, key / 100 % 100, key % 100);
    }

    private static int shift(int key, int delta) {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.set(key / 10000, key / 100 % 100 - 1, key % 100, 0, 0, 0);
        c.add(java.util.Calendar.DAY_OF_MONTH, delta);
        return c.get(java.util.Calendar.YEAR) * 10000
                + (c.get(java.util.Calendar.MONTH) + 1) * 100
                + c.get(java.util.Calendar.DAY_OF_MONTH);
    }

    // ==================== Holdings：全部册在外 / 部分册在外 ====================

    @Test
    public void fullyOutAndPartiallyOutCountedDifferently() {
        Book reading = new Book();
        reading.id = 7;
        reading.title = "全部在外";
        reading.readStatus = Book.READING;
        reading.stockStatus = Book.STOCK_LENT;
        reading.quantity = 1;

        Book partial = new Book();
        partial.id = 8;
        partial.title = "只借走一册";
        partial.readStatus = Book.READING;
        partial.stockStatus = Book.STOCK_LENT;
        partial.quantity = 2;

        BookCopy second = new BookCopy();
        second.id = 20;
        second.bookId = 8;
        second.copyIndex = 2;
        second.stockStatus = Book.STOCK_IN;

        Map<Long, java.util.Set<Integer>> open = new java.util.HashMap<>();
        open.put(7L, new java.util.HashSet<>(Arrays.asList(1)));
        open.put(8L, new java.util.HashSet<>(Arrays.asList(1)));   // 第 2 册还在家

        StatsHoldings.Holdings h = StatsHoldings.holdings(
                Arrays.asList(reading, partial),
                java.util.Collections.singletonMap(8L, Arrays.asList(second)), open);
        assertEquals(2, h.reading);
        assertEquals(2, h.readingOut);        // 两本都有册在外面
        assertEquals(1, h.readingFullyOut);   // 只有第一本是「全部册在外」
        assertEquals(1, h.readingFullyOutNames.size());
        assertEquals("全部在外", h.readingFullyOutNames.get(0));
    }

    @Test
    public void holdingsWithoutOpenMapKeepsReadingOutZero() {
        Book reading = new Book();
        reading.id = 7;
        reading.readStatus = Book.READING;
        reading.stockStatus = Book.STOCK_LENT;
        StatsHoldings.Holdings h = StatsHoldings.holdings(Arrays.asList(reading), null);
        assertEquals(0, h.readingOut);
        assertEquals(0, h.readingFullyOut);
    }

    // ==================== Pace：中位天数扣借出 + 分母剔掉全部在外 ====================

    @Test
    public void paceMedianDaysDeductsLendDate() {
        Book done = new Book();
        done.id = 7;
        done.readStatus = Book.READ_DONE;
        done.readingStartDate = "2026-01-01";
        done.readingFinishDate = "2026-01-31";
        done.pages = 310;

        StatsHoldings.Holdings h = StatsHoldings.holdings(Arrays.asList(done), null);
        StatsPace.Pace plain = StatsPace.pace(Arrays.asList(done), null, h, 31);
        assertEquals(31.0, plain.medianDays, 0.001);
        assertEquals(10.0, plain.medianPagesPerDay, 0.001);

        Map<Long, List<int[]>> spans = new java.util.HashMap<>();
        spans.put(7L, Arrays.asList(new int[]{20260101, 20260110}));  // 前 10 天在别人手上
        StatsPace.Pace cut = StatsPace.pace(Arrays.asList(done), null, h, 21, spans);
        assertEquals(21.0, cut.medianDays, 0.001);
        assertEquals(310.0 / 21.0, cut.medianPagesPerDay, 0.001);
    }

    @Test
    public void activeFinishRateSkipsBooksFullyLentOut() {
        Book done = new Book();
        done.id = 1;
        done.readStatus = Book.READ_DONE;
        Book readingHome = new Book();
        readingHome.id = 2;
        readingHome.readStatus = Book.READING;
        Book readingOut = new Book();
        readingOut.id = 3;
        readingOut.readStatus = Book.READING;
        readingOut.stockStatus = Book.STOCK_LENT;
        List<Book> books = Arrays.asList(done, readingHome, readingOut);

        Map<Long, java.util.Set<Integer>> open = new java.util.HashMap<>();
        open.put(3L, new java.util.HashSet<>(Arrays.asList(1)));
        StatsHoldings.Holdings h = StatsHoldings.holdings(books, null, open);

        // 分母 = 已读 1 + (在读 2 - 全部在外 1) = 2
        StatsPace.Pace p = StatsPace.pace(books, null, h, 0, null);
        assertEquals(0.5, p.activeFinishRate, 0.001);

        StatsPace.Pace noLend = StatsPace.pace(books, null,
                StatsHoldings.holdings(books, null), 0, null);
        assertEquals(1.0 / 3.0, noLend.activeFinishRate, 0.001);
    }

    @Test
    public void fullyLentOutBookContributesNoReadingDays() {
        Book done = new Book();
        done.id = 7;
        done.readStatus = Book.READ_DONE;
        done.readingStartDate = "2026-01-01";
        done.readingFinishDate = "2026-01-10";
        Map<Long, List<int[]>> spans = new java.util.HashMap<>();
        spans.put(7L, Arrays.asList(new int[]{20251231, 20260201})); // 整段都在外面
        StatsPace.Pace p = StatsPace.pace(Arrays.asList(done), null,
                StatsHoldings.holdings(Arrays.asList(done), null), 0, spans);
        assertEquals(0, p.daySample);   // 扣完为 0 天的样本不进统计
    }
}
