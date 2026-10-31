package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.util.BatchImport;

import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量录入入库与回滚（v5.8.16 粘贴 ISBN 清单）：只写勾选的可入库书，重复的按开关加一册，
 * 撤销按主键整批回滚。跑在 devtest 包上，结束时清掉自己的数据。
 */
@RunWith(AndroidJUnit4.class)
public class BatchImportTest {

    private Book seedExisting(BookshelfApp app, String isbn) {
        Book exist = new Book();
        exist.title = "批量回滚测试·已有";
        exist.isbn = isbn;
        exist.quantity = 1;
        exist.createdAt = System.currentTimeMillis();
        exist.updatedAt = exist.createdAt;
        exist.id = app.db().bookDao().insert(exist);
        return exist;
    }

    private boolean exists(BookshelfApp app, String isbn) {
        for (Book book : app.db().bookDao().getAll()) {
            if (isbn.equals(book.isbn)) {
                return true;
            }
        }
        return false;
    }

    /** 上一轮跑挂了留下的同前缀数据先清掉，避免累计 */
    private void purgeMine(BookshelfApp app) {
        for (Book book : app.db().bookDao().getAll()) {
            if (book.title != null && book.title.startsWith("批量回滚测试")) {
                app.db().bookDao().deleteById(book.id);
            }
        }
    }

    @Test
    public void 保存勾选条目并回滚整批() {
        BookshelfApp app = BookshelfApp.get();
        purgeMine(app);
        String dupIsbn = "978100000000" + System.currentTimeMillis() % 100;
        Book exist = seedExisting(app, dupIsbn);

        BatchImport.Item newBook = new BatchImport.Item("9787111544937");
        newBook.status = BatchImport.READY;
        Book created = new Book();
        created.title = "批量回滚测试·新书";
        created.isbn = newBook.isbn;
        created.quantity = 1;
        newBook.book = created;

        BatchImport.Item skipped = new BatchImport.Item("9787020002207");
        skipped.status = BatchImport.READY;
        Book notWanted = new Book();
        notWanted.title = "批量回滚测试·取消勾选";
        notWanted.isbn = skipped.isbn;
        skipped.book = notWanted;
        skipped.included = false;

        BatchImport.Item duplicate = new BatchImport.Item(dupIsbn);
        duplicate.status = BatchImport.DUPLICATE;
        duplicate.existingId = exist.id;

        BatchImport.Item failed = new BatchImport.Item("9780000000000");
        failed.status = BatchImport.FAILED;

        List<BatchImport.Item> items = new ArrayList<>();
        items.add(newBook);
        items.add(skipped);
        items.add(duplicate);
        items.add(failed);

        assertEquals(1, BatchImport.saveableCount(items));
        assertEquals(1, BatchImport.copyCount(items, true));
        assertEquals(0, BatchImport.copyCount(items, false));

        BatchImport.Result res = BatchImport.save(app, items, -1, true);
        assertEquals(1, res.saved);
        assertEquals(1, res.copied);
        assertEquals(1, res.savedIds.size());
        long newId = res.savedIds.get(0);
        assertNotNull(app.db().bookDao().getById(newId));
        assertEquals("重复的应加一册", 2, app.db().bookDao().getById(exist.id).quantity);
        assertFalse("取消勾选的不入库", exists(app, skipped.isbn));

        BatchImport.rollback(app, res);
        assertNull("撤销后新书应消失", app.db().bookDao().getById(newId));
        assertEquals("撤销后副本数应回退", 1, app.db().bookDao().getById(exist.id).quantity);

        app.db().bookDao().deleteById(exist.id);
        assertNull(app.db().bookDao().getById(exist.id));
    }

    @Test
    public void 不勾副本时重复条目不动() {
        BookshelfApp app = BookshelfApp.get();
        purgeMine(app);
        String dupIsbn = "978200000000" + System.currentTimeMillis() % 100;
        Book exist = seedExisting(app, dupIsbn);

        BatchImport.Item duplicate = new BatchImport.Item(dupIsbn);
        duplicate.status = BatchImport.DUPLICATE;
        duplicate.existingId = exist.id;

        List<BatchImport.Item> items = new ArrayList<>();
        items.add(duplicate);

        BatchImport.Result res = BatchImport.save(app, items, -1, false);
        assertEquals(0, res.saved);
        assertEquals(0, res.copied);
        assertEquals(1, app.db().bookDao().getById(exist.id).quantity);
        app.db().bookDao().deleteById(exist.id);
    }
}
