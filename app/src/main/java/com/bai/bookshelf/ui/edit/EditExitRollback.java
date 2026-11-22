package com.bai.bookshelf.ui.edit;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.CheckIn;

import java.util.ArrayList;
import java.util.List;

/**
 * 编辑页退出「不保存」时的整卷回滚快照（v5.12.1）。
 *
 * <p>编辑页的阅读记录区有两条<b>立即落库</b>路径——精确弹窗保存
 * （{@code ReadingRecordSection.savePrecise} 的 {@code applyDayKeys}）与
 * DayPickerSheet 面板「确定」——都会当场写 check_ins，还会经
 * {@code resyncPreciseRounds} 把派生起止与 readingRecords JSON 回写书籍行。
 * 这些写入不走整本书保存，退出选「不保存」时必须整卷退回进页时的样子，
 * 否则「没保存的编辑」实际上已经生效。
 *
 * <p>快照在进页时（DB 就绪后）抓一次：书籍行 + 这本书的全部打卡行。
 * 恢复是幂等的整卷覆盖（先删后插 + 书籍行回写），只该在「不保存」路径调用。
 */
public final class EditExitRollback {

    private final Book book;
    private final List<CheckIn> checkIns;

    private EditExitRollback(Book book, List<CheckIn> checkIns) {
        this.book = book;
        this.checkIns = checkIns;
    }

    /** 进页时调用（io 线程）；bookId&lt;=0（新增场景）返回 null，无需回滚 */
    public static EditExitRollback capture(AppDatabase db, long bookId) {
        if (db == null || bookId <= 0) {
            return null;
        }
        Book book = db.bookDao().getById(bookId);
        if (book == null) {
            return null;
        }
        return new EditExitRollback(book, new ArrayList<>(db.checkInDao().getByBook(bookId)));
    }

    /** 退出「不保存」时调用（io 线程）：整卷恢复进页时的书籍行与打卡行 */
    public void restore(AppDatabase db) {
        if (db == null || book == null) {
            return;
        }
        db.runInTransaction(() -> {
            db.checkInDao().deleteByBook(book.id);
            if (!checkIns.isEmpty()) {
                db.checkInDao().insertAll(checkIns);
            }
            db.bookDao().update(book);
        });
    }
}
