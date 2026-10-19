package com.bai.bookshelf.util;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Category;

import java.util.List;

/**
 * 分类联动传播器：分类库里的删除/重命名要同步到所有书籍的 category 字段。
 * 与标签不同，分类是单值字段，直接等值替换即可。
 * 个人藏书量小，全量遍历开销可忽略。
 */
public final class CategoryPropagator {

    private CategoryPropagator() {
    }

    /**
     * 分类展示格式（v4.9）：「一级-二级」。
     * raw 是二级分类名 → 拼「所属一级-二级」；一级名或库外自定义名 → 原样返回；空 → 空串。
     */
    public static String displayPath(String raw, List<Category> all) {
        if (raw == null || raw.trim().isEmpty()) {
            return "";
        }
        String name = raw.trim();
        for (Category c : all) {
            if (c.parentId != null && c.name.equals(name)) {
                for (Category p : all) {
                    if (p.id == c.parentId) {
                        return p.name + "-" + name;
                    }
                }
                return name;
            }
        }
        return name;
    }

    /** 删除分类后调用：把所有书籍上的该分类清空 */
    public static void removeCategoryEverywhere(AppDatabase db, String name) {
        for (Book book : BookSanitizer.normalizeAll(db.bookDao().getAll())) {
            if (book.category.equals(name)) {
                book.category = "";
                book.updatedAt = System.currentTimeMillis();
                db.bookDao().update(book);
            }
        }
    }

    /** 重命名分类后调用：所有书籍上的旧名换成新名 */
    public static void renameCategoryEverywhere(AppDatabase db, String oldName, String newName) {
        for (Book book : BookSanitizer.normalizeAll(db.bookDao().getAll())) {
            if (book.category.equals(oldName)) {
                book.category = newName;
                book.updatedAt = System.currentTimeMillis();
                db.bookDao().update(book);
            }
        }
    }
}
