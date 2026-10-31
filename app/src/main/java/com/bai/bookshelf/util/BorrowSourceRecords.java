package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 借阅来源的多次借书记录（v5.7）：每次借阅记一条来源 / 开始 / 应还 / 归还，
 * 书籍上的 borrowStartDate / borrowDueDate / borrowReturnDate 始终取最后一次记录，
 * 供到期提醒、筛选和详情摘要使用。
 */
public final class BorrowSourceRecords {

    /** 单次借书记录（JSON 存在 books.borrowRecords 里） */
    public static final class Record {
        public int seq;
        public String source = "";     // 从哪借的（图书馆 / 朋友 …）
        public int quantity = 1;       // 本次借了几本
        public String startDate = "";
        public String dueDate = "";
        public String returnDate = "";

        public Record() {
        }

        public Record(int seq) {
            this.seq = seq;
        }
    }

    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<Record>>() {
    }.getType();

    private BorrowSourceRecords() {
    }

    public static List<Record> parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return new ArrayList<>();
        }
        try {
            List<Record> list = GSON.fromJson(json, LIST_TYPE);
            if (list == null) {
                return new ArrayList<>();
            }
            for (Record r : list) {
                r.source = nz(r.source);
                r.startDate = nz(r.startDate);
                r.dueDate = nz(r.dueDate);
                r.returnDate = nz(r.returnDate);
                if (r.quantity <= 0) {
                    r.quantity = 1;
                }
            }
            list.sort((a, b) -> Integer.compare(a.seq, b.seq));
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static String toJson(List<Record> records) {
        return GSON.toJson(records == null ? Collections.emptyList() : records, LIST_TYPE);
    }

    /** 读取记录；老数据只有汇总字段时合成第 1 次记录 */
    public static List<Record> load(Book book) {
        List<Record> records = parse(book == null ? "" : book.borrowRecords);
        if (!records.isEmpty() || book == null) {
            return records;
        }
        boolean hasLegacy = !nz(book.borrowStartDate).isEmpty()
                || !nz(book.borrowDueDate).isEmpty()
                || !nz(book.borrowReturnDate).isEmpty();
        if (hasLegacy) {
            Record r = new Record(1);
            r.startDate = nz(book.borrowStartDate);
            r.dueDate = nz(book.borrowDueDate);
            r.returnDate = nz(book.borrowReturnDate);
            r.quantity = Math.max(1, book.quantity);
            records.add(r);
        }
        return records;
    }

    /**
     * 用最后一次记录同步汇总字段；已归还 → 电子书签，再次借阅 → 在库。
     * 借出/遗失是独立的人工状态，不被覆盖。
     */
    public static void syncTo(Book book, List<Record> records) {
        if (book == null) {
            return;
        }
        if (records == null || records.isEmpty()) {
            book.borrowStartDate = "";
            book.borrowDueDate = "";
            book.borrowReturnDate = "";
            book.borrowRecords = "";
            return;
        }
        Record last = records.get(records.size() - 1);
        for (Record r : records) {
            if (r.seq >= last.seq) {
                last = r;
            }
        }
        book.borrowStartDate = nz(last.startDate);
        book.borrowDueDate = nz(last.dueDate);
        book.borrowReturnDate = nz(last.returnDate);
        book.quantity = Math.max(1, last.quantity);
        book.borrowRecords = toJson(records);
        if (book.stockStatus == Book.STOCK_LENT || book.stockStatus == Book.STOCK_LOST) {
            return;
        }
        book.stockStatus = nz(last.returnDate).isEmpty()
                ? Book.STOCK_IN : Book.STOCK_BOOKMARK;
    }

    /** 读取路径校正汇总字段（不改藏书状态），保证提醒/详情与记录一致 */
    public static void refreshSummary(Book book) {
        if (book == null || book.ownType != Book.OWN_BORROW
                || book.borrowRecords == null || book.borrowRecords.isEmpty()) {
            return;
        }
        List<Record> records = parse(book.borrowRecords);
        if (records.isEmpty()) {
            return;
        }
        Record last = records.get(records.size() - 1);
        book.borrowStartDate = nz(last.startDate);
        book.borrowDueDate = nz(last.dueDate);
        book.borrowReturnDate = nz(last.returnDate);
        book.quantity = Math.max(1, last.quantity);
        // 记录里有来源但主字段丢了：回填，保证书架「来源」筛选能列出借阅渠道
        if (nz(book.sourceChannel).trim().isEmpty() && !nz(last.source).trim().isEmpty()) {
            book.sourceChannel = last.source.trim();
        }
    }

    /** 最新一次借阅记录；没有记录返回 null */
    public static Record latest(Book book) {
        List<Record> records = load(book);
        return records.isEmpty() ? null : records.get(records.size() - 1);
    }

    /** 把最后一次「未归还」的记录标记为已归还；成功返回归还时间，否则 null */
    public static String returnLatest(Book book, String returnTime) {
        List<Record> records = load(book);
        Record target = null;
        for (Record r : records) {
            if (nz(r.returnDate).isEmpty()) {
                target = r;
            }
        }
        if (target == null) {
            return null;
        }
        target.returnDate = returnTime;
        syncTo(book, records);
        return returnTime;
    }

    /** 是否有未归还的借阅 */
    public static boolean hasOpen(Book book) {
        for (Record r : load(book)) {
            if (nz(r.returnDate).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
