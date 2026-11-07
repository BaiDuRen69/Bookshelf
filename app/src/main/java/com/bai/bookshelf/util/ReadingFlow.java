package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;

import java.util.List;

/**
 * 阅读会话的生命周期动作（v5.8.31）。
 *
 * <p>一本书的每一次翻阅都是 {@link ReadingRecords.Record} 里独立的一场：
 * 开始 / 读完 / 弃读 / 再读 / 撤销，各自只**新增**或**收尾**一场，
 * 绝不就地改写别场会话的状态——旧 {@code ReadingRecords.applyStatus} 把
 * 弃读→再读→读完压成一条，导致开始日停在第一次翻开、时长跨掉弃读空窗。
 *
 * <p>五个动作末尾统一调 {@link ReadingRecords#syncTo} 由会话推导汇总字段，
 * 并置 {@code book.dirty} / {@code book.updatedAt}；调用方负责写库。
 * 返回值 false 表示这次动作没产生任何变化（幂等），调用方应当提示而不是静默落库。
 */
public final class ReadingFlow {

    private ReadingFlow() {
    }

    /** 已经在读则返回 false；否则新增一场 READING（进度从 0 起），默认精确口径 */
    public static boolean start(Book book, String today) {
        return start(book, today, ReadingRecords.MODE_PRECISE);
    }

    /** 指定口径的开始阅读（v5.9.1 修订第五步）；旧签名委托为 PRECISE，避免遗漏调用点 */
    public static boolean start(Book book, String today, int mode) {
        if (book == null) {
            return false;
        }
        List<ReadingRecords.Record> records = ReadingRecords.derive(book);
        if (ReadingRecords.ongoingReading(records) != null) {
            return false;
        }
        ReadingRecords.Record r = newRecord(records, today, mode);
        r.status = Book.READING;
        r.progress = 0;
        records.add(r);
        commit(book, records);
        return true;
    }

    /**
     * 读完：有进行中的会话就把它收尾（DONE + 今天 + 100%）；
     * 没有则新增一场 DONE（开始日 = 今天，不动任何旧会话）。
     */
    public static boolean finish(Book book, String today) {
        if (book == null) {
            return false;
        }
        List<ReadingRecords.Record> records = ReadingRecords.derive(book);
        ReadingRecords.Record ongoing = ReadingRecords.ongoingReading(records);
        if (ongoing != null) {
            ongoing.status = Book.READ_DONE;
            ongoing.endDate = nz(today);
            ongoing.progress = 100;
            if (ongoing.startDate.isEmpty()) {
                ongoing.startDate = nz(today);
            }
        } else {
            ReadingRecords.Record r = newRecord(records, today);
            r.status = Book.READ_DONE;
            r.endDate = nz(today);
            r.progress = 100;
            records.add(r);
        }
        commit(book, records);
        return true;
    }

    /**
     * 弃读：有进行中的会话就收尾成 DROPPED，进度**原样保留**（停在百分之几就是百分之几）；
     * 没有进行中会话则新增一场当天开始当天结束的 DROPPED。
     *
     * <p>弃读绝不写 {@code book.readingFinishDate}——那是「读完日期」，
     * 汇总字段由 {@link ReadingRecords#syncTo} 从已读会话推导。
     */
    public static boolean drop(Book book, String today) {
        if (book == null) {
            return false;
        }
        List<ReadingRecords.Record> records = ReadingRecords.derive(book);
        ReadingRecords.Record ongoing = ReadingRecords.ongoingReading(records);
        if (ongoing != null) {
            ongoing.status = Book.READ_DROPPED;
            ongoing.endDate = nz(today);
            if (ongoing.startDate.isEmpty()) {
                ongoing.startDate = nz(today);
            }
        } else {
            ReadingRecords.Record r = newRecord(records, today);
            r.status = Book.READ_DROPPED;
            r.endDate = nz(today);
            records.add(r);
        }
        commit(book, records);
        return true;
    }

    /** 再读：最后一场是弃读或已读时新开一场 READING；已经在读则返回 false */
    public static boolean restart(Book book, String today) {
        if (book == null) {
            return false;
        }
        List<ReadingRecords.Record> records = ReadingRecords.derive(book);
        if (ReadingRecords.ongoingReading(records) != null) {
            return false;
        }
        ReadingRecords.Record last = records.isEmpty()
                ? null : ReadingRecords.lastOf(records);
        boolean fromFinished = last != null
                && (last.status == Book.READ_DROPPED || last.status == Book.READ_DONE);
        if (records.isEmpty() && book.readStatus == Book.READ_NONE) {
            // 没读过的书「再读」等价于开始阅读
            return start(book, today);
        }
        if (!fromFinished) {
            return false;
        }
        ReadingRecords.Record r = newRecord(records, today);
        r.status = Book.READING;
        r.progress = 0;
        records.add(r);
        commit(book, records);
        return true;
    }

    /**
     * 撤销回上一态：删掉最后一场会话（而不是把它的 status 改成 0），
     * 会话空了则汇总字段一并清零。没有会话可删时返回 false。
     */
    public static boolean undoToNone(Book book) {
        if (book == null) {
            return false;
        }
        List<ReadingRecords.Record> records = ReadingRecords.load(book);
        if (records.isEmpty()) {
            return false;
        }
        int lastSeq = ReadingRecords.lastOf(records).seq;
        for (int i = records.size() - 1; i >= 0; i--) {
            if (records.get(i).seq == lastSeq) {
                records.remove(i);
                break;
            }
        }
        commit(book, records);
        return true;
    }

    /**
     * 新会话取号：max(seq)+1。编辑页手工添加一场时也用它，
     * 免得两边各写一套规则（旧实现按 List 下标 +1，删过中间一场就会与这里错位）。
     */
    public static int nextSeq(List<ReadingRecords.Record> records) {
        int maxSeq = 0;
        if (records != null) {
            for (ReadingRecords.Record r : records) {
                maxSeq = Math.max(maxSeq, r.seq);
            }
        }
        return maxSeq + 1;
    }

    /** 新会话：seq 取最大值 +1，开始日 = 今天，结束日与评分留空；默认精确 */
    private static ReadingRecords.Record newRecord(List<ReadingRecords.Record> records,
                                                   String today) {
        return newRecord(records, today, ReadingRecords.MODE_PRECISE);
    }

    /**
     * 新会话：seq 取最大值 +1，开始日 = 今天，结束日与评分留空。
     *
     * <p>v5.9.1 起活入口（开始/读完/弃读/再读）默认**精确**口径：往后的日子靠打卡记，
     * 起止由日集合派生。开始日先落在今天作为锚点，第一次打卡后 deriveRange 会把它
     * 改成最早那天；历史会话与 derive/load 合成的老记录不走这里，仍是模糊。
     */
    private static ReadingRecords.Record newRecord(List<ReadingRecords.Record> records,
                                                   String today, int mode) {
        ReadingRecords.Record r = new ReadingRecords.Record(nextSeq(records));
        r.startDate = nz(today);
        r.mode = mode;
        return r;
    }

    private static void commit(Book book, List<ReadingRecords.Record> records) {
        ReadingRecords.syncTo(book, records);
        book.updatedAt = System.currentTimeMillis();
        book.dirty = true;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
