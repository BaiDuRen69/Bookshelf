package com.bai.bookshelf.ui.detail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DetailCopyInfoTest {

    @Test
    public void copyByIndexFallsBackToMainAndDefaults() {
        Book book = book();
        List<BookCopy> copies = Arrays.asList(copy(2, Book.STOCK_LENT), copy(3, Book.STOCK_LOST));

        BookCopy first = DetailCopyInfo.copyByIndex(book, copies, 1);
        assertEquals(Book.STOCK_IN, first.stockStatus);
        assertEquals(book.locationId, first.locationId);
        assertEquals(book.sourceChannel, first.sourceChannel);

        assertEquals(Book.STOCK_LENT, DetailCopyInfo.copyByIndex(book, copies, 2).stockStatus);
        BookCopy missing = DetailCopyInfo.copyByIndex(book, copies, 4);
        assertEquals(Book.STOCK_IN, missing.stockStatus);
        assertEquals(Book.OWN_BUY, missing.ownType);
    }

    @Test
    public void openRecordForCopy() {
        BorrowRecord record = new BorrowRecord();
        record.copyIndex = 2;
        assertEquals(record, DetailCopyInfo.openRecordForCopy(Collections.singletonList(record), 2));
        assertNull(DetailCopyInfo.openRecordForCopy(Collections.singletonList(record), 1));
    }

    @Test
    public void copyNoteText() {
        Book book = book();
        BookCopy copy = copy(2, Book.STOCK_IN);
        copy.note = " 副本备注 ";
        assertEquals("", DetailCopyInfo.copyNoteText(book, Collections.singletonList(copy), 1));
        assertEquals("副本备注", DetailCopyInfo.copyNoteText(book, Collections.singletonList(copy), 2));
    }

    @Test
    public void channelHint() {
        assertEquals("未填借阅来源", DetailCopyInfo.channelHint(Book.OWN_BORROW));
        assertEquals("未填赠送来源", DetailCopyInfo.channelHint(Book.OWN_GIFT));
        assertEquals("未填购买渠道", DetailCopyInfo.channelHint(Book.OWN_BUY));
    }

    @Test
    public void multiCopyStockSummary() {
        Book book = book();
        book.quantity = 3;
        List<BookCopy> copies = Arrays.asList(copy(2, Book.STOCK_LENT), copy(3, Book.STOCK_LOST));
        assertEquals("共 3 册（在库 1、借出 1、遗失 1）",
                DetailCopyInfo.multiCopyStockSummary(book, copies));
    }

    @Test
    public void copyLineContainsLocationAndBorrowInfo() {
        Book book = book();
        BookCopy copy = copy(2, Book.STOCK_LENT);
        copy.locationId = 2L;
        copy.sourceChannel = "";
        BorrowRecord record = new BorrowRecord();
        record.copyIndex = 2;
        record.borrower = "小明";
        record.lendDate = "2026-10-01";
        record.dueDate = "2099-01-01";
        Map<Long, String> locNames = new HashMap<>();
        locNames.put(2L, "客厅");

        String line = DetailCopyInfo.copyLine(book,
                Collections.singletonList(copy), Collections.singletonList(record),
                locNames, "书房", 2, record);
        assertTrue(line.contains("第 2 册：借出"));
        assertTrue(line.contains("客厅"));
        assertTrue(line.contains("借给 小明"));
        assertTrue(line.contains("应还 2099-01-01"));
    }

    private static Book book() {
        Book book = new Book();
        book.id = 1;
        book.title = "三体";
        book.quantity = 2;
        book.locationId = 1L;
        book.stockStatus = Book.STOCK_IN;
        book.ownType = Book.OWN_BUY;
        book.sourceChannel = "京东";
        book.purchaseDate = "2026-01-01";
        book.purchasePrice = 30;
        return book;
    }

    private static BookCopy copy(int index, int status) {
        BookCopy copy = new BookCopy();
        copy.bookId = 1;
        copy.copyIndex = index;
        copy.stockStatus = status;
        copy.ownType = Book.OWN_BUY;
        copy.purchasePrice = 20;
        return copy;
    }
}
