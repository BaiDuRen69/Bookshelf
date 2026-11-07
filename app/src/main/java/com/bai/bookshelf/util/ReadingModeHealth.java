package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.CheckIn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 精确阅读记录的体检（v5.9.1 第六步）：只认「日集合 = check_ins」这一条口径，
 * 查出三类结构性问题，纯函数、不碰 DAO，修不修由 UI 决定。
 *
 * <ul>
 *   <li>精确轮 0 天：模式是精确、这一轮却一个日子都没有——速度样本会把它当「无天数」；</li>
 *   <li>起止与日集合不一致：派生起止本来就该由日集合两端决定（{@link ReadingRecords#deriveRange}）；</li>
 *   <li>跨轮同一天：同一个 bookId + dayKey 出现在两个 seq 里。表的主键是
 *       (bookId, dayKey)，正常路径写不出这种数据，这里防的是导入/旧包等旁路。</li>
 * </ul>
 */
public final class ReadingModeHealth {

    public static final int KIND_NO_DAYS = 1;
    public static final int KIND_RANGE_MISMATCH = 2;
    public static final int KIND_CROSS_ROUND = 3;

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

        Issue(int kind, long bookId, String title, int seq,
              String currentRange, String expectedRange, CheckIn row, int keptSeq) {
            this.kind = kind;
            this.bookId = bookId;
            this.title = title;
            this.seq = seq;
            this.currentRange = currentRange;
            this.expectedRange = expectedRange;
            this.row = row;
            this.keptSeq = keptSeq;
        }

        public String line() {
            if (kind == KIND_NO_DAYS) {
                return "《" + title + "》第 " + seq + " 轮：精确记录 0 天";
            }
            if (kind == KIND_CROSS_ROUND) {
                return "《" + title + "》" + DateKeys.formatKey(row.dayKey)
                        + "：同时出现在第 " + keptSeq + " 轮和第 " + seq + " 轮";
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
            return "按打卡日重写第 " + seq + " 轮的起止";
        }
    }

    /** 一次体检的结果 */
    public static final class Report {
        public final List<Issue> issues = new ArrayList<>();
        public int noDays;
        public int rangeMismatch;
        public int crossRound;

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
        return report;
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
