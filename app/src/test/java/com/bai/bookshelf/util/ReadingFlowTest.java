package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

import java.util.List;

/**
 * 阅读会话状态机（v5.8.31 第一步）。
 * 核心要钉住的是那条老 bug：弃读→再读→读完被压成一场，
 * 开始日停在第一次翻开、时长跨掉弃读空窗。
 */
public class ReadingFlowTest {

    private static Book book() {
        Book b = new Book();
        b.id = 1;
        return b;
    }

    private static int seqOf(ReadingRecords.Record r) {
        return r.seq;
    }

    private static ReadingRecords.Record session(int seq, int status, String start,
                                                 String end, double rating) {
        ReadingRecords.Record r = new ReadingRecords.Record(seq);
        r.status = status;
        r.startDate = start;
        r.endDate = end;
        r.rating = rating;
        return r;
    }

    @Test
    public void startCreatesOneReadingSession() {
        Book b = book();
        assertTrue(ReadingFlow.start(b, "2026-10-01"));
        List<ReadingRecords.Record> records = ReadingRecords.parse(b.readingRecords);
        assertEquals(1, records.size());
        assertEquals(1, seqOf(records.get(0)));
        assertEquals(Book.READING, records.get(0).status);
        assertEquals("2026-10-01", records.get(0).startDate);
        assertEquals("", records.get(0).endDate);
        assertEquals(Book.READING, b.readStatus);
        assertEquals("2026-10-01", b.readingStartDate);
        assertEquals("", b.readingFinishDate);
        assertEquals(0, b.readingProgress);
        assertTrue(b.dirty);
    }

    @Test
    public void startIsIdempotentWhileReading() {
        Book b = book();
        ReadingFlow.start(b, "2026-10-01");
        assertFalse("已经在读必须返回 false", ReadingFlow.start(b, "2026-10-09"));
        assertEquals(1, ReadingRecords.parse(b.readingRecords).size());
        assertEquals("2026-10-01", b.readingStartDate);
    }

    @Test
    public void finishClosesTheOngoingSession() {
        Book b = book();
        ReadingFlow.start(b, "2026-10-01");
        assertTrue(ReadingFlow.finish(b, "2026-10-20"));
        ReadingRecords.Record only = ReadingRecords.lastOf(ReadingRecords.parse(b.readingRecords));
        assertEquals(Book.READ_DONE, only.status);
        assertEquals("2026-10-20", only.endDate);
        assertEquals(100, only.progress);
        assertEquals(Book.READ_DONE, b.readStatus);
        assertEquals("2026-10-20", b.readingFinishDate);
        assertEquals(100, b.readingProgress);
    }

    /** 没有进行中会话也要能标已读：新增一场 DONE，不动旧会话 */
    @Test
    public void finishWithoutOngoingAddsNewSession() {
        Book b = book();
        ReadingFlow.finish(b, "2026-10-05");
        List<ReadingRecords.Record> records = ReadingRecords.parse(b.readingRecords);
        assertEquals(1, records.size());
        assertEquals(Book.READ_DONE, records.get(0).status);
        assertEquals("2026-10-05", records.get(0).startDate);
        assertEquals("2026-10-05", records.get(0).endDate);
    }

    /**
     * 老 bug 的正题：弃读→再读→读完 要留下**两场**独立会话
     * （第 1 场 1/1 开始、1/10 弃读；第 2 场 6/1 才开始、6/20 读完），
     * 而不是旧实现那样压成一场、开始日停在 1/1 让时长跨掉弃读空窗。
     */
    @Test
    public void dropRestartFinishKeepsSeparateSessions() {
        Book b = book();
        ReadingFlow.start(b, "2026-01-01");
        ReadingFlow.drop(b, "2026-01-10");
        assertTrue(ReadingFlow.restart(b, "2026-06-01"));
        assertTrue(ReadingFlow.finish(b, "2026-06-20"));

        List<ReadingRecords.Record> records = ReadingRecords.parse(b.readingRecords);
        assertEquals(2, records.size());
        assertEquals(Book.READ_DROPPED, records.get(0).status);
        assertEquals("2026-01-01", records.get(0).startDate);
        assertEquals("2026-01-10", records.get(0).endDate);
        assertEquals(Book.READ_DONE, records.get(1).status);
        assertEquals("2026-06-01", records.get(1).startDate);
        assertEquals("2026-06-20", records.get(1).endDate);
        // 第二轮时长 = 6/1..6/20（旧实现会是 1/1..6/20 = 171 天，把空窗算进去）
        assertEquals(20, ReadingRecords.days(records.get(1)));
        assertEquals(10, ReadingRecords.days(records.get(0)));
        assertEquals(Book.READ_DONE, b.readStatus);
        assertEquals("2026-06-01", b.readingStartDate);
        assertEquals("2026-06-20", b.readingFinishDate);
    }

