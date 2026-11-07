package com.bai.bookshelf.ui.edit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.dao.CheckInDao;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.util.DateKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 日集合暂存区的落库规则（v5.9.1 第四步）：只写改过的轮次，没碰过的一行不动。
 *
 * <p>拿内存库直接跑同步版 loadFrom/persist，不依赖 BookshelfApp 的线程调度。
 */
@RunWith(AndroidJUnit4.class)
public class ReadingDaySetsInstrumentedTest {

    private AppDatabase db;
    private CheckInDao dao;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase.class)
                .allowMainThreadQueries().build();
        dao = db.checkInDao();
    }

    @After
    public void tearDown() {
        db.close();
    }

    private void seed(long bookId, int seq, int... keys) {
        for (int key : keys) {
            CheckIn c = new CheckIn();
            c.bookId = bookId;
            c.dayKey = key;
            c.seq = seq;
            c.createdAt = 1000L + key;
            dao.insert(c);
        }
    }

    @Test
    public void 原样提交的轮次不落库也不改createdAt() {
        seed(7, 1, 20261101, 20261103);
        ReadingDaySets sets = new ReadingDaySets();
        sets.loadFrom(dao, 7);
        sets.commit(1, Arrays.asList(20261101, 20261103));
        assertFalse("与库里相同不算脏", sets.hasPending());
        sets.persist(dao, 7);
        List<CheckIn> rows = dao.getByBookSeq(7, 1);
        assertEquals(2, rows.size());
        assertEquals("没改过的行 createdAt 不能被刷新", 1000L + 20261101, rows.get(0).createdAt);
    }

    @Test
    public void 改过的轮次先清后插且补录记backfill() {
        seed(7, 1, 20261101);
        seed(7, 2, 20261105);
        ReadingDaySets sets = new ReadingDaySets();
        sets.loadFrom(dao, 7);
        int today = DateKeys.todayKey();
        int past = DateKeys.shiftDayKey(today, -3);
        sets.commit(1, Arrays.asList(past, today));
        assertTrue(sets.hasPending());
        sets.persist(dao, 7);

        List<CheckIn> mine = dao.getByBookSeq(7, 1);
        assertEquals("旧的那一天要被清掉", 2, mine.size());
        assertEquals(past, mine.get(0).dayKey);
        assertTrue("事后补的日子记 backfill", mine.get(0).backfill);
        assertEquals(today, mine.get(1).dayKey);
        assertFalse("今天不算补", mine.get(1).backfill);
        assertEquals("别的轮次一行不动", 1, dao.getByBookSeq(7, 2).size());
        assertFalse("落完库 pending 清空", sets.hasPending());
    }

    @Test
    public void 清空一轮只清这一轮() {
        seed(7, 1, 20261101, 20261102);
        seed(7, 2, 20261105);
        ReadingDaySets sets = new ReadingDaySets();
        sets.loadFrom(dao, 7);
        sets.commit(1, new ArrayList<>());
        sets.persist(dao, 7);
        assertTrue(dao.getByBookSeq(7, 1).isEmpty());
        assertEquals(1, dao.getByBookSeq(7, 2).size());
    }

    @Test
    public void keys返回副本改不动内部状态() {
        seed(7, 1, 20261101);
        ReadingDaySets sets = new ReadingDaySets();
        sets.loadFrom(dao, 7);
        List<Integer> keys = sets.keys(1);
        keys.add(20261109);
        assertEquals("外部改副本不该影响暂存区", 1, sets.keys(1).size());
        assertEquals(1, sets.savedCount(1));
    }
}
