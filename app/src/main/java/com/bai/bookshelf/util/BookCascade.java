package com.bai.bookshelf.util;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.NoteImage;

import java.io.File;
import java.util.List;

/**
 * 删书的集中式级联清理（v4.23）。
 *
 * <p>审查 #4/#5 修掉的缺口：原先详情页删除与书架批量删除各自手写级联，
 * 都漏了借出记录（永久孤儿）与本地封面文件（永不回收）。本类把
 * 「删一本书要清什么」收敛到一处，两边共用，后续加新关联表只改这里。
 *
 * <p>清理范围：
 * <ol>
 *   <li>备注图片（书级 book:{id} / 逐本 copy:{id}:{n} / 借出 lend:{recordId}）
 *       —— 记录 + 磁盘文件；</li>
 *   <li>逐本信息 book_copies；</li>
 *   <li>借出记录 borrow_records；</li>
 *   <li>本地封面文件（仅限 filesDir/covers/ 下的，防误删任意路径）；</li>
 *   <li>书籍主记录。</li>
 * </ol>
 *
 * <p><b>必须在 io 线程调用</b>（内部做磁盘 IO 与数据库写）。
 */
public final class BookCascade {

    private BookCascade() {
    }

    /** 删除一本书及其全部关联数据。返回删除的书籍 id（透传入参，便于调用方日志）。 */
    public static long deleteBook(BookshelfApp app, long bookId, String coverUrl) {
        // 1) 备注图片：先取路径删文件，再删记录（getByBook 含书级/逐本/借出图）
        List<NoteImage> imgs = app.db().noteImageDao().getByBook(bookId);
        app.db().noteImageDao().deleteByBook(bookId);
        for (NoteImage img : imgs) {
            if (img.path != null && !img.path.isEmpty()) {
                new File(img.path).delete();
            }
        }

        // 2) 摘抄：记录 + 摘抄图片文件（v4.50）
        List<com.bai.bookshelf.data.entity.Excerpt> excerpts =
                app.db().excerptDao().getByBook(bookId);
        app.db().excerptDao().deleteByBook(bookId);
        for (com.bai.bookshelf.data.entity.Excerpt e : excerpts) {
            ExcerptFiles.deleteQuietly(app, e.imagePath);
        }

        // 3) 逐本信息
        app.db().bookCopyDao().deleteByBook(bookId);

        // 4) 借出记录（v4.23 补：原先漏删，变永久孤儿并被 zip 备份带走）
        app.db().borrowRecordDao().deleteByBook(bookId);

        // 5) 本地封面文件（v4.23 补）：只删 filesDir/covers/ 下自己的文件，
        //    网络 URL 与用户手填的任意路径一律不动
        deleteLocalCover(app, coverUrl);

        // 6) 书籍主记录
        app.db().bookDao().deleteById(bookId);
        return bookId;
    }

    /** 删除本地封面文件（仅限应用私有 covers/ 目录内的） */
    public static void deleteLocalCover(BookshelfApp app, String coverUrl) {
        if (coverUrl == null || coverUrl.isEmpty() || coverUrl.startsWith("http")) {
            return;
        }
        File coversDir = new File(app.getFilesDir(), "covers");
        File f = new File(coverUrl);
        try {
            // 规范路径后确认确在 covers/ 目录下，防止 coverUrl 被手填成任意路径
            if (f.exists()
                    && f.getCanonicalPath().startsWith(coversDir.getCanonicalPath() + File.separator)) {
                f.delete();
            }
        } catch (Exception ignored) {
            // 路径异常（如含非法字符）时宁可留着文件也不误删
        }
    }
}
