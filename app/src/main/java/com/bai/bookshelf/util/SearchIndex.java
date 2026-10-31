package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Excerpt;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 书架搜索索引（v4.50 起把摘抄原文/批注/标签也纳入）：
 * bookId → 归一化可搜索串（原文 + 全拼 + 首字母）。
 */
public final class SearchIndex {

    private SearchIndex() {
    }

    public static Map<Long, String> build(List<Book> books, List<Excerpt> excerpts) {
        Map<Long, String> map = new HashMap<>(Math.max(16, books.size() * 2));
        Map<Long, StringBuilder> extras = new HashMap<>();
        if (excerpts != null) {
            for (Excerpt e : excerpts) {
                StringBuilder sb = extras.get(e.bookId);
                if (sb == null) {
                    sb = new StringBuilder();
                    extras.put(e.bookId, sb);
                }
                sb.append(SearchText.normalize(e.content)).append(' ')
                        .append(SearchText.normalize(e.note)).append(' ')
                        .append(SearchText.normalize(e.tags));
            }
        }
        for (Book book : books) {
            StringBuilder extra = extras.get(book.id);
            map.put(book.id, SearchText.haystack(book, extra == null ? "" : extra.toString()));
        }
        return map;
    }
}
