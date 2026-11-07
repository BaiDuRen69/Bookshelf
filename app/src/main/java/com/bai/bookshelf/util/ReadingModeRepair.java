package com.bai.bookshelf.util;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.CheckIn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 精确记录体检的「执行」与「撤销」（v5.9.1 第六步）。
 *
 * <p>只改两样东西：书的 readingRecords JSON（模式/派生起止，随后 syncTo 回写汇总字段）
 * 和 check_ins 里跨轮重复的那几行。撤销按<b>逐字段快照</b>写回：
 * 书整行还原、被删的打卡行连 createdAt/backfill/pages/seq 一起放回去。
 *
 * <p>拿 {@link AppDatabase} 而不是 Context，仪器测试可以直接喂内存库。
 */
public final class ReadingModeRepair {

    private ReadingModeRepair() {
    }

    /** 改动前的快照，撤销条 8 秒内用 */
    public static final class Undo {
        final List<Book> booksBefore = new ArrayList<>();
        final List<CheckIn> deleted = new ArrayList<>();

        public int count() {
            return booksBefore.size() + deleted.size();
        }
    }

    public static Undo apply(AppDatabase db, ReadingModeHealth.Report report) {
        Undo undo = new Undo();
        if (db == null || report == null) {
            return undo;
        }
        Map<Long, List<ReadingModeHealth.Issue>> byBook = new LinkedHashMap<>();
        for (ReadingModeHealth.Issue issue : report.issues) {
            if (issue.kind == ReadingModeHealth.KIND_CROSS_ROUND) {
                db.checkInDao().delete(issue.row);
                undo.deleted.add(issue.row);
            } else {
                List<ReadingModeHealth.Issue> list = byBook.get(issue.bookId);
                if (list == null) {
                    list = new ArrayList<>();
                    byBook.put(issue.bookId, list);
                }
                list.add(issue);
            }
        }
        for (Map.Entry<Long, List<ReadingModeHealth.Issue>> e : byBook.entrySet()) {
            Book original = db.bookDao().getById(e.getKey());
            Book fresh = db.bookDao().getById(e.getKey());
            if (original == null || fresh == null) {
                continue;
            }
            List<ReadingRecords.Record> records = ReadingRecords.load(fresh);
            boolean changed = false;
            for (ReadingModeHealth.Issue issue : e.getValue()) {
                ReadingRecords.Record r = recordOf(records, issue.seq);
                if (r == null) {
                    continue;
                }
                if (issue.kind == ReadingModeHealth.KIND_NO_DAYS) {
                    r.mode = ReadingRecords.MODE_FUZZY; // 0 天不再冒充精确，起止原样保留
                    changed = true;
                } else {
                    List<Integer> keys = db.checkInDao().getDayKeys(issue.bookId, issue.seq);
                    if (ReadDays.precise(keys) == 0) {
                        continue; // 这段时间日子被删了：留给「0 天」那项处理
                    }
                    ReadingRecords.deriveRange(r, keys);
                    changed = true;
                }
            }
            if (!changed) {
                continue;
            }
            ReadingRecords.syncTo(fresh, records);
            db.bookDao().update(fresh);
            undo.booksBefore.add(original);
        }
        return undo;
    }

    public static void undo(AppDatabase db, Undo undo) {
        if (db == null || undo == null) {
            return;
        }
        for (Book book : undo.booksBefore) {
            db.bookDao().update(book);
        }
        if (!undo.deleted.isEmpty()) {
            db.checkInDao().insertAll(undo.deleted);
        }
    }

    private static ReadingRecords.Record recordOf(List<ReadingRecords.Record> records, int seq) {
        for (ReadingRecords.Record r : records) {
            if (r != null && r.seq == seq) {
                return r;
            }
        }
        return null;
    }
}
