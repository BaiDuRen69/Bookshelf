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
        Map<Long, String> map = new HashMap<>(Math.max(16, books.size() * 2));
        for (Book book : books) {
            StringBuilder extra = extras.get(book.id);
            map.put(book.id, forBook(book, extra == null ? "" : extra.toString()));
        }
        return map;
    }

    /** 一本书的 haystack（拼音转写很贵，增量更新时只给受影响的书算） */
    public static String forBook(Book book, String excerptText) {
        return SearchText.haystack(book, excerptText == null ? "" : excerptText);
    }

    /** 某本书全部摘抄拼成的可搜索串（原文 / 批注 / 摘抄标签） */
    public static String excerptText(List<Excerpt> excerpts) {
        if (excerpts == null || excerpts.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Excerpt e : excerpts) {
            sb.append(SearchText.normalize(e.content)).append(' ')
                    .append(SearchText.normalize(e.note)).append(' ')
                    .append(SearchText.normalize(e.tags));
        }
        return sb.toString();
    }

    // ==================== 增量更新的判定（纯函数，便于单测）====================

    /**
     * 哪些书需要重算：新出现的、updatedAt 变了的，加上 {@code excerptTouched} 里
     * 仍然存在的书（摘抄改动的只有那几本）。
     */
    public static java.util.Set<Long> dirtyIds(List<Book> books,
                                               Map<Long, Long> stamped,
                                               java.util.Collection<Long> excerptTouched) {
        java.util.Set<Long> alive = new java.util.HashSet<>();
        java.util.Set<Long> dirty = new java.util.HashSet<>();
        if (books != null) {
            for (Book book : books) {
                alive.add(book.id);
                Long prev = stamped == null ? null : stamped.get(book.id);
                if (prev == null || prev != book.updatedAt) {
                    dirty.add(book.id);
                }
            }
        }
        if (excerptTouched != null) {
            for (Long id : excerptTouched) {
                if (alive.contains(id)) {
                    dirty.add(id);
                }
            }
        }
        return dirty;
    }

    /** 已经不在书里的 id（要能从索引和记账里一起摘掉） */
    public static java.util.Set<Long> removedIds(Iterable<Long> stampedIds, List<Book> books) {
        java.util.Set<Long> removed = new java.util.HashSet<>();
        if (stampedIds != null) {
            for (Long id : stampedIds) {
                removed.add(id);
            }
        }
        if (books != null) {
            for (Book book : books) {
                removed.remove(book.id);
            }
        }
        return removed;
    }

    /**
     * 要不要整表重建：首次、索引与记账条数对不上（上一轮没跑完），
     * 或者本轮要重算的书已经超过全库的四分之一——那时逐本查摘抄反而更慢。
     */
    public static boolean needsFullRebuild(boolean firstRun, int indexSize, int stampedSize,
                                           int dirtySize, int totalBooks) {
        if (firstRun || indexSize != stampedSize) {
            return true;
        }
        if (totalBooks <= 0) {
            return false;
        }
        return dirtySize * 4 > totalBooks;
    }
}
