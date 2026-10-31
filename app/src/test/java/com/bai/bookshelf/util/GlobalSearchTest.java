package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.data.entity.WishBook;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 全局搜索的分组匹配（v5.8.16）：纯数据逻辑，不碰视图 */
public class GlobalSearchTest {

    private Book book(long id, String title, String author) {
        Book b = new Book();
        b.id = id;
        b.title = title;
        b.author = author;
        return b;
    }

    private List<Book> books() {
        List<Book> list = new ArrayList<>();
        list.add(book(1, "三体", "刘慈欣"));
        list.add(book(2, "活着", "余华"));
        return list;
    }

    private Map<Long, String> index(List<Book> books) {
        Map<Long, String> index = new HashMap<>();
        for (Book b : books) {
            index.put(b.id, SearchText.haystack(b));
        }
        return index;
    }

    @Test
    public void 四组各自命中自己的那部分() {
        List<Book> books = books();
        Map<Long, String> titles = new HashMap<>();
        titles.put(1L, "三体");
        List<Excerpt> excerpts = new ArrayList<>();
        Excerpt e = new Excerpt();
        e.id = 11;
        e.bookId = 1;
        e.content = "给岁月以文明，而不是给文明以岁月";
        excerpts.add(e);
        List<WishBook> wishes = new ArrayList<>();
        WishBook w = new WishBook();
        w.id = 21;
        w.title = "球状闪电";
        w.author = "刘慈欣";
        wishes.add(w);

        GlobalSearch.Result byBook = GlobalSearch.query(
                SearchText.normalize("三体"), books, index(books), excerpts, titles, wishes, 6);
        assertEquals(1, byBook.books.size());
        assertEquals(1L, byBook.books.get(0).id);
        assertEquals(0, byBook.wishes.size());

        GlobalSearch.Result byAuthor = GlobalSearch.query(
                SearchText.normalize("刘慈欣"), books, index(books), excerpts, titles, wishes, 6);
        assertEquals(1, byAuthor.books.size());
        assertEquals(1, byAuthor.wishes.size());
        assertEquals(21L, byAuthor.wishes.get(0).id);
        // 摘抄只按正文/批注/所属书名命中，作者不参与
        assertEquals(0, byAuthor.excerpts.size());

        GlobalSearch.Result byExcerpt = GlobalSearch.query(
                SearchText.normalize("给岁月以文明"), books, index(books), excerpts, titles,
                wishes, 6);
        assertEquals(1, byExcerpt.excerpts.size());
        assertEquals(0, byExcerpt.books.size());
    }

    @Test
    public void 功能入口按关键词命中() {
        GlobalSearch.Result r = GlobalSearch.query(
                SearchText.normalize("指纹"), new ArrayList<>(), new HashMap<>(),
                new ArrayList<>(), new HashMap<>(), new ArrayList<>(), 6);
        assertEquals(1, r.actions.size());
        assertEquals(GlobalSearch.Target.APP_LOCK, r.actions.get(0).target);

        GlobalSearch.Result backup = GlobalSearch.query(
                SearchText.normalize("恢复"), new ArrayList<>(), new HashMap<>(),
                new ArrayList<>(), new HashMap<>(), new ArrayList<>(), 6);
        assertTrue(backup.actions.size() >= 1);
    }

    @Test
    public void 每组按上限截断() {
        List<Book> many = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            many.add(book(i + 1, "测试书" + i, "某人"));
        }
        GlobalSearch.Result r = GlobalSearch.query(
                SearchText.normalize("测试书"), many, index(many),
                new ArrayList<>(), new HashMap<>(), new ArrayList<>(), 6);
        assertEquals(6, r.books.size());
    }

    @Test
    public void 空关键词不返回结果() {
        List<Book> books = books();
        GlobalSearch.Result r = GlobalSearch.query("", books, index(books),
                new ArrayList<>(), new HashMap<>(), new ArrayList<>(), 6);
        assertTrue(r.isEmpty());
    }

    @Test
    public void 没有拼音索引时退化原文匹配() {
        List<Book> books = books();
        GlobalSearch.Result r = GlobalSearch.query(
                SearchText.normalize("活着"), books, new HashMap<>(),
                new ArrayList<>(), new HashMap<>(), new ArrayList<>(), 6);
        assertEquals(1, r.books.size());
        assertEquals(2L, r.books.get(0).id);
    }
}
