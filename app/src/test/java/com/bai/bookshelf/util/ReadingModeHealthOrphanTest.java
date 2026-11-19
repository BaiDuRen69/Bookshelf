package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.CheckIn;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 孤儿轮扫描（v5.9.8 T1）的纯 JVM 校验。
 *
 * <p>三种确定性残留要报：书整本删掉后 check_ins 悬空、删轮不级联留下的 seq、
 * 同一轮里的多天合并成一条 issue。唯一不能报的是「在读但没建过会话」的老数据——
 * 阅读记录为空的书完全可能只有 check_ins，报了就会误删真实历史。
 */
public class ReadingModeHealthOrphanTest {

    private static Book book(long id, String title, ReadingRecords.Record... records) {
        Book b = new Book();
        b.id = id;
        b.title = title;
        List<ReadingRecords.Record> list = new ArrayList<>(Arrays.asList(records));
        ReadingRecords.syncTo(b, list);
        return b;
    }

    private static ReadingRecords.Record round(int seq) {
        ReadingRecords.Record r = new ReadingRecords.Record(seq);
        r.mode = ReadingRecords.MODE_PRECISE;
        r.status = Book.READ_DONE;
        return r;
    }

    private static CheckIn row(long bookId, int dayKey, int seq) {
        CheckIn c = new CheckIn();
        c.bookId = bookId;
        c.dayKey = dayKey;
        c.seq = seq;
        return c;
    }

    @Test
    public void 删轮残留的seq按轮合并成一条() {
        Book b = book(1, "三体", round(1));
        List<CheckIn> rows = Arrays.asList(
                row(1, 20261110, 2), row(1, 20261111, 2), row(1, 20261101, 1));
        ReadingModeHealth.Report report = ReadingModeHealth.check(
                new ArrayList<>(Arrays.asList(b)), new ArrayList<>(rows));

        assertEquals(1, report.orphanRound);
        ReadingModeHealth.Issue issue = null;
        for (ReadingModeHealth.Issue i : report.issues) {
            if (i.kind == ReadingModeHealth.KIND_ORPHAN_ROUND) {
                issue = i;
            }
        }
        assertTrue(issue != null);
        assertEquals(2, issue.seq);
        assertEquals("同轮多天要合并计天数，不是一天一条", 2, issue.dayCount);
        assertTrue(issue.line().contains("三体"));
        assertTrue(issue.action().contains("第 2 轮"));
    }

    @Test
    public void 书已整本删掉的行也算孤儿() {
        List<CheckIn> rows = Arrays.asList(row(99, 20261101, 1), row(99, 20261102, 1));
        ReadingModeHealth.Report report = ReadingModeHealth.check(
                new ArrayList<>(), new ArrayList<>(rows));
        assertEquals("书没了，打卡行全是悬空残留", 1, report.orphanRound);
        assertEquals(2, report.issues.get(0).dayCount);
    }

    @Test
    public void 在读但没建会话的老数据不能报() {
        // 阅读记录为空的在读书：check_ins 可能是它唯一的阅读证据，清了就是事故
        Book legacy = book(5, "活着");
        legacy.readStatus = Book.READING;
        List<CheckIn> rows = Arrays.asList(row(5, 20261101, 1), row(5, 20261102, 1));
        ReadingModeHealth.Report report = ReadingModeHealth.check(
                new ArrayList<>(Arrays.asList(legacy)), new ArrayList<>(rows));
        assertEquals("空会话的书一律不按孤儿处理", 0, report.orphanRound);
        assertTrue(report.isEmpty());
    }

    @Test
    public void 阅读记录里还有的seq不算孤儿() {
        Book b = book(1, "三体", round(1), round(2));
        List<CheckIn> rows = Arrays.asList(row(1, 20261101, 1), row(1, 20261110, 2));
        ReadingModeHealth.Report report = ReadingModeHealth.check(
                new ArrayList<>(Arrays.asList(b)), new ArrayList<>(rows));
        assertEquals(0, report.orphanRound);
    }
}
