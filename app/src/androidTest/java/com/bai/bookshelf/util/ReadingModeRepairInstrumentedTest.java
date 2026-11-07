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
import java.util.Collections;
import java.util.List;

/**
 * 精确记录体检的「执行 → 复检 → 撤销 → 再复检」整条链（v5.9.1 第六步）：
 * 拿内存库跑，验证起止重写、0 天降级为模糊、撤销逐字段还原。
 */
@RunWith(AndroidJUnit4.class)
public class ReadingModeRepairInstrumentedTest {

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

    private Book insertPrecise(String title, String start, String end, int status) {
        Book b = new Book();
        b.title = title;
        b.pages = 100;
        b.listPrice = 10;
        b.purchasePrice = 10;
        b.quantity = 1;
        b.readStatus = status;
        b.createdAt = 1700000000000L;
        b.updatedAt = 1700000000000L;
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = status;
        r.mode = ReadingRecords.MODE_PRECISE;
        r.startDate = start;
        r.endDate = end;
        b.readingRecords = ReadingRecords.toJson(Collections.singletonList(r));
        b.id = db.bookDao().insert(b);
        return b;
    }

    private void seed(long bookId, int... keys) {
        for (int key : keys) {
            CheckIn c = new CheckIn();
            c.bookId = bookId;
            c.dayKey = key;
            c.seq = 1;
            c.createdAt = 1000L + key;
            db.checkInDao().insert(c);
        }
    }

    private ReadingModeHealth.Report check() {
        return ReadingModeHealth.check(db.bookDao().getAll(), db.checkInDao().getAll());
    }

    @Test
    public void 修完复检为零撤销后找回原样() {
        Book wrong = insertPrecise("起止不符", "2026-10-20", "", Book.READING);
        seed(wrong.id, 20261101, 20261103);
        Book empty = insertPrecise("零天", "2026-11-02", "", Book.READING);

        ReadingModeHealth.Report before = check();
        assertEquals(2, before.total());
        assertEquals(1, before.rangeMismatch);
        assertEquals(1, before.noDays);

        List<Book> originals = new ArrayList<>(db.bookDao().getAll());
        List<CheckIn> rowsBefore = new ArrayList<>(db.checkInDao().getAll());

        ReadingModeRepair.Undo undo = ReadingModeRepair.apply(db, before);
        assertEquals(2, undo.count());

        ReadingModeHealth.Report after = check();
        assertTrue("修完复检必须清零", after.isEmpty());
        Book fixed = db.bookDao().getById(wrong.id);
        assertEquals("起止按日集合重写", "2026-11-01", fixed.readingStartDate);
        Book downgraded = db.bookDao().getById(empty.id);
        assertEquals(ReadingRecords.MODE_FUZZY,
                ReadingRecords.parse(downgraded.readingRecords).get(0).mode);
        assertEquals("打卡行一条都不许动", rowsBefore.size(), db.checkInDao().getAll().size());

        ReadingModeRepair.undo(db, undo);
        ReadingModeHealth.Report restored = check();
        assertEquals(2, restored.total());
        for (Book original : originals) {
            Book back = db.bookDao().getById(original.id);
            assertEquals("JSON 原样", original.readingRecords, back.readingRecords);
            assertEquals("汇总开始日原样", original.readingStartDate, back.readingStartDate);
            assertEquals("汇总状态原样", original.readStatus, back.readStatus);
        }
    }
}
