package com.bai.bookshelf.ui.stats;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.util.DateKeys;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 在借清单的一行（v5.9.0 从 StatsFragment 抽出）：一条未归还记录对应一本书，
 * 逾期与今日到期都拿应还日期和当天比较。纯取数与纯计算，不碰 View。
 */
public class OpenLoan {
    final BorrowRecord record;
    final Book book;
    final String title;
    final String borrower;
    final int dueKey;

    OpenLoan(BorrowRecord r, Book book) {
        this.record = r;
        this.book = book;
        this.title = book == null || book.title.isEmpty() ? "（未命名）" : book.title;
        this.borrower = r.borrower == null || r.borrower.isEmpty() ? "未填借阅人" : r.borrower;
        this.dueKey = DateKeys.parseKey(r.dueDate);
    }

    /** 未归还记录配上书目信息成清单行；逾期的排最前，其余按应还日期 */
    public static List<OpenLoan> build(List<Book> books, List<BorrowRecord> records, int todayKey) {
        Map<Long, Book> byId = new HashMap<>();
        for (Book bk : books) {
            byId.put(bk.id, bk);
        }
        List<OpenLoan> out = new ArrayList<>();
        for (BorrowRecord r : records) {
            if (r.status == BorrowRecord.STATUS_OUT) {
                out.add(new OpenLoan(r, byId.get(r.bookId)));
            }
        }
        out.sort((a, b) -> {
            boolean ao = a.dueKey > 0 && a.dueKey < todayKey;
            boolean bo = b.dueKey > 0 && b.dueKey < todayKey;
            if (ao != bo) {
                return ao ? -1 : 1;
            }
            return Integer.compare(a.dueKey, b.dueKey);
        });
        return out;
    }

    /** 日键 → 「M月D日」，清单里比 ISO 全串好读 */
    static String monthDay(int yyyyMMdd) {
        return (yyyyMMdd / 100 % 100) + "月" + (yyyyMMdd % 100) + "日";
    }
}
