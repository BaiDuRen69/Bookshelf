package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.CheckIn;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 批量对齐日集合与跨轮移动（v5.9.1 修订第二步）：差集最小改动、拒绝项不写库、
 * 派生起止由 store 统一回写。拿内存库跑，不依赖系统时间（todayKey 传固定值）。
 */
@RunWith(AndroidJUnit4.class)
public class CheckInBatchInstrumentedTest {

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

    private Book preciseBook() {
        Book b = new Book();
        b.title = "批量测试";
        b.pages = 100;
        b.listPrice = 10;
        b.purchasePrice = 10;
        b.quantity = 1;
        b.readStatus = Book.READING;
        b.createdAt = 1700000000000L;
        b.updatedAt = 1700000000000L;
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = Book.READING;
        r.mode = ReadingRecords.MODE_PRECISE;
        r.startDate = "2026-11-01";
        b.readingRecords = ReadingRecords.toJson(Collections.singletonList(r));
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
    public void 差集最小改动_保留的行createdAt不动() {
        Book b = preciseBook();
        seed(b.id, 1, 20261101, 20261102, 20261103);
        CheckInStore.BatchResult r = CheckInStore.applyDayKeys(db, b, 1,
                Arrays.asList(20261102, 20261103, 20261104), TODAY);
        assertEquals(1, r.added);
        assertEquals(1, r.removed);
        assertEquals(0, r.rejectedFuture);
        assertEquals(0, r.rejectedOtherSeq);
        List<CheckIn> rows = db.checkInDao().getByBookSeq(b.id, 1);
        assertEquals(3, rows.size());
        assertEquals("保留的行不能被重写", 1000L + 20261102, rows.get(0).createdAt);
        assertEquals(20261104, rows.get(2).dayKey);
        assertTrue("补录的过去日子记 backfill", rows.get(2).backfill);
    }

    @Test
    public void 未来与跨轮的日子被拒绝且不写库() {
        Book b = preciseBook();
        seed(b.id, 2, 20261102);
        CheckInStore.BatchResult r = CheckInStore.applyDayKeys(db, b, 1,
                Arrays.asList(20261101, 20261102, 20261106), TODAY);
        assertEquals(1, r.added);
        assertEquals(1, r.rejectedFuture);
        assertEquals(1, r.rejectedOtherSeq);
        assertTrue("拒绝项不能落库", db.checkInDao().getByBookSeq(b.id, 1).size() == 1);
        assertEquals("别轮的行原样", 1, db.checkInDao().getByBookSeq(b.id, 2).size());
    }

    @Test
    public void 清空目标集合等于删掉该轮全部日子() {
        Book b = preciseBook();
        seed(b.id, 1, 20261101, 20261102);
        CheckInStore.BatchResult r = CheckInStore.applyDayKeys(db, b, 1,
                new ArrayList<>(), TODAY);
        assertEquals(2, r.removed);
        assertTrue(db.checkInDao().getByBookSeq(b.id, 1).isEmpty());
    }

    @Test
    public void 跨轮移动后两轮集合都正确() {
        Book b = preciseBook();
        seed(b.id, 1, 20261101);
        seed(b.id, 2, 20261102);
        int moved = CheckInStore.moveToSeq(db, b.id, Arrays.asList(20261101), 2);
        assertEquals(1, moved);
        assertTrue(db.checkInDao().getByBookSeq(b.id, 1).isEmpty());
        assertEquals(Arrays.asList(20261101, 20261102), db.checkInDao().getDayKeys(b.id, 2));
        assertEquals("已经在本轮的日子不再挪", 0,
                CheckInStore.moveToSeq(db, b.id, Arrays.asList(20261101), 2));
    }

    @Test
    public void 对齐后精确轮派生起止由store回写() {
        Book b = preciseBook();
        CheckInStore.applyDayKeys(db, b, 1, Arrays.asList(20261101, 20261103), TODAY);
        Book fresh = db.bookDao().getById(b.id);
        assertEquals("开始日 = 日集合最小值", "2026-11-01", fresh.readingStartDate);
        List<ReadingRecords.Record> records = ReadingRecords.parse(fresh.readingRecords);
        assertEquals(ReadingRecords.MODE_PRECISE, records.get(0).mode);
        assertEquals("在读不写结束日", "", records.get(0).endDate);
    }
}
