package com.bai.bookshelf.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.CheckIn;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** 打卡业务规则：纯 JVM，固定「今天」= 20261105，不依赖系统时间。 */
public class CheckInStoreTest {

    private static final int TODAY = 20261105;
    private static final int START = 20261101;

    private static Book reading(long id, String startDate) {
        Book b = new Book();
        b.id = id;
        b.readStatus = Book.READING;
        b.readingStartDate = startDate; // 无 JSON 时 ReadingRecords.load 会合成第 1 场
        b.readingRecords = "";
        return b;
    }

    private static Book readingWithJson(long id, String startDate) {
        Book b = new Book();
        b.id = id;
        b.readStatus = Book.READING;
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = Book.READING;
        r.startDate = startDate;
        b.readingRecords = ReadingRecords.toJson(Collections.singletonList(r));
        return b;
    }

    private static CheckIn ci(long bookId, int dayKey, boolean backfill) {
        CheckIn c = new CheckIn();
        c.bookId = bookId;
        c.dayKey = dayKey;
        c.backfill = backfill;
        c.createdAt = 1700000000000L;
        return c;
    }

    /** 内存版 Sink：模拟 Room 的 @Insert(IGNORE) 与 @Delete 按主键行为 */
    private static final class MemSink implements CheckInStore.Sink {
        final List<CheckIn> rows = new ArrayList<>();

        @Override
        public boolean insert(CheckIn c) {
            if (exists(c.bookId, c.dayKey)) {
                return false;
            }
            rows.add(c);
            return true;
        }

        @Override
        public void delete(long bookId, int dayKey) {
            for (int i = rows.size() - 1; i >= 0; i--) {
                CheckIn c = rows.get(i);
                if (c.bookId == bookId && c.dayKey == dayKey) {
                    rows.remove(i);
                }
            }
        }

        @Override
        public boolean exists(long bookId, int dayKey) {
            return seqOf(bookId, dayKey) != 0;
        }

        @Override
        public int seqOf(long bookId, int dayKey) {
            for (CheckIn c : rows) {
                if (c.bookId == bookId && c.dayKey == dayKey) {
                    return c.seq;
                }
            }
            return 0;
        }
    }

    private static CheckInStore.Result toggle(MemSink sink, Book book, int dayKey,
                                              boolean allowBackfill) {
        return CheckInStore.toggle(sink, book, dayKey, allowBackfill, TODAY);
    }

    // ==================== 范围与状态 ====================

    @Test
    public void 未来日期禁止打卡() {
        MemSink sink = new MemSink();
        assertEquals(CheckInStore.Result.REJECTED_FUTURE,
                toggle(sink, reading(1, "2026-11-01"), 20261106, true));
        assertTrue(sink.rows.isEmpty());
    }

    @Test
    public void 早于开始阅读日禁止打卡() {
        MemSink sink = new MemSink();
        assertEquals(CheckInStore.Result.REJECTED_BEFORE_START,
                toggle(sink, reading(1, "2026-11-01"), 20261031, true));
        assertTrue(sink.rows.isEmpty());
    }

    @Test
    public void 开始阅读日当天允许打卡() {
        MemSink sink = new MemSink();
        assertEquals(CheckInStore.Result.ADDED,
                toggle(sink, reading(1, "2026-11-01"), START, true));
    }

    @Test
    public void readingRecords里的会话起始日同样生效() {
        MemSink sink = new MemSink();
        assertEquals(CheckInStore.Result.REJECTED_BEFORE_START,
                toggle(sink, readingWithJson(1, "2026-11-01"), 20261031, true));
        assertEquals(CheckInStore.Result.ADDED,
                toggle(sink, readingWithJson(1, "2026-11-01"), START, true));
    }

    @Test
    public void 起始日解析不到时退化为不检查下界() {
        MemSink sink = new MemSink();
        assertEquals(CheckInStore.Result.ADDED,
                toggle(sink, reading(1, ""), 20260101, true));
        assertEquals(CheckInStore.Result.ADDED,
                toggle(sink, reading(2, "不是日期"), 20260102, true));
    }

