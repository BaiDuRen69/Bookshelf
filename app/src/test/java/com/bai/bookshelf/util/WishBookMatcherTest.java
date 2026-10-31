package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.WishBook;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class WishBookMatcherTest {

    @Test
    public void sameIsbnWithDifferentPunctuationIsDuplicate() {
        WishBook existing = wish(1, "三体", "刘慈欣", "978-7-536-69293-0");
        WishBook candidate = wish(0, "三体（新版）", "刘慈欣", "9787536692930");
        assertSame(existing, WishBookMatcher.findDuplicate(
                Collections.singletonList(existing), candidate));
    }

    @Test
    public void differentIsbnAllowsDifferentEdition() {
        WishBook existing = wish(1, "三体", "刘慈欣", "9787536692930");
        WishBook candidate = wish(0, "三体", "刘慈欣", "9787536692947");
        assertNull(WishBookMatcher.findDuplicate(
                Collections.singletonList(existing), candidate));
    }

    @Test
    public void titleAndAuthorUsedWhenIsbnMissing() {
        WishBook existing = wish(1, "Red  Book!", "Author", "");
        WishBook candidate = wish(0, "redbook", " author ", "");
        assertSame(existing, WishBookMatcher.findDuplicate(
                Collections.singletonList(existing), candidate));
    }

    @Test
    public void sameTitleDifferentAuthorsAllowed() {
        WishBook existing = wish(1, "红楼梦", "曹雪芹", "");
        WishBook candidate = wish(0, "红楼梦", "另一位作者", "");
        assertNull(WishBookMatcher.findDuplicate(
                Collections.singletonList(existing), candidate));
    }

    @Test
    public void emptyAuthorMatchesByTitle() {
        WishBook existing = wish(1, "活着", "余华", "");
        WishBook candidate = wish(0, "活着", "", "");
        assertSame(existing, WishBookMatcher.findDuplicate(
                Collections.singletonList(existing), candidate));
    }

    @Test
    public void editingSelfIsNotDuplicate() {
        WishBook existing = wish(7, "三体", "刘慈欣", "9787536692930");
        WishBook candidate = wish(7, "三体", "刘慈欣", "9787536692930");
        assertNull(WishBookMatcher.findDuplicate(
                Collections.singletonList(existing), candidate));
    }

    @Test
    public void nullAndEmptyListAreSafe() {
        WishBook candidate = wish(0, "三体", "刘慈欣", "");
        assertNull(WishBookMatcher.findDuplicate(null, candidate));
        assertNull(WishBookMatcher.findDuplicate(Collections.emptyList(), candidate));
        assertEquals("", WishBookMatcher.normalizeIsbn(null));
        assertEquals("", WishBookMatcher.normalize(null));
    }

    @Test
    public void duplicateInMiddleOfListIsFound() {
        WishBook a = wish(1, "A", "甲", "");
        WishBook b = wish(2, "B", "乙", "");
        List<WishBook> items = Arrays.asList(a, b);
        WishBook candidate = wish(0, "b", "乙", "");
        assertSame(b, WishBookMatcher.findDuplicate(items, candidate));
    }

    private static WishBook wish(long id, String title, String author, String isbn) {
        WishBook w = new WishBook();
        w.id = id;
        w.title = title;
        w.author = author;
        w.isbn = isbn;
        return w;
    }

    @Test
    public void findInLibraryMatchesOwnedBookByTitleAndAuthor() {
        Book owned = book(1, "三体", "刘慈欣", "");
        WishBook wish = wish(0, "三体", "刘慈欣", "");
        assertSame(owned, WishBookMatcher.findInLibrary(
                Collections.singletonList(owned), wish));
    }

    @Test
    public void findInLibrarySkipsReturnedBorrowBookmark() {
        Book returned = book(1, "三体", "刘慈欣", "");
        returned.ownType = Book.OWN_BORROW;
        returned.borrowReturnDate = "2026-10-30 10:00";
        returned.stockStatus = Book.STOCK_BOOKMARK;
        WishBook wish = wish(0, "三体", "刘慈欣", "");
        assertNull(WishBookMatcher.findInLibrary(
                Collections.singletonList(returned), wish));
    }

    @Test
    public void findInLibraryAllowsDifferentEditionByIsbn() {
        Book owned = book(1, "三体", "刘慈欣", "9787536692930");
        WishBook wish = wish(0, "三体", "刘慈欣", "9787536692947");
        assertNull(WishBookMatcher.findInLibrary(
                Collections.singletonList(owned), wish));
    }

    private static Book book(long id, String title, String author, String isbn) {
        Book b = new Book();
        b.id = id;
        b.title = title;
        b.author = author;
        b.isbn = isbn;
        return b;
    }
}