    @Test
    public void dropKeepsProgressAndNeverWritesFinishDate() {
        Book b = book();
        ReadingFlow.start(b, "2026-10-01");
        // 进度弹窗写的是「进行中的那一场」的 progress（UI 侧走 ReadingRecords 同一套 API）
        List<ReadingRecords.Record> records = ReadingRecords.load(b);
        ReadingRecords.ongoingReading(records).progress = 35;
        ReadingRecords.syncTo(b, records);
        assertEquals(35, b.readingProgress);

        assertTrue(ReadingFlow.drop(b, "2026-10-12"));
        ReadingRecords.Record dropped = ReadingRecords.lastOf(ReadingRecords.parse(b.readingRecords));
        assertEquals(Book.READ_DROPPED, dropped.status);
        assertEquals("弃读要停在百分之几就是百分之几", 35, dropped.progress);
        assertEquals("2026-10-12", dropped.endDate);
        assertEquals("", b.readingFinishDate);
        assertEquals(Book.READ_DROPPED, b.readStatus);
        assertEquals(35, b.readingProgress);
    }

    @Test
    public void dropWithoutOngoingAddsSameDaySession() {
        Book b = book();
        assertTrue(ReadingFlow.drop(b, "2026-10-12"));
        ReadingRecords.Record only = ReadingRecords.lastOf(ReadingRecords.parse(b.readingRecords));
        assertEquals(Book.READ_DROPPED, only.status);
        assertEquals("2026-10-12", only.startDate);
        assertEquals("2026-10-12", only.endDate);
    }

    @Test
    public void restartAfterDoneStartsNewSession() {
        Book b = book();
        ReadingFlow.start(b, "2026-10-01");
        ReadingFlow.finish(b, "2026-10-10");
        assertTrue(ReadingFlow.restart(b, "2026-11-01"));
        List<ReadingRecords.Record> records = ReadingRecords.parse(b.readingRecords);
        assertEquals(2, records.size());
        assertEquals(Book.READING, records.get(1).status);
        assertEquals("2026-11-01", records.get(1).startDate);
        assertEquals(Book.READING, b.readStatus);
        // 规格口径：finishDate = 最后一场 DONE 的 endDate，与当前状态无关。
        // 所以「读完一遍又重开第二遍」的书，状态是在读、但仍挂着第一轮的读完日期。
        assertEquals("2026-10-10", b.readingFinishDate);
    }

    @Test
    public void restartIsIdempotentWhileReading() {
        Book b = book();
        ReadingFlow.start(b, "2026-10-01");
        assertFalse(ReadingFlow.restart(b, "2026-10-09"));
        assertEquals(1, ReadingRecords.parse(b.readingRecords).size());
    }

    @Test
    public void undoToNoneRemovesLastSessionNotItsStatus() {
        Book b = book();
        ReadingFlow.start(b, "2026-10-01");
        ReadingFlow.finish(b, "2026-10-10");
        ReadingFlow.restart(b, "2026-11-01");        // 现在有两场：DONE + READING
        assertTrue(ReadingFlow.undoToNone(b));
        List<ReadingRecords.Record> records = ReadingRecords.parse(b.readingRecords);
        assertEquals(1, records.size());
        assertEquals("撤的是最后一场，不是把它的状态改成 0",
                Book.READ_DONE, records.get(0).status);
        assertEquals("撤销后回到已读", Book.READ_DONE, b.readStatus);
        assertEquals("2026-10-10", b.readingFinishDate);
    }

    @Test
    public void undoToNoneOnLastSessionClearsSummary() {
        Book b = book();
        ReadingFlow.start(b, "2026-10-01");
        assertTrue(ReadingFlow.undoToNone(b));
        assertEquals("", b.readingRecords);
        assertEquals(Book.READ_NONE, b.readStatus);
        assertEquals("", b.readingStartDate);
        assertEquals(0, b.readingProgress);
        assertFalse("没有会话可删时返回 false", ReadingFlow.undoToNone(b));
    }

    /** 汇总评分只平均已读会话：弃读那场打的分不掺进来 */
    @Test
    public void ratingAveragesOnlyDoneSessions() {
        Book b = book();
        List<ReadingRecords.Record> records = new java.util.ArrayList<>();
        records.add(session(1, Book.READ_DROPPED, "2026-10-01", "2026-10-03", 1));
        records.add(session(2, Book.READ_DONE, "2026-10-05", "2026-10-20", 5));
        ReadingRecords.syncTo(b, records);
        assertEquals(5.0, b.ratingAvg, 0.001);
        assertEquals(5, b.rating);
        assertEquals(3.0, ReadingRecords.averageRating(records), 0.001); // 旧口径仍是 3
    }

    /** 旧 JSON 没有 progress 字段：已读补 100，其余补 0 */
    @Test
    public void legacyJsonWithoutProgressIsBackfilled() {
        Book b = book();
        b.readingRecords = "[{\"seq\":1,\"status\":2,\"startDate\":\"2026-01-01\","
                + "\"endDate\":\"2026-01-05\",\"rating\":4}]";
        ReadingRecords.Record done = ReadingRecords.parse(b.readingRecords).get(0);
        assertEquals(100, done.progress);

        b.readingRecords = "[{\"seq\":1,\"status\":3,\"startDate\":\"2026-01-01\","
                + "\"endDate\":\"2026-01-05\"}]";
        assertEquals(0, ReadingRecords.parse(b.readingRecords).get(0).progress);
    }

