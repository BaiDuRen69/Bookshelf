package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 进度换算与「在读还剩多少页」（v5.8.19）：页码 ↔ 百分比来回换算、
 * 只算填了页数的书、速度样本不足时不许编出一个日期。
 */
public class ReadingProgressTest {

    private Book reading(String title, int pages, int percent) {
        Book book = new Book();
        book.title = title;
        book.pages = pages;
        book.readingProgress = percent;
        book.readStatus = Book.READING;
        return book;
    }

    @Test
    public void 页码与百分比来回换算() {
        assertEquals(38, ReadingProgress.percentForPage(120, 320));
        assertEquals(100, ReadingProgress.percentForPage(320, 320));
        assertEquals(0, ReadingProgress.percentForPage(0, 320));
        assertEquals(122, ReadingProgress.pageForPercent(38, 320));
        // 越界夹住，不把 120% 这种东西写进库
        assertEquals(100, ReadingProgress.percentForPage(999, 320));
        assertEquals(0, ReadingProgress.pageForPercent(-5, 320));
    }

    @Test
    public void 没填页数时换算与剩余都为空值语义() {
        assertEquals(0, ReadingProgress.percentForPage(50, 0));
        assertEquals(0, ReadingProgress.pageForPercent(50, 0));
        assertEquals(-1, ReadingProgress.pagesLeft(reading("无页数", 0, 60)));
    }

    @Test
    public void 剩余页数按进度算() {
        assertEquals(198, ReadingProgress.pagesLeft(reading("三体", 320, 38)));
        assertEquals(0, ReadingProgress.pagesLeft(reading("读完", 320, 100)));
    }

    @Test
    public void 在读盘点只算填了页数的书() {
        List<Book> books = new ArrayList<>(Arrays.asList(
                reading("A", 300, 100),   // 还剩 0 页
                reading("B", 200, 50),    // 还剩 100 页
                reading("C", 0, 30),      // 没填页数
                reading("D", 400, 0)));   // 还剩 400 页
        Book done = new Book();
        done.title = "E";
        done.pages = 100;
        done.readStatus = Book.READ_DONE;
        books.add(done); // 已读不参与

        StatsPace.Backlog b = StatsPace.backlog(books, 20, 20261031);
        assertEquals(4, b.titles);
        assertEquals(3, b.counted);
        assertEquals(500, b.pagesLeft);
        assertEquals(25, b.daysLeft);
        assertEquals("2026-11-25", b.etaDate);
        assertTrue(b.line(), b.line().contains("在读 4 本"));
        assertTrue(b.line(), b.line().contains("还剩约 500 页"));
        assertTrue("没填页数的那本要单独报出来", b.line().contains("1 本没填页数"));
        assertTrue(b.line(), b.line().contains("约 25 天后"));
    }

    @Test
    public void 速度算不出时不许编日期() {
        List<Book> books = new ArrayList<>();
        books.add(reading("A", 300, 0));
        StatsPace.Backlog b = StatsPace.backlog(books, -1, 20261031);
        assertEquals(-1, b.daysLeft);
        assertEquals(null, b.etaDate);
        assertTrue(b.line(), b.line().contains("算不出预计读完日"));
    }

    @Test
    public void 没有在读或都没填页数时说人话() {
        assertEquals("现在没有「在读」的书 · 去书架把要读的点成在读",
                StatsPace.backlog(new ArrayList<>(), 30, 20261031).line());
        List<Book> books = new ArrayList<>();
        books.add(reading("没填页数", 0, 40));
        assertTrue(StatsPace.backlog(books, 30, 20261031).line()
                .contains("都没填总页数"));
    }

    @Test
    public void 都读到百分之百时提醒去标已读() {
        List<Book> books = new ArrayList<>();
        books.add(reading("读完没标", 300, 100));
        StatsPace.Backlog b = StatsPace.backlog(books, 30, 20261031);
        assertEquals(0, b.pagesLeft);
        assertTrue(b.line(), b.line().contains("去标个「已读」"));
    }
}
