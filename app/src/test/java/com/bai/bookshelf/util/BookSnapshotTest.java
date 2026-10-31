package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

/** 整行快照（v5.8.16）：批量改动的撤销靠它拿「改动前的整行」 */
public class BookSnapshotTest {

    @Test
    public void 克隆与原行互不影响() {
        Book book = new Book();
        book.title = "撤销测试";
        book.quantity = 3;
        book.locationId = 7L;
        book.readingProgress = 42;
        book.tags = "小说,历史";

        Book copy = BookSnapshot.copy(book);
        assertNotSame(book, copy);
        assertEquals("撤销测试", copy.title);
        assertEquals(3, copy.quantity);
        assertEquals(Long.valueOf(7L), copy.locationId);
        assertEquals(42, copy.readingProgress);
        assertEquals("小说,历史", copy.tags);

        copy.quantity = 9;
        copy.locationId = null;
        copy.tags = "";
        assertEquals(3, book.quantity);
        assertEquals(Long.valueOf(7L), book.locationId);
        assertEquals("小说,历史", book.tags);
    }

    @Test
    public void 空行不抛异常() {
        assertNull(BookSnapshot.copy(null));
    }
}
