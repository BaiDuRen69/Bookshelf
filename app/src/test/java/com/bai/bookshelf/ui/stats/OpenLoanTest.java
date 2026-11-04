package com.bai.bookshelf.ui.stats;

import static org.junit.Assert.assertEquals;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/**
 * 在借清单取数（v5.9.0 从 StatsFragment 抽出）：逾期排最前，其余按应还日期；
 * 已归还的记录不进清单。
 */
public class OpenLoanTest {

    private static Book book(long id, String title) {
        Book b = new Book();
        b.id = id;
        b.title = title;
        return b;
    }

    private static BorrowRecord loan(long id, long bookId, String who, String due, int status) {
        BorrowRecord r = new BorrowRecord();
        r.id = id;
        r.bookId = bookId;
        r.borrower = who;
        r.lendDate = "2026-10-01";
        r.dueDate = due;
        r.status = status;
        r.copyIndex = 1;
        return r;
    }

    @Test
    public void overdueFirstThenByDueDate() {
        List<OpenLoan> rows = OpenLoan.build(
                Arrays.asList(book(7, "甲书"), book(8, "乙书"), book(9, "丙书")),
                Arrays.asList(
                        loan(1, 9, "小王", "2026-11-20", BorrowRecord.STATUS_OUT),
                        loan(2, 7, "小李", "2026-10-20", BorrowRecord.STATUS_OUT),
                        loan(3, 8, "小张", "2026-11-10", BorrowRecord.STATUS_OUT)),
                20261104);
        assertEquals(3, rows.size());
        // 10-20 与 11-01 之前都算逾期：这里只有 10-20 逾期，排第一，其后按应还日期升序
        assertEquals("甲书", rows.get(0).title);
        assertEquals("乙书", rows.get(1).title);
        assertEquals("丙书", rows.get(2).title);
    }

    @Test
    public void returnedRecordsAreSkipped() {
        List<OpenLoan> rows = OpenLoan.build(
                Arrays.asList(book(7, "甲书")),
                Arrays.asList(
                        loan(1, 7, "小李", "2026-10-20", BorrowRecord.STATUS_RETURNED),
                        loan(2, 7, "小王", "2026-11-20", BorrowRecord.STATUS_OUT)),
                20261104);
        assertEquals(1, rows.size());
        assertEquals("小王", rows.get(0).borrower);
    }

    @Test
    public void missingBookAndBorrowerFallBackToPlaceholders() {
        List<OpenLoan> rows = OpenLoan.build(
                Arrays.asList(book(7, "甲书")),
                Arrays.asList(loan(1, 404, "", "2026-11-20", BorrowRecord.STATUS_OUT)),
                20261104);
        assertEquals(1, rows.size());
        assertEquals("（未命名）", rows.get(0).title);
        assertEquals("未填借阅人", rows.get(0).borrower);
    }

    @Test
    public void monthDayDropsYearAndLeadingZeros() {
        assertEquals("11月4日", OpenLoan.monthDay(20261104));
        assertEquals("1月20日", OpenLoan.monthDay(20260120));
    }
}
