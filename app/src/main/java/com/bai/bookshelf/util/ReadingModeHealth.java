package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.CheckIn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 精确阅读记录的体检（v5.9.1 第六步）：只认「日集合 = check_ins」这一条口径，
 * 查出三类结构性问题，纯函数、不碰 DAO，修不修由 UI 决定。
 *
 * <ul>
 *   <li>精确轮 0 天：模式是精确、这一轮却一个日子都没有——速度样本会把它当「无天数」；</li>
 *   <li>起止与日集合不一致：派生起止本来就该由日集合两端决定（{@link ReadingRecords#deriveRange}）；</li>
 *   <li>跨轮同一天：同一个 bookId + dayKey 出现在两个 seq 里。表的主键是
 *       (bookId, dayKey)，正常路径写不出这种数据，这里防的是导入/旧包等旁路。</li>
 *   <li>孤儿轮（v5.9.8）：check_ins 里某本书有某个 seq 的打卡，但书的阅读记录里
 *       没有这一轮——「删轮不级联」年代删出来的残留；书已被整本删掉的行也算。</li>
 * </ul>
 */
public final class ReadingModeHealth {

    public static final int KIND_NO_DAYS = 1;
    public static final int KIND_RANGE_MISMATCH = 2;
    public static final int KIND_CROSS_ROUND = 3;
    public static final int KIND_ORPHAN_ROUND = 4;

    private ReadingModeHealth() {
    }

    /** 一条待处理项：既能上屏逐条列出，也带着修复要用到的原始数据 */
    public static final class Issue {
        public final int kind;
        public final long bookId;
        public final String title;
        public final int seq;
        public final String currentRange;
        public final String expectedRange;
        /** 跨轮重复里「该删的那一行」的完整快照（撤销按字段写回） */
        public final CheckIn row;
        /** 跨轮重复里保留的轮次 */
        public final int keptSeq;

        /** 孤儿轮的打卡天数（执行修复前给用户看规模） */
        public final int dayCount;

        Issue(int kind, long bookId, String title, int seq,
              String currentRange, String expectedRange, CheckIn row, int keptSeq) {
            this(kind, bookId, title, seq, currentRange, expectedRange, row, keptSeq, 0);
        }

        Issue(int kind, long bookId, String title, int seq,
              String currentRange, String expectedRange, CheckIn row, int keptSeq,
              int dayCount) {
            this.kind = kind;
            this.bookId = bookId;
            this.title = title;
            this.seq = seq;
            this.currentRange = currentRange;
            this.expectedRange = expectedRange;
            this.row = row;
            this.keptSeq = keptSeq;
            this.dayCount = dayCount;
        }

        public String line() {
            if (kind == KIND_NO_DAYS) {
                return "《" + title + "》第 " + seq + " 轮：精确记录 0 天";
            }
            if (kind == KIND_CROSS_ROUND) {
                return "《" + title + "》" + DateKeys.formatKey(row.dayKey)
                        + "：同时出现在第 " + keptSeq + " 轮和第 " + seq + " 轮";
            }
            if (kind == KIND_ORPHAN_ROUND) {
                return "《" + title + "》第 " + seq + " 轮：打卡日历还有 " + dayCount
                        + " 天，但阅读记录里已经没有这一轮";
            }
            return "《" + title + "》第 " + seq + " 轮：起止 " + currentRange
                    + "，与打卡日 " + expectedRange + " 不一致";
        }

        public String action() {
            if (kind == KIND_NO_DAYS) {
                return "第 " + seq + " 轮改回模糊（原起止保留）";
            }
            if (kind == KIND_CROSS_ROUND) {
                return "删掉第 " + seq + " 轮这一天，保留第 " + keptSeq + " 轮";
            }
            if (kind == KIND_ORPHAN_ROUND) {
                return "清掉第 " + seq + " 轮残留的 " + dayCount + " 天打卡";
            }
            return "按打卡日重写第 " + seq + " 轮的起止";
        }
    }

    /** 一次体检的结果 */
    public static final class Report {
        public final List<Issue> issues = new ArrayList<>();
        public int noDays;
        public int rangeMismatch;
        public int crossRound;
        public int orphanRound;

        public boolean isEmpty() {
            return issues.isEmpty();
        }

        public int total() {
            return issues.size();
        }

        public String line() {
            if (issues.isEmpty()) {
                return "精确记录：没发现 0 天、起止不符或跨轮重复";
            }
            StringBuilder sb = new StringBuilder("精确记录 ").append(issues.size())
                    .append(" 处要看看：0 天 ").append(noDays).append(" 轮");
            if (rangeMismatch > 0) {
                sb.append("、起止与打卡日不一致 ").append(rangeMismatch).append(" 轮");
            }
            if (crossRound > 0) {
                sb.append("、跨轮同一天 ").append(crossRound).append(" 处");
            }
            if (orphanRound > 0) {
                sb.append("、孤儿轮 ").append(orphanRound).append(" 轮");
            }
            return sb.toString();
        }

        /** 执行前逐条列出来：改哪本、哪一轮、改成什么，用户点「执行」前能看全 */
        public String describePlan() {
            StringBuilder sb = new StringBuilder("将处理 ").append(issues.size()).append(" 处：\n");
            for (Issue issue : issues) {
                sb.append("· ").append(issue.line()).append('\n');
                sb.append("  → ").append(issue.action()).append('\n');
            }
            return sb.toString().trim();
        }
    }

    public static Report check(List<Book> books, List<CheckIn> rows) {
        Report report = new Report();
        Map<Long, Map<Integer, List<Integer>>> byBook = ReadDays.byBook(rows);
        if (books != null) {
            for (Book raw : BookSanitizer.normalizeAll(books)) {
                if (raw == null) {
                    continue;
                }
                List<ReadingRecords.Record> records = ReadingRecords.load(raw);
                Map<Integer, List<Integer>> bySeq = byBook.get(raw.id);
                for (ReadingRecords.Record r : records) {
                    if (r == null || r.mode != ReadingRecords.MODE_PRECISE) {
                        continue;
                    }
                    List<Integer> keys = bySeq == null ? null : bySeq.get(r.seq);
                    if (ReadDays.precise(keys) == 0) {
                        report.noDays++;
                        report.issues.add(new Issue(KIND_NO_DAYS, raw.id, title(raw), r.seq,
                                range(r), "", null, 0));
                        continue;
                    }
                    String expectedStart = DateKeys.formatKey(min(keys));
                    String expectedEnd = r.status == Book.READ_DONE
                            ? DateKeys.formatKey(max(keys)) : "";
                    if (!nz(r.startDate).equals(expectedStart)
                            || !nz(r.endDate).equals(expectedEnd)) {
                        report.rangeMismatch++;
                        report.issues.add(new Issue(KIND_RANGE_MISMATCH, raw.id, title(raw), r.seq,
                                range(r), range(expectedStart, expectedEnd), null, 0));
                    }
                }
            }
        }
        collectCrossRound(report, books, rows);
        collectOrphanRounds(report, books, rows);
        return report;
    }

    /**
     * 孤儿轮（v5.9.8）：check_ins 里的 (bookId, seq) 在书的阅读记录里找不到。
     *
     * <p>只报两种确定性残留：书已被整本删掉（行还在表里，日历还在画点）；
     * 书还在、阅读记录非空、但里面没有这个 seq——正是「删轮不级联」年代的
     * 删除残留。阅读记录为空的在读书<b>不报</b>：老数据里「在读但没建过会话」
     * 的书完全可能只有 check_ins，按孤儿清掉会误删真实历史。
     */
    private static void collectOrphanRounds(Report report, List<Book> books, List<CheckIn> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<Long, String> titles = new HashMap<>();
        Map<Long, Set<Integer>> knownSeqs = new HashMap<>();
        if (books != null) {
            for (Book raw : BookSanitizer.normalizeAll(books)) {
                if (raw == null) {
                    continue;
                }
                titles.put(raw.id, title(raw));
                Set<Integer> seqs = new HashSet<>();
                for (ReadingRecords.Record r : ReadingRecords.load(raw)) {
                    if (r != null) {
                        seqs.add(r.seq);
                    }
                }
                knownSeqs.put(raw.id, seqs);
            }
        }
        // (bookId, seq) → [dayCount]，一轮一条 issue 而不是一天一条
        Map<Long, Map<Integer, Integer>> orphans = new HashMap<>();
        for (CheckIn c : rows) {
            if (c == null) {
                continue;
            }
            Set<Integer> seqs = knownSeqs.get(c.bookId);
            boolean orphan = seqs == null // 书已整本删掉：行一定是指针悬空的残留
                    || (!seqs.isEmpty() && !seqs.contains(c.seq));
            if (orphan) {
                orphans.computeIfAbsent(c.bookId, k -> new HashMap<>())
                        .merge(c.seq, 1, Integer::sum);
            }
        }
        for (Map.Entry<Long, Map<Integer, Integer>> byBook : orphans.entrySet()) {
            String title = titles.get(byBook.getKey());
            String name = title == null ? "#" + byBook.getKey() : title;
            for (Map.Entry<Integer, Integer> bySeq : byBook.getValue().entrySet()) {
                report.orphanRound++;
                report.issues.add(new Issue(KIND_ORPHAN_ROUND, byBook.getKey(), name,
                        bySeq.getKey(), null, null, null, 0, bySeq.getValue()));
            }
        }
    }

    /** 同一个 (bookId, dayKey) 出现多条：保留 seq 最小的那条，其余各算一处 */
    private static void collectCrossRound(Report report, List<Book> books, List<CheckIn> rows) {
        if (rows == null || rows.isEmpty()) {
            return;
        }
        Map<Long, String> titles = new HashMap<>();
        if (books != null) {
            for (Book raw : BookSanitizer.normalizeAll(books)) {
                if (raw != null) {
                    titles.put(raw.id, title(raw));
                }
            }
        }
        Map<String, List<CheckIn>> grouped = new HashMap<>();
        for (CheckIn c : rows) {
            if (c == null) {
                continue;
            }
            String key = c.bookId + ":" + c.dayKey;
            List<CheckIn> list = grouped.get(key);
            if (list == null) {
                list = new ArrayList<>();
                grouped.put(key, list);
            }
            list.add(c);
        }
        for (List<CheckIn> list : grouped.values()) {
            if (list.size() < 2) {
                continue;
            }
            CheckIn kept = list.get(0);
            for (CheckIn c : list) {
                if (c.seq < kept.seq) {
                    kept = c;
                }
            }
            for (CheckIn c : list) {
                if (c == kept) {
                    continue;
                }
                report.crossRound++;
                String title = titles.get(c.bookId);
                report.issues.add(new Issue(KIND_CROSS_ROUND, c.bookId,
                        title == null ? "#" + c.bookId : title,
                        c.seq, null, null, c, kept.seq));
            }
        }
    }

    private static String title(Book book) {
        String t = nz(book.title).trim();
        return t.isEmpty() ? "#" + book.id : t;
    }

    private static String range(ReadingRecords.Record r) {
        return range(nz(r.startDate), nz(r.endDate));
    }

    private static String range(String start, String end) {
        if (start.isEmpty() && end.isEmpty()) {
            return "未填";
        }
        if (start.isEmpty()) {
            return "～" + end;
        }
        return end.isEmpty() ? start + " 起" : start + "～" + end;
    }

    private static int min(List<Integer> keys) {
        int v = keys.get(0);
        for (int key : keys) {
            if (key < v) {
                v = key;
            }
        }
        return v;
    }

    private static int max(List<Integer> keys) {
        int v = keys.get(0);
        for (int key : keys) {
            if (key > v) {
                v = key;
            }
        }
        return v;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
