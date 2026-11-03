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
        /**
         * v5.8.31：本场读到/停在百分之几（0-100）。
         * -1 = 旧数据没这个字段，由 {@link #parse} 按「DONE 视为 100、其余视为 0」补齐。
         */
        public int progress = -1;

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
                // v5.8.31：旧 JSON 没有 progress 字段（Gson 留 -1）→ 已读补 100，其余按 0
                if (r.progress < 0) {
                    r.progress = r.status == Book.READ_DONE ? 100 : 0;
                } else if (r.progress > 100) {
                    r.progress = 100;
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

    /**
     * 汇总字段**从会话推导**（v5.8.31 第二步）。
     *
     * <p>旧实现是「取 seq 最大的那一场，把它的状态/日期原样抄到书上」，
     * 于是弃读也会写 readingFinishDate（applyStatus 给 DONE/DROPPED 都补 endDate），
     * 而弃读→再读→读完只有一条会话时，startDate 还停在第一次翻开日、时长跨掉空窗。
     * 现在按语义分层：状态看「有没有在进行 / 有没有完成过」，日期看对应那一类会话。
     */
    public static void syncTo(Book book, List<Record> records) {
        if (book == null) {
            return;
        }
        if (records == null || records.isEmpty()) {
            book.readStatus = Book.READ_NONE;
            book.readingStartDate = "";
            book.readingFinishDate = "";
            book.readingProgress = 0;
            book.rating = 0;
            book.ratingAvg = 0;
            book.readingRecords = "";
            return;
        }
        Record last = lastOf(records);
        Record ongoing = ongoingReading(records);
        Record done = lastDone(records);
        // 内存里直接构造（没经过 parse）的会话也补齐 progress，避免 -1 落进 JSON
        for (Record r : records) {
            r.progress = progressOf(r);
        }
        if (ongoing != null) {
            book.readStatus = Book.READING;
        } else if (done != null) {
            book.readStatus = Book.READ_DONE;
        } else if (last.status == Book.READ_DROPPED) {
            book.readStatus = Book.READ_DROPPED;
        } else {
            book.readStatus = Book.READ_NONE;
        }
        book.readingFinishDate = done == null ? "" : nz(done.endDate);
        book.readingStartDate = ongoing != null ? nz(ongoing.startDate)
                : done != null ? nz(done.startDate)
                : nz(last.startDate);
        if (ongoing != null) {
            book.readingProgress = ongoing.progress;
        } else if (book.readStatus == Book.READ_DONE) {
            book.readingProgress = 100;
        } else {
            book.readingProgress = last.progress;
        }
        book.ratingAvg = averageRatingOfDone(records);
        book.rating = (int) Math.round(book.ratingAvg);
        book.readingRecords = toJson(records);
    }

    /** 会话进度取值：缺字段（-1）时已读算 100、其余算 0，上限夹到 100 */
    public static int progressOf(Record r) {
        if (r == null) {
            return 0;
        }
        if (r.progress < 0) {
            return r.status == Book.READ_DONE ? 100 : 0;
        }
        return Math.min(100, r.progress);
    }

    /** seq 最大的一场（并列时取靠后的） */
    public static Record lastOf(List<Record> records) {
        Record last = records.get(records.size() - 1);
        for (Record r : records) {
            if (r.seq >= last.seq) {
                last = r;
            }
        }
        return last;
    }

    /** 进行中的那一场（status==READING 且没写结束日期）；没有返回 null */
    public static Record ongoingReading(List<Record> records) {
        Record found = null;
        if (records == null) {
            return null;
        }
        for (Record r : records) {
            if (r.status == Book.READING && nz(r.endDate).isEmpty()
                    && (found == null || r.seq >= found.seq)) {
                found = r;
            }
        }
        return found;
    }

    /** 最后一场已读（DONE）；没有返回 null */
    public static Record lastDone(List<Record> records) {
        Record found = null;
        if (records == null) {
            return null;
        }
        for (Record r : records) {
            if (r.status == Book.READ_DONE && (found == null || r.seq >= found.seq)) {
                found = r;
            }
        }
        return found;
    }

    /**
     * v5.8.31：汇总评分只平均**已读会话**的评分。
     * 弃读那一遍通常没打分、或打的是「读不下去」的分，混进平均值会把书分拉歪。
     */
    public static double averageRatingOfDone(List<Record> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        List<Record> done = new ArrayList<>();
        for (Record r : records) {
            if (r.status == Book.READ_DONE) {
                done.add(r);
            }
        }
        return averageRating(done);
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

    /**
     * v5.8.31：读取路径的内存自愈。把「书上的汇总」与「会话列表」对不上账的旧数据
     * 在内存里纠正一遍并返回修好的会话，**不直接写库**——真正落盘发生在调用方保存时
     * 由 {@link #syncTo} 写回。
     *
     * <p>两条规则：
     * ① 标着已读、会话里却没有任何已读会话 → 按书上的日期补一场已读；
     * ② 会话里没有任何已读、最后一场是弃读，但书上写着「读完日期」（旧 applyStatus
     *    给 DONE/DROPPED 都补 endDate 留下的）→ 把那个日期归到弃读会话的 endDate，
     *    汇总的 finishDate 清空。
     *
     * <p>注意与「先读完、后来又弃读一遍」的区分：那种数据里存在 DONE 会话，
     * 规则 ② 用「没有任何 DONE」做前置条件，因此不会误伤它（此时汇总状态按
     * {@link #syncTo} 的优先级仍是「已读」，finishDate 保留第一次读完的日期）。
     */
    public static List<Record> derive(Book book) {
        if (book == null) {
            return new ArrayList<>();
        }
        List<Record> records = load(book);
        if (records.isEmpty()) {
            return records;
        }
        Record last = lastOf(records);
        Record done = lastDone(records);
        boolean fixed = false;
        if (book.readStatus == Book.READ_DONE && done == null) {
            Record r = new Record(last.seq + 1);
            r.status = Book.READ_DONE;
            r.startDate = nz(book.readingStartDate).isEmpty()
                    ? nz(last.startDate) : book.readingStartDate;
            r.endDate = nz(book.readingFinishDate);
            r.progress = 100;
            r.rating = book.ratingAvg > 0 ? book.ratingAvg : book.rating;
            records.add(r);
            fixed = true;
        } else if (done == null && last.status == Book.READ_DROPPED
                && !nz(book.readingFinishDate).isEmpty()) {
            if (nz(last.endDate).isEmpty()) {
                last.endDate = book.readingFinishDate;
            }
            book.readingFinishDate = "";
            fixed = true;
        }
        if (fixed) {
            syncTo(book, records);
        }
        return records;
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
     * 快捷改状态：不动历史记录，只改最后一次记录的状态并同步汇总字段。
     * 未读会清空全部记录；已读/弃读缺结束日期时补今天，在读清掉结束日期。
     *
     * <p><b>语义（v5.8.31 起）：{@code @deprecated} 级别——它不产生新会话，</b>
     * 弃读→再读→读完会被压成一条、时长跨掉空窗。业务入口一律走
     * {@link ReadingFlow}（start / finish / drop / restart / undoToNone），
     * 这里只留作低层兜底与既有单测的被测对象。
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
