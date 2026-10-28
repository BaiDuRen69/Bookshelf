package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class SmartShelfTest {

    private static final int TODAY = 20261027;

    @Test
    public void readingAndStockStatuses() {
        Book book = completeBook(1);
        book.readStatus = Book.READING;
        assertTrue(SmartShelf.matches(book, SmartShelf.READING, null, TODAY));

        book.stockStatus = Book.STOCK_LENT;
        assertTrue(SmartShelf.matches(book, SmartShelf.LENT, null, TODAY));

        book.stockStatus = Book.STOCK_LOST;
        assertTrue(SmartShelf.matches(book, SmartShelf.LOST, null, TODAY));
    }

    @Test
    public void overdueOnlyBeforeToday() {
        Book book = completeBook(1);
        BorrowRecord loan = new BorrowRecord();
        loan.dueDate = "2026-10-26";
        assertTrue(SmartShelf.matches(book, SmartShelf.OVERDUE, loan, TODAY));

        loan.dueDate = "2026-10-27";
        assertFalse(SmartShelf.matches(book, SmartShelf.OVERDUE, loan, TODAY));

        loan.dueDate = "";
        assertFalse(SmartShelf.matches(book, SmartShelf.OVERDUE, loan, TODAY));
        assertFalse(SmartShelf.matches(book, SmartShelf.OVERDUE, null, TODAY));
    }

    @Test
    public void recentPurchaseWindowIsInclusive() {
        Book book = completeBook(1);
        book.purchaseDate = "2026-10-01";
        assertTrue(SmartShelf.matches(book, SmartShelf.RECENT, null, TODAY));

        book.purchaseDate = "2026-09-28";
        assertTrue(SmartShelf.matches(book, SmartShelf.RECENT, null, TODAY));

        book.purchaseDate = "2026-09-27";
        assertFalse(SmartShelf.matches(book, SmartShelf.RECENT, null, TODAY));

        book.purchaseDate = "";
        assertFalse(SmartShelf.matches(book, SmartShelf.RECENT, null, TODAY));
    }

    @Test
    public void completenessRules() {
        Book book = completeBook(1);
        assertFalse(SmartShelf.matches(book, SmartShelf.NO_COVER, null, TODAY));
        assertFalse(SmartShelf.matches(book, SmartShelf.NO_LOCATION, null, TODAY));
        assertFalse(SmartShelf.matches(book, SmartShelf.INCOMPLETE, null, TODAY));
        assertFalse(SmartShelf.matches(book, SmartShelf.UNRATED, null, TODAY));

        book.coverUrl = "";
        assertTrue(SmartShelf.matches(book, SmartShelf.NO_COVER, null, TODAY));
        assertTrue(SmartShelf.matches(book, SmartShelf.INCOMPLETE, null, TODAY));

        Book noLocation = completeBook(2);
        noLocation.locationId = null;
        assertTrue(SmartShelf.matches(noLocation, SmartShelf.NO_LOCATION, null, TODAY));

        Book noCategory = completeBook(3);
        noCategory.category = "";
        assertTrue(SmartShelf.matches(noCategory, SmartShelf.INCOMPLETE, null, TODAY));

        Book unrated = completeBook(4);
        unrated.rating = 0;
        assertTrue(SmartShelf.matches(unrated, SmartShelf.UNRATED, null, TODAY));
    }

    @Test
    public void countsMatchPredicates() {
        Book reading = completeBook(1);
        reading.readStatus = Book.READING;

        Book lent = completeBook(2);
        lent.stockStatus = Book.STOCK_LENT;

        Book lost = completeBook(3);
        lost.stockStatus = Book.STOCK_LOST;

        BorrowRecord loan = new BorrowRecord();
        loan.bookId = 2;
        loan.dueDate = "2026-10-01";
        Map<Long, BorrowRecord> loans = new HashMap<>();
        loans.put(2L, loan);

        int[] counts = SmartShelf.counts(Arrays.asList(reading, lent, lost), loans, TODAY);
        assertEquals(1, counts[SmartShelf.READING]);
        assertEquals(1, counts[SmartShelf.LENT]);
        assertEquals(1, counts[SmartShelf.OVERDUE]);
        assertEquals(1, counts[SmartShelf.LOST]);
    }

    /** 各字段齐全的书：避免被「无封面/无地点/未评分」等入口误计入 */
    private static Book completeBook(long id) {
        Book b = new Book();
        b.id = id;
        b.title = "书" + id;
        b.author = "作者";
        b.publisher = "出版社";
        b.coverUrl = "covers/cover.jpg";
        b.locationId = 1L;
        b.category = "小说";
        b.tags = "标签";
        b.rating = 5;
        b.purchaseDate = "2020-01-01";
        b.stockStatus = Book.STOCK_IN;
        b.readStatus = Book.READ_NONE;
        return b;
    }
}