    @Test
    public void 非在读状态一律拒绝() {
        int[] statuses = {Book.READ_NONE, Book.READ_DONE, Book.READ_DROPPED};
        for (int status : statuses) {
            Book b = reading(1, "2026-11-01");
            b.readStatus = status;
            MemSink sink = new MemSink();
            assertEquals("status=" + status, CheckInStore.Result.REJECTED_NOT_READING,
                    toggle(sink, b, TODAY, true));
            assertTrue(sink.rows.isEmpty());
        }
    }

    @Test
    public void canCheckIn覆盖四个边界() {
        Book b = reading(1, "2026-11-01");
        assertFalse(CheckInStore.canCheckIn(b, 20261106, TODAY)); // 未来
        assertFalse(CheckInStore.canCheckIn(b, 20261031, TODAY)); // 早于起始
        assertTrue(CheckInStore.canCheckIn(b, START, TODAY));      // 起始当天
        assertTrue(CheckInStore.canCheckIn(b, TODAY, TODAY));      // 今天
        b.readStatus = Book.READ_DONE;
        assertFalse(CheckInStore.canCheckIn(b, TODAY, TODAY));     // 非在读
    }

    // ==================== 打卡 / 补卡 / 撤销 ====================

    @Test
    public void 今天打卡不是补卡且字段正确() {
        MemSink sink = new MemSink();
        assertEquals(CheckInStore.Result.ADDED, toggle(sink, reading(7, "2026-11-01"), TODAY, true));
        CheckIn c = sink.rows.get(0);
        assertEquals(7L, c.bookId);
        assertEquals(TODAY, c.dayKey);
        assertFalse(c.backfill);
        assertEquals(0, c.pages);
        assertTrue(c.createdAt > 0);
    }

    @Test
    public void 过去日期补卡标记为backfill() {
        MemSink sink = new MemSink();
        assertEquals(CheckInStore.Result.ADDED,
                toggle(sink, reading(1, "2026-11-01"), 20261103, true));
        assertTrue(sink.rows.get(0).backfill);
        assertTrue(CheckInStore.isBackfill(20261103, TODAY));
        assertFalse(CheckInStore.isBackfill(TODAY, TODAY));
        assertFalse(CheckInStore.isBackfill(20261106, TODAY));
    }

    @Test
    public void 禁止补卡时过去日被拒绝今天仍可打卡() {
        MemSink sink = new MemSink();
        assertEquals(CheckInStore.Result.REJECTED_BACKFILL,
                toggle(sink, reading(1, "2026-11-01"), 20261104, false));
        assertTrue(sink.rows.isEmpty());
        assertEquals(CheckInStore.Result.ADDED,
                toggle(sink, reading(1, "2026-11-01"), TODAY, false));
    }

    @Test
    public void 重复写入按IGNORE幂等不抛异常且只留一条() {
        MemSink sink = new MemSink();
        CheckIn c = ci(1, TODAY, false);
        assertTrue(sink.insert(c));
        assertFalse(sink.insert(ci(1, TODAY, true)));
        assertEquals(1, sink.rows.size());
    }

    @Test
    public void 撤销后再打视为新增() {
        MemSink sink = new MemSink();
        Book b = reading(1, "2026-11-01");
        assertEquals(CheckInStore.Result.ADDED, toggle(sink, b, TODAY, true));
        assertEquals(CheckInStore.Result.REMOVED, toggle(sink, b, TODAY, true));
        assertTrue(sink.rows.isEmpty());
        assertEquals(CheckInStore.Result.ADDED, toggle(sink, b, TODAY, true));
        assertEquals(1, sink.rows.size());
    }

    @Test
    public void 撤销不受阅读状态变化影响() {
        MemSink sink = new MemSink();
        Book b = reading(1, "2026-11-01");
        toggle(sink, b, TODAY, true);
        b.readStatus = Book.READ_DONE;
        assertEquals(CheckInStore.Result.REMOVED, toggle(sink, b, TODAY, true));
    }

    @Test
    public void 并发下IGNORE返回false仍按幂等视为已打卡() {
        CheckInStore.Sink racy = new CheckInStore.Sink() {
            @Override
            public boolean insert(CheckIn c) {
                return false;
            }

            @Override
            public void delete(long bookId, int dayKey) {
            }

            @Override
            public boolean exists(long bookId, int dayKey) {
                return false;
            }

            @Override
            public int seqOf(long bookId, int dayKey) {
                return 0;
            }
        };
        assertEquals(CheckInStore.Result.ADDED,
                CheckInStore.toggle(racy, reading(1, "2026-11-01"), TODAY, true, TODAY));
    }

