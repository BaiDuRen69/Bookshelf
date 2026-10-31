package com.bai.bookshelf.util;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.data.entity.NoteImage;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 删书的集中式级联清理（v4.23）。
 *
 * <p>审查 #4/#5 修掉的缺口：原先详情页删除与书架批量删除各自手写级联，
 * 都漏了借出记录（永久孤儿）与本地封面文件（永不回收）。本类把
 * 「删一本书要清什么」收敛到一处，两边共用，后续加新关联表只改这里。
 *
 * <p>清理范围：备注图片（书级 / 逐本 / 借出）、摘抄与摘抄图片、逐本信息、
 * 借出记录、本地封面文件（仅限 filesDir/covers/）、书籍主记录。
 *
 * <p>v5.8.16：删除改成可撤销——{@link #deleteRecoverable} 把删掉的行留在快照里、
 * 只登记待删文件不立刻删；撤销用 {@link #restore} 按原主键写回，
 * 撤销窗口结束后由 {@link #deleteFiles} 真正回收文件。
 *
 * <p><b>必须在 io 线程调用</b>（内部做磁盘 IO 与数据库写）。
 */
public final class BookCascade {

    private BookCascade() {
    }

    /** 一本书被带走的全部行（撤销时按原主键写回） */
    public static final class Deleted {
        public Book book;
        public final List<BookCopy> copies = new ArrayList<>();
        public final List<BorrowRecord> records = new ArrayList<>();
        public final List<NoteImage> images = new ArrayList<>();
        public final List<Excerpt> excerpts = new ArrayList<>();
        /** 待删文件：撤销窗口结束后再删，避免恢复回来的书丢封面/图片 */
        public final List<String> files = new ArrayList<>();
    }

    /** 删除一本书及其关联数据，并返回可撤销的快照（文件只登记、不删） */
    public static Deleted deleteRecoverable(BookshelfApp app, long bookId) {
        Deleted d = new Deleted();
        d.book = app.db().bookDao().getById(bookId);
        d.images.addAll(app.db().noteImageDao().getByBook(bookId));
        d.excerpts.addAll(app.db().excerptDao().getByBook(bookId));
        d.copies.addAll(app.db().bookCopyDao().getByBook(bookId));
        d.records.addAll(app.db().borrowRecordDao().getByBook(bookId));

        app.db().noteImageDao().deleteByBook(bookId);
        app.db().excerptDao().deleteByBook(bookId);
        app.db().bookCopyDao().deleteByBook(bookId);
        app.db().borrowRecordDao().deleteByBook(bookId);
        app.db().bookDao().deleteById(bookId);

        for (NoteImage img : d.images) {
            if (img.path != null && !img.path.isEmpty()) {
                d.files.add(img.path);
            }
        }
        for (Excerpt e : d.excerpts) {
            if (e.imagePath != null && !e.imagePath.isEmpty()) {
                d.files.add(e.imagePath);
            }
        }
        if (d.book != null) {
            collectLocalCover(app, d.book.coverUrl, d.files);
        }
        return d;
    }

    /** 撤销删除：按原主键把行写回（文件还没删，所以能原样恢复） */
    public static void restore(BookshelfApp app, Deleted d) {
        if (d == null) {
            return;
        }
        if (d.book != null) {
            app.db().bookDao().insert(d.book);
        }
        if (!d.copies.isEmpty()) {
            app.db().bookCopyDao().insertAll(d.copies);
        }
        for (BorrowRecord r : d.records) {
            app.db().borrowRecordDao().insert(r);
        }
        if (!d.images.isEmpty()) {
            app.db().noteImageDao().insertAll(d.images);
        }
        for (Excerpt e : d.excerpts) {
            app.db().excerptDao().insert(e);
        }
    }

    /** 撤销窗口结束后回收文件：只删应用私有目录内的路径 */
    public static void deleteFiles(BookshelfApp app, List<String> paths) {
        if (app == null || paths == null || paths.isEmpty()) {
            return;
        }
        try {
            String root = app.getFilesDir().getCanonicalPath() + File.separator;
            for (String path : paths) {
                if (path == null || path.isEmpty()) {
                    continue;
                }
                try {
                    File f = new File(path);
                    if (f.exists() && f.getCanonicalPath().startsWith(root)) {
                        f.delete();
                    }
                } catch (Exception ignored) {
                    // 单个文件删不掉不影响其它
                }
            }
        } catch (Exception ignored) {
            // 拿不到 filesDir 规范路径时宁可不删
        }
    }

    /** 本地封面（仅限 filesDir/covers/ 下自己的文件）登记为待删，网络 URL 与手填路径不动 */
    private static void collectLocalCover(BookshelfApp app, String coverUrl, List<String> out) {
        if (coverUrl == null || coverUrl.isEmpty() || coverUrl.startsWith("http")) {
            return;
        }
        File coversDir = new File(app.getFilesDir(), "covers");
        File f = new File(coverUrl);
        try {
            if (f.exists()
                    && f.getCanonicalPath().startsWith(coversDir.getCanonicalPath() + File.separator)) {
                out.add(f.getCanonicalPath());
            }
        } catch (Exception ignored) {
            // 路径异常（如含非法字符）时宁可留着文件也不误删
        }
    }
}
