package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Excerpt;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 书架搜索索引（v4.50 起含摘抄原文/批注/标签；v5.8.20 起支持增量更新）。
 * 全量重建在真机上是 3.6 秒/千本，增量判定错了就会每改一本书重来一遍。
 */
public class SearchIndexTest {

    private Book book(long id, long updatedAt) {
        Book book = new Book();
        book.id = id;
        book.title = "书" + id;
        book.author = "某人";
        book.updatedAt = updatedAt;
        return book;
    }

    private Map<Long, Long> stamps(Object... pairs) {
        Map<Long, Long> map = new HashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            map.put(((Number) pairs[i]).longValue(), ((Number) pairs[i + 1]).longValue());
        }
        return map;
    }

    @Test
    public void excerptContentAndNoteEnterIndex() {
        Book book = new Book();
        book.id = 7;
        book.title = "红楼梦";
        book.author = "曹雪芹";

        Excerpt e = new Excerpt();
        e.bookId = 7;
        e.content = "满纸荒唐言";
        e.note = "字字看来皆是血";
        e.tags = "泪";

        Map<Long, String> index = SearchIndex.build(
                Collections.singletonList(book), Collections.singletonList(e));
        String hay = index.get(7L);
        assertTrue(hay.contains("红楼梦"));
        assertTrue(hay.contains("满纸荒唐言"));
        assertTrue(hay.contains("字字看来皆是血"));
        assertTrue(hay.contains("泪"));
    }

    @Test
    public void indexCoversAllBooksAndIgnoresOtherBooksExcerpts() {
        Book a = new Book();
        a.id = 1;
        a.title = "三体";
        Book b = new Book();
        b.id = 2;
        b.title = "活着";

        Excerpt wrong = new Excerpt();
        wrong.bookId = 999;
        wrong.content = "别人的摘抄";

        Map<Long, String> index = SearchIndex.build(Arrays.asList(a, b),
                Collections.singletonList(wrong));
        assertTrue(index.get(1L).contains("三体"));
        assertTrue(index.get(2L).contains("活着"));
        assertFalse(index.get(1L).contains("别人的摘抄"));
    }

    @Test
    public void 只有新增与改过的书要重算() {
        List<Book> books = Arrays.asList(book(1, 100L), book(2, 200L), book(3, 300L));
        Map<Long, Long> stamped = stamps(1L, 100L, 2L, 199L);   // 2 改过，3 是新书
        Set<Long> dirty = SearchIndex.dirtyIds(books, stamped, null);
        assertEquals(new HashSet<>(Arrays.asList(2L, 3L)), dirty);
    }

    @Test
    public void 改动过的摘抄只带上还在的书() {
        List<Book> books = Arrays.asList(book(1, 100L), book(2, 200L));
        Map<Long, Long> stamped = stamps(1L, 100L, 2L, 200L);
        Set<Long> dirty = SearchIndex.dirtyIds(books, stamped, Arrays.asList(2L, 99L));
        assertEquals(new HashSet<>(Arrays.asList(2L)), dirty);
    }

    @Test
    public void 删掉的书能被摘出索引() {
        List<Book> books = Arrays.asList(book(1, 100L));
        Set<Long> gone = SearchIndex.removedIds(
                new HashSet<>(Arrays.asList(1L, 2L, 3L)), books);
        assertEquals(new HashSet<>(Arrays.asList(2L, 3L)), gone);
    }

    @Test
    public void 首次与账实不符时整表重建() {
        assertTrue(SearchIndex.needsFullRebuild(true, 0, 0, 1, 100));
        assertTrue(SearchIndex.needsFullRebuild(false, 5, 9, 1, 100));
    }

    @Test
    public void 改动面小走增量超过四分之一才整表() {
        assertFalse(SearchIndex.needsFullRebuild(false, 100, 100, 25, 100));
        assertTrue(SearchIndex.needsFullRebuild(false, 100, 100, 26, 100));
        assertFalse("空库不需要重建", SearchIndex.needsFullRebuild(false, 0, 0, 0, 0));
    }

    @Test
    public void 摘抄文本进索引且能命中() {
        Excerpt e = new Excerpt();
        e.bookId = 1;
        e.content = "命运的齿轮开始转动";
        e.note = "想到童年";
        e.tags = "人生";
        String text = SearchIndex.excerptText(new ArrayList<>(Arrays.asList(e)));
        assertTrue(text, text.contains(SearchText.normalize("命运的齿轮")));
        assertTrue(text, text.contains(SearchText.normalize("人生")));
        assertEquals("", SearchIndex.excerptText(null));
        assertEquals("", SearchIndex.excerptText(new ArrayList<>()));

        String haystack = SearchIndex.forBook(book(1, 100L), text);
        assertTrue("按摘抄内容要能命中",
                haystack.contains(SearchText.normalize("齿轮")));
        assertTrue("书名本身也要在", haystack.contains(SearchText.normalize("书1")));
    }

    @Test
    public void 全量与增量结果一致() {
        List<Book> books = Arrays.asList(book(1, 100L), book(2, 200L));
        Excerpt e = new Excerpt();
        e.bookId = 2;
        e.content = "一句摘抄";
        e.createdAt = 1L;
        List<Excerpt> excerpts = new ArrayList<>();
        excerpts.add(e);

        Map<Long, String> full = SearchIndex.build(books, excerpts);
        // 增量：只重算 2 号书，结果应与整表重建一致
        Map<Long, String> incremental = new HashMap<>(full);
        incremental.remove(2L);
        incremental.put(2L, SearchIndex.forBook(books.get(1),
                SearchIndex.excerptText(excerpts)));
        assertEquals(full.get(2L), incremental.get(2L));
        assertFalse(full.get(1L).contains(SearchText.normalize("一句摘抄")));
    }
}
