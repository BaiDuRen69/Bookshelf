package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.util.BookCascade;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 可撤销删除（v5.8.16）：删书留快照 → 文件不立刻删 → 撤销按原主键写回 →
 * 窗口结束后才回收文件。跑在 devtest 包上，不碰用户的库。
 */
@RunWith(AndroidJUnit4.class)
public class BookCascadeUndoTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private File write(String dir, String name) throws Exception {
        File folder = new File(ctx().getFilesDir(), dir);
        if (!folder.exists()) {
            folder.mkdirs();
        }
        File f = new File(folder, name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(new byte[]{1, 2, 3, 4});
        }
        return f;
    }

    @Test
    public void 删除可撤销且文件延后回收() throws Exception {
        BookshelfApp app = BookshelfApp.get();
        long now = System.currentTimeMillis();

        Book book = new Book();
        book.title = "撤销级联测试";
        book.isbn = "978" + (now % 1000000000L);
        book.quantity = 2;
        book.createdAt = now;
        book.updatedAt = now;
        long bookId = app.db().bookDao().insert(book);
        book.id = bookId;

        BookCopy copy = new BookCopy();
        copy.bookId = bookId;
        copy.copyIndex = 2;
        copy.ownType = Book.OWN_BUY;
        copy.stockStatus = Book.STOCK_IN;
        app.db().bookCopyDao().insert(copy);

        BorrowRecord record = new BorrowRecord();
        record.bookId = bookId;
        record.borrower = "测试借阅人";
        record.lendDate = "2026-10-01";
        record.dueDate = "2026-10-31";
        record.copyIndex = 1;
        app.db().borrowRecordDao().insert(record);

        Excerpt excerpt = new Excerpt();
        excerpt.bookId = bookId;
        excerpt.content = "一句摘抄";
        excerpt.createdAt = now;
        File excerptImage = write("excerpt_images", "undo_test_" + now + ".jpg");
        excerpt.imagePath = excerptImage.getAbsolutePath();
        app.db().excerptDao().insert(excerpt);

        NoteImage image = new NoteImage();
        image.bookId = bookId;
        image.ownerKey = "book:" + bookId;
        File noteImage = write("note_images", "undo_test_" + now + ".jpg");
        image.path = noteImage.getAbsolutePath();
        image.sort = 0;
        app.db().noteImageDao().insert(image);

        // 1) 删除：行全部消失，但文件还在（撤销窗口内）
        BookCascade.Deleted deleted = BookCascade.deleteRecoverable(app, bookId);
        assertNull("主记录应已删除", app.db().bookDao().getById(bookId));
        assertTrue(app.db().bookCopyDao().getByBook(bookId).isEmpty());
        assertTrue(app.db().borrowRecordDao().getByBook(bookId).isEmpty());
        assertTrue(app.db().excerptDao().getByBook(bookId).isEmpty());
        assertTrue(app.db().noteImageDao().getByBook(bookId).isEmpty());
        assertEquals(1, deleted.copies.size());
        assertEquals(1, deleted.records.size());
        assertEquals(1, deleted.excerpts.size());
        assertEquals(1, deleted.images.size());
        assertTrue("延后的文件不该被删", noteImage.exists() && excerptImage.exists());
        assertEquals(2, deleted.files.size());

        // 2) 撤销：按原主键写回，关联数据一条不少
        BookCascade.restore(app, deleted);
        Book restored = app.db().bookDao().getById(bookId);
        assertNotNull("撤销后主记录应回来", restored);
        assertEquals(bookId, restored.id);
        assertEquals("撤销级联测试", restored.title);
        assertEquals(1, app.db().bookCopyDao().getByBook(bookId).size());
        assertEquals(1, app.db().borrowRecordDao().getByBook(bookId).size());
        assertEquals(1, app.db().excerptDao().getByBook(bookId).size());
        assertEquals(1, app.db().noteImageDao().getByBook(bookId).size());

        // 3) 窗口结束：文件才真正回收
        BookCascade.deleteFiles(app, deleted.files);
        assertFalse(noteImage.exists());
        assertFalse(excerptImage.exists());

        // 4) 收尾：把这条测试数据清掉，不给 devtest 库留垃圾
        BookCascade.Deleted cleanup = BookCascade.deleteRecoverable(app, bookId);
        BookCascade.deleteFiles(app, cleanup.files);
        assertNull(app.db().bookDao().getById(bookId));
    }

    @Test
    public void 只删应用私有目录内的文件() throws Exception {
        BookshelfApp app = BookshelfApp.get();
        File outside = ctx().getExternalCacheDir() == null
                ? new File(ctx().getCacheDir().getParentFile(), "undo_outside.txt")
                : new File(ctx().getExternalCacheDir(), "undo_outside.txt");
        try (FileOutputStream out = new FileOutputStream(outside)) {
            out.write(new byte[]{9});
        }
        java.util.List<String> paths = new java.util.ArrayList<>();
        paths.add(outside.getAbsolutePath());
        paths.add(ctx().getFilesDir() + "/definitely-missing.png");
        BookCascade.deleteFiles(app, paths);
        assertTrue("filesDir 之外的文件不该被删", outside.exists());
        outside.delete();
    }
}
