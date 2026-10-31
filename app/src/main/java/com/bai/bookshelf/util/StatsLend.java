package com.bai.bookshelf.util;

import com.bai.bookshelf.util.StatsHoldings.Group;
import com.bai.bookshelf.util.StatsHoldings.KeyOf;

import static com.bai.bookshelf.util.LibraryStats.MIN_SAMPLE;
import static com.bai.bookshelf.util.LibraryStats.copiesOf;
import static com.bai.bookshelf.util.LibraryStats.copyPaid;
import static com.bai.bookshelf.util.LibraryStats.copyStatuses;
import static com.bai.bookshelf.util.LibraryStats.countOwnedCopies;
import static com.bai.bookshelf.util.LibraryStats.enough;
import static com.bai.bookshelf.util.LibraryStats.groupCopies;
import static com.bai.bookshelf.util.LibraryStats.isPartialPeriod;
import static com.bai.bookshelf.util.LibraryStats.median;
import static com.bai.bookshelf.util.LibraryStats.pearson;
import static com.bai.bookshelf.util.LibraryStats.samePeriodLastYearKey;
import static com.bai.bookshelf.util.LibraryStats.toArray;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 借阅（v5.8.1 从 LibraryStats 拆出）：
 * 在途与逾期、按时归还率、借阅人可靠性、借出结构。
 */
public final class StatsLend {

    private StatsLend() {
    }

    /** 借出管理与按时归还率 */
    public static class Lend {
        public int out;
        public int overdue;
        public int returned;
        /** 按时归还率（只统计填了应还日期的归还）；无样本 -1 */
        public double onTimeRate;
        public int onTimeSample;
        /** 按时归还的笔数（onTimeRate 的分子） */
        public int onTimeCount;
        /** 在外书籍的平均已借天数；无在外的 -1 */
        public double avgDaysOut;
        /** 逾期未还的平均逾期天数；无逾期的 -1 */
        public double avgOverdueDays;
    }

    public static Lend lend(List<BorrowRecord> records, int todayKey) {
        Lend l = new Lend();
        if (records == null) {
            return l;
        }
        List<Double> outDays = new ArrayList<>();
        List<Double> overdueDays = new ArrayList<>();
        for (BorrowRecord r : records) {
            int due = DateKeys.parseKey(r.dueDate);
            int ret = DateKeys.parseKey(r.returnDate);
            int lend = DateKeys.parseKey(r.lendDate);
            if (r.status == BorrowRecord.STATUS_RETURNED) {
                l.returned++;
                if (due > 0 && ret > 0) {
                    l.onTimeSample++;
                    if (ret <= due) {
                        l.onTimeCount++;
                    }
                }
            } else {
                l.out++;
                if (due > 0 && due < todayKey) {
                    l.overdue++;
                    if (lend > 0) {
                        overdueDays.add((double) BorrowReminder.daysBetween(due, todayKey));
                    }
                }
                if (lend > 0) {
                    outDays.add((double) BorrowReminder.daysBetween(lend, todayKey) + 1);
                }
            }
        }
        l.onTimeRate = l.onTimeSample >= MIN_SAMPLE
                ? (double) l.onTimeCount / l.onTimeSample : -1;
        l.avgDaysOut = outDays.isEmpty() ? -1 : median(toArray(outDays));
        l.avgOverdueDays = overdueDays.isEmpty() ? -1 : median(toArray(overdueDays));
        return l;
    }

    /** 一个借阅人的账本 */
    public static class Lender {
        public String name;
        public int loans;
        public int overdue;
        public int outNow;
        /** 有应还日期且已归还的笔数（按时率样本） */
        public int returnedWithDue;
        public int onTime;

        Lender(String name) {
            this.name = name;
        }

        public double onTimeRate() {
            return returnedWithDue == 0 ? -1 : (double) onTime / returnedWithDue;
        }
    }

    /** 借出去的书集中在哪些分类 / 作者 */
    public static List<Group> lendStructure(List<Book> raw, List<BorrowRecord> records, KeyOf keyOf,
                                            String fallbackName) {
        java.util.Map<Long, Book> byId = new java.util.HashMap<>();
        for (Book book : BookSanitizer.normalizeAll(raw)) {
            byId.put(book.id, book);
        }
        java.util.Map<String, Group> groups = new java.util.LinkedHashMap<>();
        if (records == null) {
            return new ArrayList<>();
        }
        for (BorrowRecord r : records) {
            Book book = byId.get(r.bookId);
            if (book == null) {
                continue;
            }
            String key = keyOf.of(book);
            key = key == null ? "" : key.trim();
            if (key.isEmpty()) {
                key = fallbackName;
            }
            Group g = groups.get(key);
            if (g == null) {
                g = new Group(key);
                groups.put(key, g);
            }
            g.titles++;
            if (book.readStatus == Book.READ_DONE) {
                g.readDone++;
            }
        }
        List<Group> out = new ArrayList<>(groups.values());
        out.sort((x, y) -> y.titles != x.titles ? Integer.compare(y.titles, x.titles)
                : x.name.compareTo(y.name));
        return out;
    }

    /** 按借阅人聚合的可靠性账本，按借出笔数降序 */
    public static List<Lender> lenders(List<BorrowRecord> records, int todayKey) {
        java.util.Map<String, Lender> map = new java.util.LinkedHashMap<>();
        if (records == null) {
            return new ArrayList<>();
        }
        for (BorrowRecord r : records) {
            String name = r.borrower == null || r.borrower.trim().isEmpty()
                    ? "未填借阅人" : r.borrower.trim();
            Lender l = map.computeIfAbsent(name, Lender::new);
            l.loans++;
            int due = DateKeys.parseKey(r.dueDate);
            int ret = DateKeys.parseKey(r.returnDate);
            if (r.status == BorrowRecord.STATUS_RETURNED) {
                if (due > 0 && ret > 0) {
                    l.returnedWithDue++;
                    if (ret <= due) {
                        l.onTime++;
                    } else {
                        l.overdue++;
                    }
                }
            } else {
                l.outNow++;
                if (due > 0 && due < todayKey) {
                    l.overdue++;
                }
            }
        }
        List<Lender> out = new ArrayList<>(map.values());
        out.sort((x, y) -> y.loans != x.loans ? Integer.compare(y.loans, x.loans)
                : x.name.compareTo(y.name));
        return out;
    }
}
