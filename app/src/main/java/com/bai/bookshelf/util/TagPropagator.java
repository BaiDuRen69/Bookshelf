package com.bai.bookshelf.util;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 标签联动传播器：标签库里的删除/重命名要同步到所有书籍的 tags 字段。
 * 书籍上的标签是逗号分隔的字符串，处理时拆分→精确匹配→重组。
 * 个人藏书量小，全量遍历开销可忽略。
 */
public final class TagPropagator {

    private TagPropagator() {
    }

    /** 删除标签后调用：把该标签从所有书籍上摘掉 */
    public static void removeTagEverywhere(AppDatabase db, String tagName) {
        for (Book book : BookSanitizer.normalizeAll(db.bookDao().getAll())) {
            List<String> tags = split(book.tags);
            if (tags.remove(tagName)) {
                saveTags(db, book, tags);
            }
        }
    }

    /** 重命名标签后调用：所有书籍上的旧名换成新名（自动去重） */
    public static void renameTagEverywhere(AppDatabase db, String oldName, String newName) {
        for (Book book : BookSanitizer.normalizeAll(db.bookDao().getAll())) {
            List<String> tags = split(book.tags);
            boolean changed = false;
            for (int i = 0; i < tags.size(); i++) {
                if (tags.get(i).equals(oldName)) {
                    tags.set(i, newName);
                    changed = true;
                }
            }
            if (changed) {
                // 新名可能原本就在这本书上，去重
                saveTags(db, book, new ArrayList<>(new LinkedHashSet<>(tags)));
            }
        }
    }

    /** 拆分逗号分隔的标签字符串为干净列表 */
    private static List<String> split(String tags) {
        List<String> list = new ArrayList<>();
        if (tags == null) {
            return list;
        }
        for (String t : tags.split(",")) {
            String s = t.trim();
            if (!s.isEmpty() && !list.contains(s)) {
                list.add(s);
            }
        }
        return list;
    }

    /** 重组标签字符串并写库 */
    private static void saveTags(AppDatabase db, Book book, List<String> tags) {
        book.tags = String.join(",", tags);
        book.updatedAt = System.currentTimeMillis();
        db.bookDao().update(book);
    }
}
