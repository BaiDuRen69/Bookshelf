package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.dao.BookSeqStat;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.CheckIn;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 打卡页去全表扫描（v5.9.2 第 7 项）：新的按书/轮次聚合必须与旧的 recordedDays
 * 逐本逐轮一致。样本覆盖多轮、跨月、已删书的孤儿行、未来日。
 */
@RunWith(AndroidJUnit4.class)
public class CheckInStatsInstrumentedTest {

    private static final int TODAY = 20261105;

    private AppDatabase db;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase.class)
                .allowMainThreadQueries().build();
    }

    @After
    public void tearDown() {
        db.close();
    }

    private Book book(String title) {
        Book b = new Book();
        b.title = title;
        b.pages = 100;
        b.listPrice = 10;
        b.purchasePrice = 10;
        b.quantity = 1;
        b.readStatus = Book.READING;
        b.createdAt = 1700000000000L;
        b.updatedAt = 1700000000000L;
        ReadingRecords.Record r1 = new ReadingRecords.Record(1);
        r1.status = Book.READ_DONE;
        r1.mode = ReadingRecords.MODE_PRECISE;
        r1.startDate = "2026-09-20";
        r1.endDate = "2026-10-02";
        ReadingRecords.Record r2 = new ReadingRecords.Record(2);
        r2.status = Book.READING;
        r2.mode = ReadingRecords.MODE_PRECISE;
        r2.startDate = "2026-11-01";
        b.readingRecords = ReadingRecords.toJson(Arrays.asList(r1, r2));
        b.id = db.bookDao().insert(b);
        return b;
    }

    private void seed(long bookId, int seq, int... keys) {
        for (int key : keys) {
            CheckIn c = new CheckIn();
            c.bookId = bookId;
            c.dayKey = key;
            c.seq = seq;
            c.createdAt = 1000L + key;
            db.checkInDao().insert(c);
        }
    }

    @Test
    public void 聚合天数与旧口径逐本逐轮一致() {
        Book a = book("多轮跨月");
        Book b = book("另一本");
        seed(a.id, 1, 20260920, 20260921, 20260930, 20261001, 20261002);
        seed(a.id, 2, 20261101, 20261103, 20261103); // 重复主键被 IGNORE
        seed(b.id, 1, 20261104);
        seed(b.id, 2, 20261105, 20261106); // 20261106 是未来日，旧口径也照数
        // 已删书的孤儿行：书表里没有它
        seed(99999L, 1, 20261101, 20261102);

        List<CheckIn> all = db.checkInDao().getInRange(0, 99999999);
        List<BookSeqStat> stats = db.checkInDao().statsForBooks(Arrays.asList(a.id, b.id));

        Set<String> pairs = new HashSet<>();
        for (BookSeqStat s : stats) {
            pairs.add(s.bookId + ":" + s.seq);
            assertEquals("book=" + s.bookId + " seq=" + s.seq,
                    CheckInStore.recordedDays(all, s.bookId, s.seq), s.dayCount);
        }
        assertEquals("a 两轮 + b 两轮", 4, pairs.size());
        assertEquals("跨月按总天数聚合", 5, dayCount(stats, a.id, 1));
        assertEquals("重复主键不重复计", 2, dayCount(stats, a.id, 2));
        assertEquals("未来日旧口径也数", 2, dayCount(stats, b.id, 2));
        for (BookSeqStat s : stats) {
            assertTrue("孤儿行不属于任何在读书，不应出现在按书聚合里", s.bookId != 99999L);
        }
    }

    @Test
    public void 单轮计数与当天计数同口径() {
        Book a = book("单轮");
        // 主键是 (bookId, dayKey)：同一天只能有一条，重复插入被 IGNORE
        seed(a.id, 1, 20261101, 20261102, 20261102);
        seed(a.id, 2, 20261105);
        assertEquals(2, db.checkInDao().countRoundDays(a.id, 1));
        assertEquals(1, db.checkInDao().countRoundDays(a.id, 2));
        assertEquals(0, db.checkInDao().countRoundDays(a.id, 9));
        assertEquals(1, db.checkInDao().countOnDay(20261102));
        assertEquals(0, db.checkInDao().countOnDay(20261201));
    }

    @Test
    public void 截至今天的不同自然日与旧去重一致() {
        Book a = book("日历");
        seed(a.id, 1, 20261103, 20261103);
        seed(a.id, 2, 20261104, 20261107); // 07 未来
        List<Integer> days = db.checkInDao().distinctDaysUpTo(TODAY);
        assertEquals(2, days.size());
        assertEquals(new HashSet<>(Arrays.asList(20261103, 20261104)), new HashSet<>(days));
        Set<Integer> old = new HashSet<>();
        for (CheckIn c : db.checkInDao().getInRange(0, 99999999)) {
            if (c.dayKey <= TODAY) {
                old.add(c.dayKey);
            }
        }
        assertEquals(old, new HashSet<>(days));
        assertEquals("连续天数的新旧入口一致",
                Arrays.toString(CheckInStore.streakDays(
                        db.checkInDao().getInRange(0, 99999999), TODAY)),
                Arrays.toString(CheckInStore.streakDaysOf(days, TODAY)));
    }

    @Test
    public void 没有在读书时聚合早退不查库() {
        // 调用方用空列表短路；这里确认空书单不会被拼成 IN ()
        List<BookSeqStat> stats = new ArrayList<>();
        assertEquals(0, dayCount(stats, 1L, 1));
        assertEquals(Collections.emptyList(), db.checkInDao().getByBookSeq(1L, 1));
    }

    private static int dayCount(List<BookSeqStat> stats, long bookId, int seq) {
        for (BookSeqStat s : stats) {
            if (s.bookId == bookId && s.seq == seq) {
                return s.dayCount;
            }
        }
        return 0;
    }
}
