package com.bai.bookshelf.util;

import androidx.annotation.Nullable;

import com.bai.bookshelf.data.entity.WishBook;

/**
 * 想读清单的内存过滤（v5.9.8 T3）。
 *
 * <p>查询词在 {@link #matches} 内部做一次 {@link WishBookMatcher#normalize} 归一化
 * （幂等，调用方预归一化也没问题），再对书名 / 作者 / ISBN / 备注逐字段归一化后
 * 做包含匹配；ISBN 额外走 {@link WishBookMatcher#normalizeIsbn}，用户粘贴
 * 「978-7-02-000873-8」这类带连字符的写法也能命中。
 */
public final class WishFilter {

    private WishFilter() {
    }

    /** 查询词内部会归一化；空查询直接全量放行 */
    public static boolean matches(@Nullable WishBook wish, @Nullable String query) {
        if (wish == null || query == null) {
            return true;
        }
        String q = WishBookMatcher.normalize(query).trim();
        if (q.isEmpty()) {
            return true;
        }
        return WishBookMatcher.normalize(wish.title).contains(q)
                || WishBookMatcher.normalize(wish.author).contains(q)
                || WishBookMatcher.normalizeIsbn(wish.isbn).contains(q)
                || WishBookMatcher.normalize(wish.note).contains(q);
    }
}
