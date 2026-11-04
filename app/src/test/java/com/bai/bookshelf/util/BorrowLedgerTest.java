package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.NoteImage;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 借阅台账（v5.8.32）：状态回写规则与借阅孤儿体检。
 * 两边都只测纯逻辑（不碰 Room），DB 侧的行为由真机验收覆盖。
 */
public class BorrowLedgerTest {

    private static Book book(long id, int stock) {
        Book b = new Book();
        b.id = id;
        b.stockStatus = stock;
        return b;
    }

    private static BookCopy copy(long id, long bookId, int index, int stock) {
        BookCopy c = new BookCopy();
        c.id = id;
        c.bookId = bookId;
        c.copyIndex = index;
        c.stockStatus = stock;
        return c;
    }

    private static BorrowRecord record(long id, long bookId, int copyIndex, int status) {
        BorrowRecord r = new BorrowRecord();
        r.id = id;
        r.bookId = bookId;
        r.copyIndex = copyIndex;
        r.status = status;
        return r;
    }

    private static NoteImage image(long id, long recordId, String path) {
        NoteImage n = new NoteImage();
        n.id = id;
        n.ownerKey = "borrow:" + recordId;
        n.path = path;
        return n;
    }

    // ==================== 状态回写 ====================

    @Test
    public void stockFollowsOpenRecord() {
        assertEquals(Book.STOCK_LENT, BorrowLedger.wantStock(Book.STOCK_IN, true));
        assertEquals(Book.STOCK_IN, BorrowLedger.wantStock(Book.STOCK_LENT, false));
    }

    @Test
    public void lostStatusIsNeverOverwritten() {
        assertEquals(Book.STOCK_LOST, BorrowLedger.wantStock(Book.STOCK_LOST, true));
        assertEquals(Book.STOCK_LOST, BorrowLedger.wantStock(Book.STOCK_LOST, false));
    }

    @Test
    public void borrowedInDeleteRenumbersAndResyncsFlatFields() {
        Book book = book(1, Book.STOCK_IN);
        book.ownType = Book.OWN_BORROW;
        List<BorrowSourceRecords.Record> records = new ArrayList<>(Arrays.asList(
                rec(1, "2026-01-01", "2026-01-31", ""),
                rec(2, "2026-03-01", "2026-03-20", "")));
        BorrowSourceRecords.syncTo(book, records);
        assertEquals("2026-03-01", book.borrowStartDate);

        assertTrue(BorrowLedger.deleteBorrowedIn(book, records, 1));
        assertEquals(1, records.size());
        assertEquals(1, records.get(0).seq);
        // 删掉第 1 场后，汇总字段跟着剩下的最后一场走
        assertEquals("2026-03-01", book.borrowStartDate);
        assertEquals("2026-03-20", book.borrowDueDate);
    }

    @Test
    public void borrowedInDeleteOfMissingSeqChangesNothing() {
        Book book = book(1, Book.STOCK_IN);
        List<BorrowSourceRecords.Record> records = new ArrayList<>();
        records.add(rec(1, "2026-01-01", "2026-01-31", ""));
        BorrowSourceRecords.syncTo(book, records);
        assertFalse(BorrowLedger.deleteBorrowedIn(book, records, 7));
        assertEquals(1, records.size());
    }

    @Test
    public void borrowedInDeleteOfLastRecordClearsSummary() {
        Book book = book(1, Book.STOCK_IN);
        List<BorrowSourceRecords.Record> records = new ArrayList<>();
        records.add(rec(1, "2026-01-01", "2026-01-31", ""));
        BorrowSourceRecords.syncTo(book, records);
        assertTrue(BorrowLedger.deleteBorrowedIn(book, records, 1));
        assertEquals("", book.borrowStartDate);
        assertEquals("", book.borrowDueDate);
        assertEquals("", book.borrowRecords);
    }

    private static BorrowSourceRecords.Record rec(int seq, String start, String due,
                                                  String returned) {
        BorrowSourceRecords.Record r = new BorrowSourceRecords.Record(seq);
        r.startDate = start;
        r.dueDate = due;
        r.returnDate = returned;
        return r;
    }

    // ==================== 借阅孤儿 ====================

    @Test
    public void cleanDataHasNoOrphans() {
        Book self = book(1, Book.STOCK_IN);
        List<Book> books = Arrays.asList(self);
        List<BookCopy> copies = Arrays.asList(copy(10, 1, 2, Book.STOCK_IN));
        List<BorrowRecord> records = Arrays.asList(
                record(100, 1, 1, BorrowRecord.STATUS_RETURNED));
        StatsHealth.BorrowOrphans o =
                StatsHealth.borrowOrphans(books, copies, records, new ArrayList<>());
        assertTrue(o.line(), o.isEmpty());
    }

