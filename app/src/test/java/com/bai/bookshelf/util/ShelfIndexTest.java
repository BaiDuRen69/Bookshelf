package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

/** 字母索引（v5.8.16）：桶的生成与「跳到该字母或下一个有内容的字母」的定位 */
public class ShelfIndexTest {

    private Book book(long id, String title, String author) {
        Book b = new Book();
        b.id = id;
        b.title = title;
        b.author = author;
        return b;
    }

    @Test
    public void 只有书名与作者排序给字母桶() {
        List<Book> books = Arrays.asList(book(1, "Apple", "Zhang"), book(2, "Banana", "Li"));
        assertEquals(Arrays.asList("A", "B"), ShelfIndex.letters(books, ShelfIndex.SORT_TITLE));
        assertEquals(Arrays.asList("Z", "L"), ShelfIndex.letters(books, ShelfIndex.SORT_AUTHOR));
        assertTrue(ShelfIndex.letters(books, 0).isEmpty());
        assertTrue(ShelfIndex.letters(books, 3).isEmpty());
    }

    @Test
    public void 作者为空时退回书名_数字开头归井号() {
        // 汉字要系统拼音转写（Android 10+），JVM 单测里只验 ASCII 与数字
        List<Book> books = Arrays.asList(book(1, "Whatever", ""), book(2, "1984", ""));
        List<String> letters = ShelfIndex.letters(books, ShelfIndex.SORT_AUTHOR);
        assertEquals("W", letters.get(0));          // 作者没填 → 按书名归桶
        assertEquals(ShelfIndex.HASH, letters.get(1)); // 数字开头不进字母表
    }

    @Test
    public void 升序列表定位到目标或下一个有内容的字母() {
        List<String> letters = Arrays.asList("#", "A", "A", "C", "F", "F", "Z");
        assertEquals(0, ShelfIndex.firstPositionAtOrAfter(letters, ShelfIndex.HASH));
        assertEquals(1, ShelfIndex.firstPositionAtOrAfter(letters, "A"));
        assertEquals(3, ShelfIndex.firstPositionAtOrAfter(letters, "B"));
        assertEquals(4, ShelfIndex.firstPositionAtOrAfter(letters, "D"));
        assertEquals(6, ShelfIndex.firstPositionAtOrAfter(letters, "Y"));
        assertEquals(6, ShelfIndex.firstPositionAtOrAfter(letters, "Z"));
        assertEquals(-1, ShelfIndex.firstPositionAtOrAfter(letters, "@"));
        assertEquals(-1, ShelfIndex.firstPositionAtOrAfter(java.util.Collections.emptyList(), "A"));
    }

    @Test
    public void 倒序列表定位到该字母那一组的开头() {
        List<String> letters = Arrays.asList("Z", "F", "F", "C", "A", "A", "#");
        assertEquals(0, ShelfIndex.firstPositionAtOrAfter(letters, "Z"));
        assertEquals(1, ShelfIndex.firstPositionAtOrAfter(letters, "G"));
        assertEquals(3, ShelfIndex.firstPositionAtOrAfter(letters, "D"));
        assertEquals(4, ShelfIndex.firstPositionAtOrAfter(letters, "B"));
        assertEquals(6, ShelfIndex.firstPositionAtOrAfter(letters, ShelfIndex.HASH));
    }

    @Test
    public void 可用刻度用于把空字母画淡() {
        List<String> letters = Arrays.asList("A", "A", "C");
        assertEquals(2, ShelfIndex.available(letters).size());
        assertTrue(ShelfIndex.available(letters).contains("A"));
        assertTrue(!ShelfIndex.available(letters).contains("B"));
    }
}
