package com.bai.bookshelf.ui.shelf;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.BorrowRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 书架的未归还借出记录（v5.6 从 ShelfFragment 抽出）：
 * 每个 bookId 只留一条（套装书以第一条为准），用于「借出中 / 超期」判断与智能书架。
 */
public class ShelfLoans {

    private final Map<Long, BorrowRecord> openByBook = new HashMap<>();

    /** 每本书当前在外的人名（去重、按记录顺序）——组合态文案用，v5.8.33 */
    private final Map<Long, List<String>> lendersByBook = new HashMap<>();

    public Map<Long, BorrowRecord> map() {
        return openByBook;
    }

    public Map<Long, List<String>> lenders() {
        return lendersByBook;
    }

    /** 全量替换（每本书只保留第一条未归还记录） */
    public void set(List<BorrowRecord> open) {
        openByBook.clear();
        lendersByBook.clear();
        if (open == null) {
            return;
        }
        for (BorrowRecord r : open) {
            if (!openByBook.containsKey(r.bookId)) {
                openByBook.put(r.bookId, r);
            }
            String who = r.borrower == null ? "" : r.borrower.trim();
            if (who.isEmpty()) {
                continue;
            }
            List<String> names = lendersByBook.get(r.bookId);
            if (names == null) {
                names = new ArrayList<>();
                lendersByBook.put(r.bookId, names);
            }
            if (!names.contains(who)) {
                names.add(who);
            }
        }
    }

    /** 从数据库重新读取未归还记录（逐本借出不会改 books 表，Room 不会推送） */
    public void refreshAsync(BookshelfApp app, Runnable onLoaded) {
        app.io(() -> {
            List<BorrowRecord> open;
            try {
                open = app.db().borrowRecordDao().getAllOpen();
            } catch (Exception e) {
                open = new ArrayList<>();
            }
            final List<BorrowRecord> fOpen = open;
            app.ui(() -> {
                set(fOpen);
                onLoaded.run();
            });
        });
    }
}
