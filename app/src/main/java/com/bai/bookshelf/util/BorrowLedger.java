package com.bai.bookshelf.util;

import android.content.Context;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.ui.widget.ReadingWidgetProvider;

import java.util.ArrayList;
import java.util.List;

/**
 * 借阅台账的唯一写入口（v5.8.32）。
 *
 * <p>之前「删一条借出记录」在五处各写一遍，删得彻不彻底全看运气：详情页与批量删除会清
 * note_images 行但用 `new File(path).delete()` 裸删文件，书架批量借出的撤销回调压根不清图，
 * 库存状态有的按剩余记录重算（{@code syncBookStock}）、有的还原快照。这里把破坏性动作收敛成
 * 一个核心 + 两个语义入口，各处只负责自己的交互（弹窗、Toast、撤销条）。
 *
 * <p>约定：<b>必须在 io 线程调用</b>（调用方保持原有的 {@code app.io(...)} 包裹），
 * 通知与小组件刷新自带异步，不在这里做主线程切换。
 */
public final class BorrowLedger {

    /** 一次删除/归还的结果，供调用方决定要不要提示与刷新 */
    public static final class Result {
        public final boolean changed;
        public final long bookId;
        public final int imagesRemoved;

        Result(boolean changed, long bookId, int imagesRemoved) {
            this.changed = changed;
            this.bookId = bookId;
            this.imagesRemoved = imagesRemoved;
        }

        static final Result NOOP = new Result(false, 0, 0);
    }

    private BorrowLedger() {
    }

    /** 记录的图片 ownerKey 前缀（与备注图片区分开的唯一约定） */
    public static String ownerKey(long recordId) {
        return "borrow:" + recordId;
    }

    /**
     * 唯一破坏性核心：删该记录的 note_images 行 + 文件，再删 borrow_records 行。
     * 文件走 {@link BookCascade#deleteFiles} —— 它会校验路径落在应用文件目录内，
     * 比原先三处的 {@code new File(path).delete()} 裸删安全。
     *
     * @return 删掉的图片数
     */
    public static int deleteRecordWithImages(BookshelfApp app, long recordId) {
        String key = ownerKey(recordId);
        List<NoteImage> imgs = app.db().noteImageDao().getByOwnerKey(key);
        List<String> files = new ArrayList<>();
        for (NoteImage img : imgs) {
            if (img.path != null && !img.path.isEmpty()) {
                files.add(img.path);
            }
        }
        app.db().noteImageDao().deleteByOwnerKey(key);
        app.db().borrowRecordDao().deleteById(recordId);
        BookCascade.deleteFiles(app, files);
        return files.size();
    }

    /**
     * 纯判定：这一本该是在库还是借出。遗失是人工状态，任何回写都不覆盖它
     * （约束：借阅既有业务规则不变）。抽出来是为了能被单测直接打到。
     */
    public static int wantStock(int current, boolean hasOpenRecord) {
        if (current == Book.STOCK_LOST) {
            return current;
        }
        return hasOpenRecord ? Book.STOCK_LENT : Book.STOCK_IN;
    }

    /**
     * 按现存「未归还」记录回退库存状态（v5.8.32 从 BorrowHistoryActivity.syncBookStock 平移，
     * 逻辑一字未改）：第 1 本写在书籍主记录，第 2 本起写在 book_copies；
     * 仍有未归还记录的本 → 借出，否则 → 在库；<b>用户手动标了遗失的本不被覆盖</b>。
     */
    public static void syncStockFromRecords(BookshelfApp app, long bookId) {
        Book book = BookSanitizer.normalize(app.db().bookDao().getById(bookId));
        if (book == null) {
            return;
        }
        java.util.Set<Integer> openCopies = new java.util.HashSet<>();
        for (BorrowRecord r : app.db().borrowRecordDao().findOpenListByBook(bookId)) {
            openCopies.add(r.copyIndex);
        }
        int want1 = wantStock(book.stockStatus, openCopies.contains(1));
        if (book.stockStatus != want1) {
            book.stockStatus = want1;
            book.updatedAt = System.currentTimeMillis();
            app.db().bookDao().update(book);
        }
        for (BookCopy c : app.db().bookCopyDao().getByBook(bookId)) {
            int want = wantStock(c.stockStatus, openCopies.contains(c.copyIndex));
            if (c.stockStatus != want) {
                c.stockStatus = want;
                app.db().bookCopyDao().update(c);
            }
        }
    }

