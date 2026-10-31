package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class BorrowSourceRecordsTest {

    private static BorrowSourceRecords.Record record(int seq, String source, String start,
                                                     String due, String returned) {
        BorrowSourceRecords.Record r = new BorrowSourceRecords.Record(seq);
        r.source = source;
        r.startDate = start;
        r.dueDate = due;
        r.returnDate = returned;
        return r;
    }

    @Test
    public void legacySummaryBecomesFirstRecordWithQuantity() {
        Book book = new Book();
        book.ownType = Book.OWN_BORROW;
        book.borrowStartDate = "2026-10-01";
        book.borrowDueDate = "2026-10-30";
        book.quantity = 2;
        List<BorrowSourceRecords.Record> records = BorrowSourceRecords.load(book);
        assertEquals(1, records.size());
        assertEquals("2026-10-01", records.get(0).startDate);
        assertEquals("2026-10-30", records.get(0).dueDate);
        assertEquals(2, records.get(0).quantity);
    }

    @Test
    public void oldJsonWithoutQuantityDefaultsToOne() {
        List<BorrowSourceRecords.Record> records = BorrowSourceRecords.parse(
                "[{\"seq\":1,\"source\":\"图书馆\"}]");
        assertEquals(1, records.get(0).quantity);
    }

    @Test
    public void jsonRoundTripKeepsSourceAndDates() {
        List<BorrowSourceRecords.Record> records = new ArrayList<>();
        records.add(record(1, "图书馆", "2026-10-01", "2026-10-30", "2026-10-20 18:00"));
        records.add(record(2, "朋友", "2026-11-01", "2026-11-20", ""));
        records.get(1).quantity = 2;
        List<BorrowSourceRecords.Record> back = BorrowSourceRecords.parse(
                BorrowSourceRecords.toJson(records));
        assertEquals(2, back.size());
        assertEquals("图书馆", back.get(0).source);
        assertEquals("2026-10-20 18:00", back.get(0).returnDate);
        assertEquals(2, back.get(1).quantity);
    }

    @Test
    public void syncToUsesLatestRecordAndMarksBookmark() {
        Book book = new Book();
        book.ownType = Book.OWN_BORROW;
        List<BorrowSourceRecords.Record> records = new ArrayList<>();
        records.add(record(1, "图书馆", "2026-10-01", "2026-10-30", "2026-10-20 18:00"));
        records.add(record(2, "朋友", "2026-11-01", "2026-11-20", "2026-11-18 09:00"));
        records.get(1).quantity = 3;
        BorrowSourceRecords.syncTo(book, records);
        assertEquals("2026-11-01", book.borrowStartDate);
        assertEquals("2026-11-20", book.borrowDueDate);
        assertEquals("2026-11-18 09:00", book.borrowReturnDate);
        assertEquals(3, book.quantity);
        assertEquals(Book.STOCK_BOOKMARK, book.stockStatus);
    }

    @Test
    public void newUnreturnedBorrowRestoresInStock() {
        Book book = new Book();
        book.ownType = Book.OWN_BORROW;
        book.stockStatus = Book.STOCK_BOOKMARK;
        List<BorrowSourceRecords.Record> records = new ArrayList<>();
        records.add(record(1, "图书馆", "2026-10-01", "2026-10-30", "2026-10-20 18:00"));
        records.add(record(2, "朋友", "2026-11-01", "2026-11-20", ""));
        BorrowSourceRecords.syncTo(book, records);
        assertEquals("", book.borrowReturnDate);
        assertEquals(Book.STOCK_IN, book.stockStatus);
    }

    @Test
    public void lentStatusIsNotOverridden() {
        Book book = new Book();
        book.ownType = Book.OWN_BORROW;
        book.stockStatus = Book.STOCK_LENT;
        List<BorrowSourceRecords.Record> records = new ArrayList<>();
        records.add(record(1, "", "2026-10-01", "2026-10-30", "2026-10-20 18:00"));
        BorrowSourceRecords.syncTo(book, records);
        assertEquals(Book.STOCK_LENT, book.stockStatus);
    }

    @Test
    public void returnLatestMarksLastOpenRecord() {
        Book book = new Book();
        book.ownType = Book.OWN_BORROW;
        List<BorrowSourceRecords.Record> records = new ArrayList<>();
        records.add(record(1, "图书馆", "2026-10-01", "2026-10-30", "2026-10-20 18:00"));
        records.add(record(2, "朋友", "2026-11-01", "2026-11-20", ""));
        BorrowSourceRecords.syncTo(book, records);

        assertEquals("2026-11-23 10:00",
                BorrowSourceRecords.returnLatest(book, "2026-11-23 10:00"));
        assertEquals(Book.STOCK_BOOKMARK, book.stockStatus);
        assertFalse(BorrowSourceRecords.hasOpen(book));
        assertNull(BorrowSourceRecords.returnLatest(book, "2026-11-24 10:00"));
    }

    @Test
    public void refreshSummaryBackfillsSourceChannel() {
        Book book = new Book();
        book.ownType = Book.OWN_BORROW;
        List<BorrowSourceRecords.Record> records = new ArrayList<>();
        records.add(record(1, "图书馆", "2026-10-01", "2026-10-30", ""));
        book.borrowRecords = BorrowSourceRecords.toJson(records);
        BorrowSourceRecords.refreshSummary(book);
        assertEquals("图书馆", book.sourceChannel);
    }

    @Test
    public void emptyRecordsClearSummary() {
        Book book = new Book();
        book.ownType = Book.OWN_BORROW;
        book.borrowStartDate = "2026-10-01";
        book.borrowDueDate = "2026-10-30";
        book.borrowReturnDate = "2026-10-20 18:00";
        book.borrowRecords = "[{\"seq\":1}]";
        BorrowSourceRecords.syncTo(book, new ArrayList<>());
        assertEquals("", book.borrowStartDate);
        assertEquals("", book.borrowDueDate);
        assertEquals("", book.borrowReturnDate);
        assertEquals("", book.borrowRecords);
    }
}
