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

    @Test
    public void 单日批量一次给多本打卡并跳过别轮() {
        Book a = preciseBook();
        Book b = preciseBook();
        seed(b.id, 2, TODAY); // 同一天已记在另一轮
        CheckInStore.DayBatchResult r =
                CheckInStore.addDayForBooks(db, Arrays.asList(a, b), TODAY, TODAY);
        assertEquals(1, r.added);
        assertEquals(1, r.skippedOtherSeq);
        assertEquals(Collections.singletonList(a.id), r.addedBookIds);
        assertEquals(Arrays.asList(TODAY), db.checkInDao().getDayKeys(a.id, 1));
        assertEquals("别轮的行不抢", Arrays.asList(TODAY), db.checkInDao().getDayKeys(b.id, 2));
    }

    @Test
    public void 单日批量撤销只删这一批() {
        Book a = preciseBook();
        Book b = preciseBook();
        seed(b.id, 1, TODAY);
        CheckInStore.DayBatchResult r =
                CheckInStore.addDayForBooks(db, Arrays.asList(a, b), TODAY, TODAY);
        assertEquals(1, r.added);
        assertEquals("已打卡的那本不重复计", 0, r.skippedOtherSeq);
        assertEquals(1, CheckInStore.removeDayForBooks(db, r.addedBookIds, TODAY));
        assertTrue(db.checkInDao().getDayKeys(a.id, 1).isEmpty());
        assertEquals("不在本批的旧打卡保留", Arrays.asList(TODAY),
                db.checkInDao().getDayKeys(b.id, 1));
    }

    @Test
    public void 未来日的批量一条也不写() {
        Book a = preciseBook();
        CheckInStore.DayBatchResult r = CheckInStore.addDayForBooks(
                db, Collections.singletonList(a), 20261106, TODAY);
        assertEquals(0, r.added);
        assertEquals(1, r.skippedFuture);
        assertTrue(db.checkInDao().getByBookSeq(a.id, 1).isEmpty());
    }

    /** 只有阅读状态、没有会话 JSON 也没有起止日的老书（真机里那批「已读 0 天」的样本） */
    private Book undatedFuzzyBook(int progress, int rating) {
        Book b = new Book();
        b.title = "合成阅读";
        b.pages = 100;
        b.listPrice = 10;
        b.purchasePrice = 10;
        b.quantity = 1;
        b.readStatus = Book.READING;
        b.readingProgress = progress;
        b.rating = rating;
        b.createdAt = 1700000000000L;
        b.updatedAt = 1700000000000L;
        b.readingRecords = "";
        b.id = db.bookDao().insert(b);
        return b;
    }

    private Book datedFuzzyBook(String startDate, int progress, int rating) {
        Book b = undatedFuzzyBook(progress, rating);
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = Book.READING;
        r.startDate = startDate;
        r.progress = progress;
        r.rating = rating;
        b.readingStartDate = startDate;
        b.readingRecords = ReadingRecords.toJson(Collections.singletonList(r));
        db.bookDao().update(b);
        return b;
    }

    @Test
    public void 无日期模糊轮打卡后提升为精确轮且保留进度评分() {
        Book b = undatedFuzzyBook(60, 1);
        seed(b.id, 1, 20261101, 20261103);
        assertEquals(CheckInStore.Result.ADDED,
                CheckInStore.toggle(db, b, TODAY, true, 1));
        Book fresh = db.bookDao().getById(b.id);
        assertEquals(Arrays.asList(20261101, 20261103, TODAY),
                db.checkInDao().getDayKeys(b.id, 1));
        List<ReadingRecords.Record> records = ReadingRecords.parse(fresh.readingRecords);
        assertEquals("合成会话只有一场", 1, records.size());
        assertEquals("无日期的模糊轮应提升", ReadingRecords.MODE_PRECISE, records.get(0).mode);
        assertEquals("开始日 = 最早打卡日", "2026-11-01", records.get(0).startDate);
        assertEquals("在读提升后不写结束日", "", records.get(0).endDate);
        assertEquals("2026-11-01", fresh.readingStartDate);
        assertEquals("", fresh.readingFinishDate);
        assertEquals("进度不能被归零", 60, fresh.readingProgress);
        assertEquals("评分不能被抹掉", 1, fresh.rating);
    }

    @Test
    public void 有日期的模糊轮打卡后仍保持模糊() {
        Book b = datedFuzzyBook("2026-10-01", 30, 4);
        seed(b.id, 1, 20261102);
        assertEquals(CheckInStore.Result.ADDED,
                CheckInStore.toggle(db, b, TODAY, true, 1));
        Book fresh = db.bookDao().getById(b.id);
        List<ReadingRecords.Record> records = ReadingRecords.parse(fresh.readingRecords);
        assertEquals("用户填过日期的模糊轮保持零改写", ReadingRecords.MODE_FUZZY,
                records.get(0).mode);
        assertEquals("2026-10-01", records.get(0).startDate);
        assertEquals("2026-10-01", fresh.readingStartDate);
    }

    @Test
    public void 撤销最后一天后精确轮零天且日集合为空() {
        Book b = undatedFuzzyBook(40, 2);
        b.ratingAvg = 2;
        db.bookDao().update(b);
        assertEquals(CheckInStore.Result.ADDED,
                CheckInStore.toggle(db, b, TODAY, true, 1));
        assertEquals(CheckInStore.Result.REMOVED,
                CheckInStore.toggle(db, db.bookDao().getById(b.id), TODAY, true, 1));
        Book fresh = db.bookDao().getById(b.id);
        assertTrue(db.checkInDao().getDayKeys(b.id, 1).isEmpty());
        List<ReadingRecords.Record> records = ReadingRecords.parse(fresh.readingRecords);
        assertEquals("撤回后保留精确轮（不降回模糊）", ReadingRecords.MODE_PRECISE,
                records.get(0).mode);
        assertEquals("日集合为空 = 0 天", 0,
                ReadingRecords.readDays(records.get(0),
                        db.checkInDao().getDayKeys(b.id, 1), null));
        assertEquals("撤销不冲掉用户评分", 2, fresh.rating);
        assertEquals(2.0, fresh.ratingAvg, 0.001);
        assertEquals("撤销不丢进度", 40, fresh.readingProgress);
    }

    @Test
    public void 提升幂等_多次打卡始终只有一条精确轮() {
        Book b = undatedFuzzyBook(50, 0);
        assertEquals(CheckInStore.Result.ADDED,
                CheckInStore.toggle(db, b, TODAY, true, 1));
        assertEquals(CheckInStore.Result.ADDED,
                CheckInStore.toggle(db, db.bookDao().getById(b.id), 20261106, true, 1));
        Book fresh = db.bookDao().getById(b.id);
        List<ReadingRecords.Record> records = ReadingRecords.parse(fresh.readingRecords);
        assertEquals("提升不新增会话", 1, records.size());
        assertEquals(ReadingRecords.MODE_PRECISE, records.get(0).mode);
        assertEquals("开始日跟着最早一天走", "2026-11-05", records.get(0).startDate);
        assertEquals("在读仍不写结束日", "", records.get(0).endDate);
        assertEquals(Arrays.asList(20261105, 20261106),
                db.checkInDao().getDayKeys(b.id, 1));
    }
}