    // ==================== 连续天数 ====================

    @Test
    public void 连续天数含补卡且跨月跨年() {
        List<CheckIn> list = Arrays.asList(
                ci(1, 20251230, true), ci(1, 20251231, true),
                ci(2, 20260101, false), ci(1, 20260102, false));
        assertArrayEquals(new int[]{4, 20260102},
                CheckInStore.streakDays(list, 20260102));
    }

    @Test
    public void 同一天多本只算一天() {
        List<CheckIn> list = Arrays.asList(
                ci(1, 20261103, false), ci(2, 20261103, false), ci(3, 20261103, false));
        assertArrayEquals(new int[]{1, 20261103},
                CheckInStore.streakDays(list, 20261103));
    }

    @Test
    public void 今天未打但昨天打过连续不清零且pendingToday为真() {
        List<CheckIn> list = Arrays.asList(ci(1, 20261103, false), ci(1, 20261104, false));
        assertArrayEquals(new int[]{2, 20261104}, CheckInStore.streakDays(list, TODAY));
        assertTrue(CheckInStore.pendingToday(list, TODAY));
    }

    @Test
    public void 断签超过一天连续归零且pendingToday为假() {
        List<CheckIn> list = Collections.singletonList(ci(1, 20261101, false));
        assertArrayEquals(new int[]{0, 20261101}, CheckInStore.streakDays(list, TODAY));
        assertFalse(CheckInStore.pendingToday(list, TODAY));
    }

    @Test
    public void 无记录时连续为零() {
        assertArrayEquals(new int[]{0, 0}, CheckInStore.streakDays(Collections.emptyList(), TODAY));
        assertFalse(CheckInStore.pendingToday(Collections.emptyList(), TODAY));
    }

    @Test
    public void 今天已打卡时pendingToday为假() {
        List<CheckIn> list = Arrays.asList(ci(1, 20261104, false), ci(1, TODAY, false));
        assertFalse(CheckInStore.pendingToday(list, TODAY));
        assertArrayEquals(new int[]{2, TODAY}, CheckInStore.streakDays(list, TODAY));
    }

    // ==================== 月 / 年聚合 ====================

    @Test
    public void 月聚合同一天两本等于一天两次且补卡计数正确() {
        List<CheckIn> list = Arrays.asList(
                ci(1, 20261103, true), ci(2, 20261103, true),
                ci(1, 20261104, false));
        assertArrayEquals(new int[]{2, 3, 2}, CheckInStore.monthSummary(list, 2026, 11));
    }

    @Test
    public void 年聚合跨年去重且只统计指定年份() {
        List<CheckIn> list = Arrays.asList(
                ci(1, 20251231, true), ci(1, 20260101, false),
                ci(2, 20260101, false), ci(1, 20261231, false));
        assertArrayEquals(new int[]{2, 3, 0}, CheckInStore.yearSummary(list, 2026));
        assertArrayEquals(new int[]{1, 1, 1}, CheckInStore.yearSummary(list, 2025));
    }

    @Test
    public void 按日统计打卡次数用于视图打点() {
        List<CheckIn> list = Arrays.asList(
                ci(1, 20261103, false), ci(2, 20261103, false), ci(1, 20261105, false));
        Map<Integer, Integer> map = CheckInStore.countByDayInRange(list);
        assertEquals(Integer.valueOf(2), map.get(20261103));
        assertEquals(Integer.valueOf(1), map.get(20261105));
        assertEquals(2, map.size());
    }

    @Test
    public void 空列表与null输入不抛异常() {
        assertArrayEquals(new int[]{0, 0, 0}, CheckInStore.monthSummary(null, 2026, 11));
        assertTrue(CheckInStore.countByDayInRange(null).isEmpty());
        assertArrayEquals(new int[]{0, 0}, CheckInStore.streakDays(null, TODAY));
    }