    /** 删除一条借出记录：图 + 行 + 库存回退 + 提醒重排 + 通知按情况收掉 + 小组件 */
    public static Result deleteOpen(Context ctx, long recordId) {
        BookshelfApp app = BookshelfApp.get();
        if (app == null) {
            return Result.NOOP;
        }
        BorrowRecord record = app.db().borrowRecordDao().getById(recordId);
        if (record == null) {
            return Result.NOOP;
        }
        long bookId = record.bookId;
        int images = deleteRecordWithImages(app, recordId);
        syncStockFromRecords(app, bookId);
        afterChange(ctx);
        return new Result(true, bookId, images);
    }

    /** 归还一条借出记录：写状态与归还日 + 库存回退 + 提醒/通知/小组件 */
    public static Result returnOpen(Context ctx, long recordId) {
        BookshelfApp app = BookshelfApp.get();
        if (app == null) {
            return Result.NOOP;
        }
        BorrowRecord record = app.db().borrowRecordDao().getById(recordId);
        if (record == null || record.status != BorrowRecord.STATUS_OUT) {
            return Result.NOOP;
        }
        markReturned(app, record);
        syncStockFromRecords(app, record.bookId);
        afterChange(ctx);
        return new Result(true, record.bookId, 0);
    }

    /** 纯写状态（批量路径自己收尾库存与提醒时用，避免每条记录都刷一遍通知与小组件） */
    public static void markReturned(BookshelfApp app, BorrowRecord record) {
        record.status = BorrowRecord.STATUS_RETURNED;
        record.returnDate = StatusText.today();
        app.db().borrowRecordDao().update(record);
    }

    /**
     * 借入记录（编辑页那份 JSON）删除：只同步 JSON 与书籍上的 flat 字段，
     * <b>不碰 borrow_records 表</b>——借入与借出是两套数据，删一边不该动另一边。
     *
     * @param records 编辑页正在编辑的活列表（不是从库里重新解析的）
     */
    public static boolean deleteBorrowedIn(Book book,
                                           List<BorrowSourceRecords.Record> records, int seq) {
        if (book == null || records == null) {
            return false;
        }
        boolean removed = false;
        for (int i = records.size() - 1; i >= 0; i--) {
            if (records.get(i).seq == seq) {
                records.remove(i);
                removed = true;
            }
        }
        if (!removed) {
            return false;
        }
        for (int i = 0; i < records.size(); i++) {
            records.get(i).seq = i + 1;
        }
        BorrowSourceRecords.syncTo(book, records);
        return true;
    }

    /** 数据变化后的统一收尾：重排提醒、按情况收通知、刷小组件 */
    public static void afterChange(Context ctx) {
        BorrowReminder.refresh(ctx);
        BorrowReminder.cancelIfClear(ctx);
        ReadingWidgetProvider.refresh(ctx);
    }

    // ==================== 借阅孤儿的一次性修复（v5.8.32） ====================

    /** 修复结果：既给文案，也留着改之前的快照供撤销条还原 */
    public static final class OrphanFix {
        public int booksUnlent;
        public int copiesUnlent;
        public int copiesCreated;
        public int imagesRemoved;
        public int booksResynced;

        final List<Book> beforeBooks = new ArrayList<>();
        final List<BookCopy> beforeCopies = new ArrayList<>();
        final List<Long> createdCopyIds = new ArrayList<>();
        final List<String> removedImageFiles = new ArrayList<>();

        public int total() {
            return booksUnlent + copiesUnlent + copiesCreated + imagesRemoved + booksResynced;
        }
    }

