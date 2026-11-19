package com.bai.bookshelf.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.WishBook;

import org.junit.Test;

/** 想读清单搜索（v5.9.8 T3）的纯 JVM 校验：归一化匹配四个字段。 */
public class WishFilterTest {

    private static WishBook wish(String title, String author, String isbn, String note) {
        WishBook w = new WishBook();
        w.title = title == null ? "" : title;
        w.author = author == null ? "" : author;
        w.isbn = isbn == null ? "" : isbn;
        w.note = note == null ? "" : note;
        return w;
    }

    @Test
    public void 空查询全量放行() {
        assertTrue(WishFilter.matches(wish("三体", "", "", ""), ""));
        assertTrue(WishFilter.matches(wish("三体", "", "", ""), null));
    }

    @Test
    public void 书名与作者与备注都能命中() {
        assertTrue(WishFilter.matches(wish("三体", "刘慈欣", "", ""), "三体"));
        assertTrue(WishFilter.matches(wish("三体", "刘慈欣", "", ""), "刘慈欣"));
        assertTrue(WishFilter.matches(wish("三体", "", "", "书店有货"), "书店"));
        assertFalse(WishFilter.matches(wish("三体", "", "", ""), "活着"));
    }

    @Test
    public void 归一化忽略大小写空格与标点() {
        assertTrue(WishFilter.matches(wish("The Three-Body Problem", "", "", ""), "threebody"));
        assertTrue(WishFilter.matches(wish("三体", "", "", ""), "三 体"));
    }

    @Test
    public void isbn带连字符也能命中() {
        WishBook w = wish("", "", "978-7-02-000873-8", "");
        assertTrue("粘贴带连字符的 ISBN 也能搜到", WishFilter.matches(w, "9787020008738"));
        assertTrue(WishFilter.matches(w, "978-7"));
        assertFalse(WishFilter.matches(w, "978111"));
    }
}