    /** v5.9.1：打卡归到当前在读的那一轮，精确轮靠 (bookId, seq) 取日集合 */
    @Test
    public void 打卡落在当前在读轮次上() {
        Book b = new Book();
        b.id = 7;
        b.readStatus = Book.READING;
        List<ReadingRecords.Record> records = new ArrayList<>();
        ReadingRecords.Record done = new ReadingRecords.Record(1);
        done.status = Book.READ_DONE;
        done.startDate = "2026-09-01";
        done.endDate = "2026-09-20";
        records.add(done);
        ReadingRecords.Record ongoing = new ReadingRecords.Record(2);
        ongoing.status = Book.READING;
        ongoing.startDate = "2026-11-01";
        ongoing.mode = ReadingRecords.MODE_PRECISE;
        records.add(ongoing);
        b.readingRecords = ReadingRecords.toJson(records);

        assertEquals(2, CheckInStore.currentSeq(b));
        MemSink sink = new MemSink();
        assertEquals(CheckInStore.Result.ADDED, toggle(sink, b, TODAY, true));
        assertEquals("第二轮在读，打卡不能落进第一轮", 2, sink.rows.get(0).seq);
    }

    @Test
    public void 没有会话时打卡归第一轮() {
        Book b = new Book();
        b.id = 7;
        b.readStatus = Book.READ_NONE;
        assertEquals(1, CheckInStore.currentSeq(b));
        assertEquals(1, CheckInStore.currentSeq(null));
    }

    /** 精确轮在读 + 指定拥有日/录入日的小书 */
    private static Book preciseReading(String purchaseDate, long createdAt) {
        Book b = new Book();
        b.id = 7;
        b.readStatus = Book.READING;
        b.purchaseDate = purchaseDate;
        b.createdAt = createdAt;
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = Book.READING;
        r.mode = ReadingRecords.MODE_PRECISE;
        r.startDate = "2026-11-01";
        b.readingRecords = ReadingRecords.toJson(Collections.singletonList(r));
        return b;
    }

    /** 与现状的行为差异，必须钉住：精确轮补历史可以早于会话起始日 */
    @Test
    public void 精确轮允许补早于会话起始日的历史() {
        MemSink sink = new MemSink();
        Book b = preciseReading("2026-10-01", 0);
        assertEquals("会话 11-01 才开始，但 10-15 拥有之后就能补",
                CheckInStore.Result.ADDED, toggle(sink, b, 20261015, true));
        assertEquals("拥有日之前还是不行", CheckInStore.Result.REJECTED_BEFORE_START,
                toggle(sink, b, 20260930, true));
    }

    @Test
    public void 精确轮没有拥有日就退到录入当天再退到无下界() {
        java.util.Calendar c = java.util.Calendar.getInstance();
        c.clear();
        c.set(2026, 9, 20, 12, 0, 0); // 中午，避开时区零点边界
        MemSink sink = new MemSink();
        Book created = preciseReading("", c.getTimeInMillis());
        assertEquals(CheckInStore.Result.REJECTED_BEFORE_START,
                toggle(sink, created, 20261019, true));
        assertEquals(CheckInStore.Result.ADDED, toggle(sink, created, 20261020, true));

        MemSink sink2 = new MemSink();
        Book none = preciseReading("", 0);
        assertEquals("拥有日与录入日都没有就不设下界",
                CheckInStore.Result.ADDED, toggle(sink2, none, 20260101, true));
    }

    @Test
    public void 模糊轮下界仍是会话起始日() {
        MemSink sink = new MemSink();
        Book b = readingWithJson(7, "2026-11-01");
        assertEquals("模糊轮不许补到会话开始之前",
                CheckInStore.Result.REJECTED_BEFORE_START, toggle(sink, b, 20261015, true));
    }

    @Test
    public void 日记在别轮时拒绝而不是抢过来() {
        MemSink sink = new MemSink();
        Book b = preciseReading("2026-10-01", 0);
        CheckIn other = ci(7, 20261015, false);
        other.seq = 2; // 同一天记在第 2 轮
        sink.rows.add(other);
        assertEquals(CheckInStore.Result.REJECTED_OTHER_SEQ, toggle(sink, b, 20261015, true));
        assertEquals("别轮的行不能被删掉", 1, sink.rows.size());
        assertEquals(2, sink.rows.get(0).seq);
    }

    @Test
    public void 本轮已记的日子再点是撤销() {
        MemSink sink = new MemSink();
        Book b = preciseReading("2026-10-01", 0);
        assertEquals(CheckInStore.Result.ADDED, toggle(sink, b, 20261015, true));
        assertEquals(CheckInStore.Result.REMOVED, toggle(sink, b, 20261015, true));
        assertTrue(sink.rows.isEmpty());
    }
}