    /** 修复前的清单文案（确认弹窗里逐条列出将被改动的内容） */
    public static String describePlan(StatsHealth.BorrowOrphans o) {
        StringBuilder sb = new StringBuilder();
        int n = o.lendBooks.size() + o.lendCopies.size();
        if (n > 0) {
            sb.append("· ").append(n).append(" 处「借出」状态改回「在库」（遗失的不动）\n");
        }
        if (!o.orphanRecords.isEmpty()) {
            sb.append("· ").append(o.orphanRecords.size())
                    .append(" 条未归还记录缺副本行，补建这些副本\n");
        }
        if (!o.orphanImages.isEmpty()) {
            sb.append("· ").append(o.orphanImages.size())
                    .append(" 张挂在已删除记录上的备注图，连同文件一起删除（不可恢复）\n");
        }
        if (!o.outOfSyncBooks.isEmpty()) {
            sb.append("· ").append(o.outOfSyncBooks.size())
                    .append(" 本书的借入汇总字段按记录 JSON 重算\n");
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }
        return sb.toString();
    }

    /**
     * 执行修复。必须在 io 线程调用。
     * 图片是「记录已经没了」的孤儿文件，删掉不影响任何在册数据，因此不进撤销范围。
     */
    public static OrphanFix repairOrphans(BookshelfApp app, StatsHealth.BorrowOrphans o) {
        OrphanFix fix = new OrphanFix();
        long now = System.currentTimeMillis();
        for (Book book : o.lendBooks) {
            Book before = BookSnapshot.copy(book);
            book.stockStatus = Book.STOCK_IN;
            book.updatedAt = now;
            book.dirty = true;
            app.db().bookDao().update(book);
            if (before != null) {
                fix.beforeBooks.add(before);
            }
            fix.booksUnlent++;
        }
        for (Book book : o.outOfSyncBooks) {
            Book before = BookSnapshot.copy(book);
            BorrowSourceRecords.syncTo(book,
                    BorrowSourceRecords.parse(book.borrowRecords));
            book.updatedAt = now;
            book.dirty = true;
            app.db().bookDao().update(book);
            if (before != null) {
                fix.beforeBooks.add(before);
            }
            fix.booksResynced++;
        }
        for (BookCopy c : o.lendCopies) {
            BookCopy before = copyOf(c);
            c.stockStatus = Book.STOCK_IN;
            app.db().bookCopyDao().update(c);
            fix.beforeCopies.add(before);
            fix.copiesUnlent++;
        }
        for (BorrowRecord r : o.orphanRecords) {
            BookCopy c = new BookCopy();
            c.bookId = r.bookId;
            c.copyIndex = r.copyIndex;
            c.stockStatus = Book.STOCK_LENT;
            c.ownType = Book.OWN_BUY;
            long id = app.db().bookCopyDao().insert(c);
            if (id > 0) {
                fix.createdCopyIds.add(id);
                fix.copiesCreated++;
            }
        }
        for (com.bai.bookshelf.data.entity.NoteImage img : o.orphanImages) {
            if (img.path != null && !img.path.isEmpty()) {
                fix.removedImageFiles.add(img.path);
            }
            app.db().noteImageDao().deleteById(img.id);
            fix.imagesRemoved++;
        }
        if (!fix.removedImageFiles.isEmpty()) {
            BookCascade.deleteFiles(app, fix.removedImageFiles);
        }
        return fix;
    }

    /** 撤销：状态与借入汇总按快照还原、补建的副本行删掉 */
    public static void undoOrphanFix(BookshelfApp app, OrphanFix fix) {
        for (Book old : fix.beforeBooks) {
            old.updatedAt = System.currentTimeMillis();
            app.db().bookDao().update(old);
        }
        for (BookCopy old : fix.beforeCopies) {
            app.db().bookCopyDao().update(old);
        }
        for (Long id : fix.createdCopyIds) {
            app.db().bookCopyDao().deleteById(id);
        }
    }

    private static BookCopy copyOf(BookCopy c) {
        BookCopy x = new BookCopy();
        x.id = c.id;
        x.bookId = c.bookId;
        x.copyIndex = c.copyIndex;
        x.stockStatus = c.stockStatus;
        x.locationId = c.locationId;
        x.ownType = c.ownType;
        x.sourceChannel = c.sourceChannel;
        x.purchaseDate = c.purchaseDate;
        x.purchasePrice = c.purchasePrice;
        x.note = c.note;
        return x;
    }
}
