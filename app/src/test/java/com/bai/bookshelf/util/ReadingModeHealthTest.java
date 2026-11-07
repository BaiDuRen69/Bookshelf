package com.bai.bookshelf.util;

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

/** 精确记录体检：三类脏数据各能检出，干净数据不误报。 */
public class ReadingModeHealthTest {

    private static Book precise(long id, String start, String end, int status) {
        Book b = new Book();
        b.id = id;
        b.title = "书" + id;
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.mode = ReadingRecords.MODE_PRECISE;
        r.status = status;
        r.startDate = start;
        r.endDate = end;
        b.readingRecords = ReadingRecords.toJson(Collections.singletonList(r));
        return b;
    }

    private static CheckIn ci(long bookId, int dayKey, int seq) {
        CheckIn c = new CheckIn();
        c.bookId = bookId;
        c.dayKey = dayKey;
        c.seq = seq;
        return c;
    }

    @Test
    public void 起止与日集合一致时不报() {
        Book b = precise(1, "2026-11-03", "", Book.READING);
        ReadingModeHealth.Report r = ReadingModeHealth.check(
                Collections.singletonList(b),
                Arrays.asList(ci(1, 20261103, 1), ci(1, 20261105, 1)));
        assertTrue(r.isEmpty());
        assertEquals(0, r.total());
        assertTrue(r.line().contains("没发现"));
    }

    @Test
    public void 精确轮零天单独算一类() {
        Book b = precise(1, "2026-11-03", "", Book.READING);
        ReadingModeHealth.Report r = ReadingModeHealth.check(
                Collections.singletonList(b), new ArrayList<>());
        assertEquals(1, r.total());
        assertEquals(1, r.noDays);
        assertEquals(0, r.rangeMismatch);
        assertEquals(ReadingModeHealth.KIND_NO_DAYS, r.issues.get(0).kind);
        assertTrue(r.issues.get(0).line().contains("精确记录 0 天"));
        assertTrue(r.issues.get(0).action().contains("改回模糊"));
    }

    @Test
    public void 已读完的轮错结束日也算起止不一致() {
        Book b = precise(2, "2026-11-03", "2026-11-09", Book.READ_DONE);
        List<CheckIn> rows = Arrays.asList(ci(2, 20261103, 1), ci(2, 20261105, 1));
        ReadingModeHealth.Report r = ReadingModeHealth.check(
                Collections.singletonList(b), rows);
        assertEquals(1, r.rangeMismatch);
        assertEquals(ReadingModeHealth.KIND_RANGE_MISMATCH, r.issues.get(0).kind);
        assertTrue(r.issues.get(0).line().contains("2026-11-09"));
        assertTrue(r.issues.get(0).line().contains("2026-11-05"));
        assertTrue(r.issues.get(0).action().contains("重写"));
    }

    @Test
    public void 在读轮只看派生开始日不要求结束日() {
        Book b = precise(3, "2026-11-03", "", Book.READING);
        ReadingModeHealth.Report r = ReadingModeHealth.check(
                Collections.singletonList(b),
                Arrays.asList(ci(3, 20261103, 1), ci(3, 20261105, 1)));
        assertFalse(r.rangeMismatch > 0);
    }

    @Test
    public void 跨轮同一天保留小轮次并报出其余() {
        Book b = precise(4, "2026-11-03", "", Book.READING);
        List<CheckIn> rows = Arrays.asList(
                ci(4, 20261103, 2), ci(4, 20261103, 1), ci(4, 20261105, 2));
        ReadingModeHealth.Report r = ReadingModeHealth.check(
                Collections.singletonList(b), rows);
        assertEquals(1, r.crossRound);
        ReadingModeHealth.Issue issue = r.issues.get(r.issues.size() - 1);
        assertEquals(ReadingModeHealth.KIND_CROSS_ROUND, issue.kind);
        assertEquals(1, issue.keptSeq);
        assertEquals(2, issue.seq);
        assertTrue(issue.line().contains("第 1 轮和第 2 轮"));
        assertTrue(issue.action().contains("保留第 1 轮"));
    }

    @Test
    public void 计划逐条列出改哪本改什么() {
        Book b = precise(5, "2026-11-03", "", Book.READING);
        ReadingModeHealth.Report r = ReadingModeHealth.check(
                Collections.singletonList(b), new ArrayList<>());
        String plan = r.describePlan();
        assertTrue(plan.startsWith("将处理 1 处"));
        assertTrue(plan.contains("《书5》第 1 轮"));
        assertTrue(plan.contains("→"));
    }
}