    /** 自愈①：标着已读却没有已读会话 → 补一场，且不改写已有会话 */
    @Test
    public void deriveAddsMissingDoneSession() {
        Book b = book();
        b.readStatus = Book.READ_DONE;
        b.readingStartDate = "2026-01-01";
        b.readingFinishDate = "2026-01-09";
        b.readingRecords = "[{\"seq\":1,\"status\":1,\"startDate\":\"2026-01-01\","
                + "\"endDate\":\"\",\"rating\":0}]";
        List<ReadingRecords.Record> fixed = ReadingRecords.derive(b);
        assertEquals(2, fixed.size());
        assertEquals(Book.READING, fixed.get(0).status);   // 旧会话没被动过
        assertEquals(Book.READ_DONE, fixed.get(1).status);
        // 补完会话后 syncTo 重算优先级：还有一场在进行中 → 汇总是在读，
        // 但「曾经读完」这件事已经落进会话，finishDate 不再只是书上的孤值
        assertEquals(Book.READING, b.readStatus);
        assertEquals("2026-01-09", b.readingFinishDate);
    }

    /** 自愈②：旧代码给弃读写进「读完日期」→ 日期归到弃读会话，汇总清空 */
    @Test
    public void deriveMovesFinishDateIntoDroppedSession() {
        Book b = book();
        b.readStatus = Book.READ_DROPPED;
        b.readingStartDate = "2026-01-01";
        b.readingFinishDate = "2026-01-06";
        b.readingRecords = "[{\"seq\":1,\"status\":3,\"startDate\":\"2026-01-01\","
                + "\"endDate\":\"\",\"rating\":0}]";
        List<ReadingRecords.Record> fixed = ReadingRecords.derive(b);
        assertEquals("2026-01-06", fixed.get(0).endDate);
        assertEquals("", b.readingFinishDate);
        assertEquals(Book.READ_DROPPED, b.readStatus);
    }

    /** 先读完、后来又弃读一遍：DONE 会话存在，自愈不许抹掉第一次的读完日期 */
    @Test
    public void deriveKeepsFinishDateWhenSomeDoneSessionExists() {
        Book b = book();
        b.readingRecords = "[{\"seq\":1,\"status\":2,\"startDate\":\"2026-01-01\","
                + "\"endDate\":\"2026-01-09\",\"rating\":4},"
                + "{\"seq\":2,\"status\":3,\"startDate\":\"2026-02-01\",\"endDate\":\"2026-02-02\"}]";
        ReadingRecords.syncTo(b, ReadingRecords.parse(b.readingRecords));
        ReadingRecords.derive(b);
        assertEquals("2026-01-09", b.readingFinishDate);
        assertEquals("按优先级：有 DONE 会话就是已读", Book.READ_DONE, b.readStatus);
    }

    /** 验收口径 4：已读→再读→读完 两条 DONE 会话，平均分只算已读会话 */
    @Test
    public void rereadAfterDoneKeepsTwoDoneSessions() {
        Book b = book();
        ReadingFlow.start(b, "2026-01-01");
        ReadingFlow.finish(b, "2026-01-10");
        java.util.List<ReadingRecords.Record> recs = ReadingRecords.load(b);
        recs.get(0).rating = 5;
        ReadingRecords.syncTo(b, recs);
        ReadingFlow.restart(b, "2026-03-01");
        ReadingFlow.finish(b, "2026-03-05");
        recs = ReadingRecords.load(b);
        recs.get(1).rating = 3;
        ReadingRecords.syncTo(b, recs);

        recs = ReadingRecords.parse(b.readingRecords);
        assertEquals(2, recs.size());
        assertEquals(Book.READ_DONE, recs.get(0).status);
        assertEquals(Book.READ_DONE, recs.get(1).status);
        assertEquals(4.0, b.ratingAvg, 0.001);
        assertEquals(4, b.rating);
        assertEquals("2026-03-05", b.readingFinishDate);
    }

    /** 验收口径 5：旧脏数据（单条 DONE 跨 400 天）自愈不许算成负数或抛异常 */
    @Test
    public void legacyFourHundredDayDoneSessionSurvives() {
        Book b = book();
        b.readStatus = Book.READ_DONE;
        b.readingStartDate = "2025-01-01";
        b.readingFinishDate = "2026-02-05";
        java.util.List<ReadingRecords.Record> recs = ReadingRecords.derive(b);
        assertEquals(1, recs.size());
        assertEquals(401, ReadingRecords.days(recs.get(0)));   // 含首尾：2025-01-01 → 2026-02-05
        assertTrue(ReadingRecords.days(recs.get(0)) > 0);
        assertEquals(Book.READ_DONE, b.readStatus);
        assertEquals("2026-02-05", b.readingFinishDate);
        assertFalse("没有弃读会话就不该拆", ReadingRecords.splitLongReadAfterDrop(b, 365));
        // 自愈只在内存生效：没调用 syncTo 就不该把会话写回 JSON
        assertEquals("", b.readingRecords);
    }
}