    @Test
    public void detectsLentWithoutOpenRecord() {
        Book lent = book(1, Book.STOCK_LENT);
        List<BorrowRecord> records = Arrays.asList(
                record(100, 1, 1, BorrowRecord.STATUS_RETURNED));
        StatsHealth.BorrowOrphans o = StatsHealth.borrowOrphans(
                Arrays.asList(lent), new ArrayList<>(), records, new ArrayList<>());
        assertEquals(1, o.lendBooks.size());
        assertEquals(0, o.orphanRecords.size());
    }

    @Test
    public void detectsLentCopyWithoutOpenRecord() {
        Book head = book(1, Book.STOCK_IN);
        BookCopy staleLent = copy(11, 1, 2, Book.STOCK_LENT);
        BookCopy lost = copy(12, 1, 3, Book.STOCK_LOST);
        StatsHealth.BorrowOrphans o = StatsHealth.borrowOrphans(
                Arrays.asList(head), Arrays.asList(staleLent, lost),
                new ArrayList<>(), new ArrayList<>());
        // 只有「标着借出却没记录」算孤儿；遗失是人工状态，不该被当成脏数据
        assertEquals(1, o.lendCopies.size());
        assertEquals(11L, o.lendCopies.get(0).id);
    }

    @Test
    public void detectsRecordPointingAtMissingCopy() {
        Book head = book(1, Book.STOCK_LENT);
        BorrowRecord second = record(101, 1, 2, BorrowRecord.STATUS_OUT);
        StatsHealth.BorrowOrphans o = StatsHealth.borrowOrphans(
                Arrays.asList(head), new ArrayList<>(),
                Arrays.asList(second), new ArrayList<>());
        assertEquals(1, o.orphanRecords.size());
    }

    @Test
    public void detectsImageOfDeletedRecord() {
        List<NoteImage> images = Arrays.asList(image(5, 999, "/x/1.jpg"));
        StatsHealth.BorrowOrphans o = StatsHealth.borrowOrphans(
                new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), images);
        assertEquals(1, o.orphanImages.size());
    }

    @Test
    public void detectsFlatSummaryOutOfSyncWithJson() {
        Book book = book(1, Book.STOCK_IN);
        book.ownType = Book.OWN_BORROW;
        List<BorrowSourceRecords.Record> records = new ArrayList<>();
        records.add(rec(1, "2026-05-01", "2026-05-30", ""));
        book.borrowRecords = BorrowSourceRecords.toJson(records);
        book.borrowStartDate = "2026-01-01";   // 故意留旧值
        book.borrowDueDate = "2026-01-31";
        StatsHealth.BorrowOrphans o = StatsHealth.borrowOrphans(
                Arrays.asList(book), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        assertEquals(1, o.outOfSyncBooks.size());
    }

    @Test
    public void legacyBookWithoutJsonIsNotFlagged() {
        // 老数据只有 flat 字段、没有 JSON：BorrowSourceRecords.load 会合成第 1 场，不算不一致
        Book book = book(1, Book.STOCK_IN);
        book.ownType = Book.OWN_BORROW;
        book.borrowStartDate = "2026-01-01";
        book.borrowDueDate = "2026-01-31";
        book.borrowRecords = "";
        StatsHealth.BorrowOrphans o = StatsHealth.borrowOrphans(
                Arrays.asList(book), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        assertEquals(0, o.outOfSyncBooks.size());
    }

    @Test
    public void orphanLineNamesAllFourCounts() {
        Book lent = book(1, Book.STOCK_LENT);
        Book stale = book(2, Book.STOCK_IN);
        stale.ownType = Book.OWN_BORROW;
        List<BorrowSourceRecords.Record> records = new ArrayList<>();
        records.add(rec(1, "2026-05-01", "2026-05-30", ""));
        stale.borrowRecords = BorrowSourceRecords.toJson(records);
        stale.borrowStartDate = "2026-01-01";
        stale.borrowDueDate = "2026-01-30";
        StatsHealth.BorrowOrphans o = StatsHealth.borrowOrphans(
                Arrays.asList(lent, stale),
                new ArrayList<>(),
                Arrays.asList(record(101, 1, 2, BorrowRecord.STATUS_OUT)),
                Arrays.asList(image(5, 999, "/x/1.jpg")));
        String line = o.line();
        assertTrue(line, line.contains("借阅孤儿 4 项"));
        assertTrue(line, line.contains("借出无记录 1"));
        assertTrue(line, line.contains("记录无副本 1"));
        assertTrue(line, line.contains("图片孤儿 1"));
        assertTrue(line, line.contains("借入汇总不符 1"));

        String plan = BorrowLedger.describePlan(o);
        assertTrue(plan, plan.contains("改回「在库」"));
        assertTrue(plan, plan.contains("补建这些副本"));
        assertTrue(plan, plan.contains("不可恢复"));
        assertTrue(plan, plan.contains("重算"));
    }
}
