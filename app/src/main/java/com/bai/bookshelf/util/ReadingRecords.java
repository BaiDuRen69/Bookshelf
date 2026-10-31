package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 阅读记录（v5.7）：一本书可有多次阅读（第 1 次、第 2 次…），
 * 每次记录状态、开始/结束日期与单独评分；书籍上的阅读状态、日期与评分
 * 始终取「最后一次」记录作为汇总，供筛选、详情和统计使用。
 */
public final class ReadingRecords {

    /** 单次阅读记录（JSON 存在 books.readingRecords 里） */
    public static final class Record {
        public int seq;
        public int status = Book.READ_NONE;
        public String startDate = "";
        public String endDate = "";
        public double rating; // 0-5，0.5 步进

        public Record() {
        }

        public Record(int seq) {
            this.seq = seq;
        }
    }

    private static final Gson GSON = new Gson();
    private static final Type LIST_TYPE = new TypeToken<List<Record>>() {
    }.getType();

    private ReadingRecords() {
    }

    /** 解析 JSON；坏数据按空列表处理 */
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
                if (r.startDate == null) {
                    r.startDate = "";
                }
                if (r.endDate == null) {
                    r.endDate = "";
                }
            }
            list.sort((a, b) -> Integer.compare(a.seq, b.seq));
            return list;
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public static String toJson(List<Record> records) {
        List<Record> list = records == null ? Collections.emptyList() : records;
        return GSON.toJson(list, LIST_TYPE);
    }

    /**
     * 读取记录；老数据没有 JSON 时，用书上的阅读状态/日期/评分合成第 1 次记录。
     */
    public static List<Record> load(Book book) {
        List<Record> records = parse(book == null ? "" : book.readingRecords);
        if (!records.isEmpty() || book == null) {
            return records;
        }
        boolean hasLegacy = book.readStatus != Book.READ_NONE
                || !nz(book.readingStartDate).isEmpty()
                || !nz(book.readingFinishDate).isEmpty()
                || book.rating > 0;
        if (hasLegacy) {
            Record r = new Record(1);
            r.status = book.readStatus;
            r.startDate = nz(book.readingStartDate);
            r.endDate = nz(book.readingFinishDate);
            r.rating = book.ratingAvg > 0 ? book.ratingAvg : book.rating;
            records.add(r);
        }
        return records;
    }

    /** 用最后一次记录更新书籍汇总字段；没有记录 = 未读 */
    public static void syncTo(Book book, List<Record> records) {
        if (book == null) {
            return;
        }
        if (records == null || records.isEmpty()) {
            book.readStatus = Book.READ_NONE;
            book.readingStartDate = "";
            book.readingFinishDate = "";
            book.rating = 0;
            book.ratingAvg = 0;
            book.readingRecords = "";
            return;
        }
        Record last = records.get(records.size() - 1);
        for (Record r : records) {
            if (r.seq >= last.seq) {
                last = r;
            }
        }
        book.readStatus = last.status;
        book.readingStartDate = nz(last.startDate);
        book.readingFinishDate = nz(last.endDate);
        book.ratingAvg = averageRating(records);
        book.rating = (int) Math.round(book.ratingAvg);
        book.readingRecords = toJson(records);
    }

    /** 所有已评分记录的平均分（保留 1 位小数）；没有评分返回 0 */
    public static double averageRating(List<Record> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        double sum = 0;
        int count = 0;
        for (Record r : records) {
            if (r.rating > 0) {
                sum += Math.min(5, Math.max(0, r.rating));
                count++;
            }
        }
        return count == 0 ? 0 : Math.round(sum / count * 10) / 10.0;
    }

    /** 评分的展示文本：整数不带小数点（4），半星保留一位（4.5） */
    public static String formatRating(double rating) {
        double v = Math.round(rating * 10) / 10.0;
        if (v == Math.rint(v)) {
            return String.valueOf((int) v);
        }
        return String.format(java.util.Locale.ROOT, "%.1f", v);
    }

    /** 读取路径校正汇总评分：有记录且至少一条评过分时，按记录平均值刷新 */
    public static void refreshSummary(Book book) {
        if (book == null || book.readingRecords == null || book.readingRecords.isEmpty()) {
            return;
        }
        double avg = averageRating(parse(book.readingRecords));
        if (avg > 0) {
            book.ratingAvg = avg;
            book.rating = (int) Math.round(avg);
        }
    }

    /** 阅读天数（含首尾）；在读只有开始日期时算到今天，缺日期返回 0 */
    public static int days(Record r) {
        if (r == null) {
            return 0;
        }
        int start = DateKeys.parseKey(r.startDate);
        if (start == 0) {
            return 0;
        }
        int end = DateKeys.parseKey(r.endDate);
        if (end == 0) {
            if (r.status == Book.READING) {
                end = DateKeys.todayKey();
            } else {
                return 0;
            }
        }
        return Math.max(0, BorrowReminder.daysBetween(start, end) + 1);
    }

    /** 根据日期推断状态：有始有终=已读，只有开始=在读，都没有=未读 */
    public static int suggestedStatus(String startDate, String endDate) {
        boolean start = DateKeys.parseKey(startDate) > 0;
        boolean end = DateKeys.parseKey(endDate) > 0;
        if (start && end) {
            return Book.READ_DONE;
        }
        if (start) {
            return Book.READING;
        }
        return Book.READ_NONE;
    }

    /**
     * 快捷改状态（批量操作等）：不动历史记录，只改最后一次记录的状态并同步汇总字段。
     * 未读会清空全部记录；已读/弃读缺结束日期时补今天，在读清掉结束日期。
     */
    public static void applyStatus(Book book, int status, String today) {
        if (book == null) {
            return;
        }
        List<Record> records = load(book);
        if (status == Book.READ_NONE) {
            syncTo(book, new ArrayList<>());
            return;
        }
        Record last;
        if (records.isEmpty()) {
            last = new Record(1);
            records.add(last);
        } else {
            last = records.get(records.size() - 1);
        }
        last.status = status;
        last.startDate = nz(last.startDate);
        last.endDate = nz(last.endDate);
        if ((status == Book.READING || status == Book.READ_DONE) && last.startDate.isEmpty()) {
            last.startDate = today;
        }
        if (status == Book.READ_DONE || status == Book.READ_DROPPED) {
            if (last.endDate.isEmpty()) {
                last.endDate = today;
            }
        } else {
            last.endDate = "";
        }
        syncTo(book, records);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
