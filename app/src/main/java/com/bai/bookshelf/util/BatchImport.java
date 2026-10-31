package com.bai.bookshelf.util;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;

import java.util.ArrayList;
import java.util.List;

/**
 * 批量录入的队列模型与入库（v5.8.16 从扫码批量录入抽出）：
 * 扫码页与「粘贴 ISBN 清单」共用同一套状态与保存规则，避免两处各写一遍默认值。
 *
 * <p>状态含义：0 查询中、1 可入库、2 书架已有、3 查不到。
 */
public final class BatchImport {

    public static final int QUERYING = 0;
    public static final int READY = 1;
    public static final int DUPLICATE = 2;
    public static final int FAILED = 3;

    /** 队列里的一条 */
    public static final class Item {
        public final String isbn;
        public int status = QUERYING;
        public Book book;
        public long existingId = -1;
        public String title = "";
        public String author = "";
        public String coverUrl = "";
        public String message = "";
        /** 预览里是否勾选入库（扫码页一直为 true） */
        public boolean included = true;

        public Item(String isbn) {
            this.isbn = isbn;
        }
    }

    private BatchImport() {
    }

    /** 可保存的种数：状态可入库且被勾选 */
    public static int saveableCount(List<Item> items) {
        int n = 0;
        if (items != null) {
            for (Item item : items) {
                if (item.status == READY && item.included) {
                    n++;
                }
            }
        }
        return n;
    }

    /** 会加副本的重复条目数（没勾选加副本时不计） */
    public static int copyCount(List<Item> items, boolean addCopies) {
        int n = 0;
        if (items == null || !addCopies) {
            return n;
        }
        for (Item item : items) {
            if (item.status == DUPLICATE) {
                n++;
            }
        }
        return n;
    }

    /**
     * 入库结果：种数/册数之外还记下主键，供「撤销这次批量录入」逐条回滚。
     * 副本只记被改过的书 id，撤销时按 copiedIds 回退一次数量。
     */
    public static final class Result {
        public int saved;
        public int copied;
        public final List<Long> savedIds = new ArrayList<>();
        public final List<Long> copiedIds = new ArrayList<>();
    }

    /**
     * 入库：可保存的按默认值写进 books，重复的（勾选时）各加一册。
     *
     * @return 入库结果；必须在 io 线程调用
     */
    public static Result save(BookshelfApp app, List<Item> items, long locationId,
                              boolean addCopies) {
        Result result = new Result();
        if (app == null || items == null) {
            return result;
        }
        long now = System.currentTimeMillis();
        for (Item item : items) {
            if (item.status == READY && item.included) {
                Book book = item.book;
                if (book == null || book.title == null || book.title.trim().isEmpty()) {
                    continue;
                }
                book.locationId = locationId > 0 ? locationId : null;
                book.ownType = Book.OWN_BUY;
                book.createdAt = now;
                book.updatedAt = now;
                book.dirty = true;
                // Room 不会把自增主键写回传入的对象，得自己接住
                long rowId = app.db().bookDao().insert(book);
                if (rowId > 0) {
                    book.id = rowId;
                    result.saved++;
                    result.savedIds.add(rowId);
                }
            } else if (addCopies && item.status == DUPLICATE && item.existingId > 0) {
                Book exist = app.db().bookDao().getById(item.existingId);
                if (exist != null) {
                    exist.quantity = exist.quantity + 1;
                    exist.updatedAt = now;
                    app.db().bookDao().update(exist);
                    result.copied++;
                    result.copiedIds.add(exist.id);
                }
            }
        }
        return result;
    }

    /**
     * 回滚一次批量录入（撤销条用）：删掉新建的书、把加过的副本各减一册。
     * 只在 io 线程调用；这些书刚建出来，还没有本地封面/图片文件，不必删文件。
     */
    public static void rollback(BookshelfApp app, Result result) {
        if (app == null || result == null) {
            return;
        }
        for (long id : result.savedIds) {
            app.db().bookDao().deleteById(id);
        }
        long now = System.currentTimeMillis();
        for (long id : result.copiedIds) {
            Book book = app.db().bookDao().getById(id);
            if (book == null) {
                continue;
            }
            book.quantity = Math.max(1, book.quantity - 1);
            book.updatedAt = now;
            app.db().bookDao().update(book);
        }
    }
}
