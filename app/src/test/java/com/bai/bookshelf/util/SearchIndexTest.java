package com.bai.bookshelf.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Excerpt;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

public class SearchIndexTest {

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
}
