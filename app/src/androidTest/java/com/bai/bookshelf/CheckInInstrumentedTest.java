package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.Cursor;

import androidx.room.Room;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.util.BookCascade;
import com.bai.bookshelf.util.CheckInStore;
import com.bai.bookshelf.util.DateKeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * 打卡数据层真机测试（打卡页一期第 ① 步）。
 *
 * <p>跑在 {@code .devtest} 隔离包：用独立的 check_in_probe.db，不碰正式版书库，
 * 也不与 DbRepairInstrumentedTest 的「故意弄坏主库」互相干扰。
 * JVM 单测覆盖纯规则，这里覆盖 Room 生成实现、SQLite 主键冲突与真机迁移后的表结构。
 */
@RunWith(AndroidJUnit4.class)
public class CheckInInstrumentedTest {

    private static final String PROBE_DB = "check_in_probe.db";

    private AppDatabase db;

    @Before
    public void setUp() {
        Context ctx = ApplicationProvider.getApplicationContext();
        ctx.deleteDatabase(PROBE_DB);
        db = Room.databaseBuilder(ctx, AppDatabase.class, PROBE_DB)
                .addMigrations(AppDatabase.migrations())
                .build();
        db.getOpenHelper().getWritableDatabase(); // 触发建库，失败要在这里就暴露
    }

    @After
    public void tearDown() {
        if (db != null) {
            db.close();
        }
        ApplicationProvider.getApplicationContext().deleteDatabase(PROBE_DB);
    }

    private static CheckIn ci(long bookId, int dayKey, boolean backfill) {
        CheckIn c = new CheckIn();
        c.bookId = bookId;
        c.dayKey = dayKey;
        c.backfill = backfill;
        c.createdAt = System.currentTimeMillis();
        c.pages = 0;
        return c;
    }

    private static boolean rowExists(SupportSQLiteDatabase sdb, String sql) {
        Cursor c = sdb.query(sql);
        try {
            return c.getCount() > 0;
        } finally {
            c.close();
        }
    }

    @Test
    public void 真机建库带出check_ins表与dayKey索引() {
        SupportSQLiteDatabase sdb = db.getOpenHelper().getWritableDatabase();
        assertTrue("check_ins 表应存在", rowExists(sdb,
                "SELECT name FROM sqlite_master WHERE type='table' AND name='check_ins'"));
        assertTrue("index_check_ins_dayKey 索引应存在", rowExists(sdb,
                "SELECT name FROM sqlite_master WHERE type='index' "
                        + "AND name='index_check_ins_dayKey'"));
    }

    @Test
    public void 复合主键重复插入被IGNORE只留一条() {
        long first = db.checkInDao().insert(ci(1, 20261105, false));
        long second = db.checkInDao().insert(ci(1, 20261105, true));
        assertTrue("首次写入应成功", first != -1);
        assertEquals("重复写入应被 IGNORE", -1L, second);
        assertEquals(1, db.checkInDao().countAll());
        assertFalse("原有记录不能被覆盖", db.checkInDao().getByDay(20261105).get(0).backfill);
    }

    @Test
    public void 撤销按复合主键删除() {
        CheckIn c = ci(1, 20261105, false);
        db.checkInDao().insert(c);
        db.checkInDao().delete(c);
        assertEquals(0, db.checkInDao().countAll());
    }

    @Test
    public void 删书联动只清掉该书打卡() {
        db.checkInDao().insert(ci(1, 20261103, true));
        db.checkInDao().insert(ci(1, 20261104, false));
        db.checkInDao().insert(ci(2, 20261104, false));
        db.checkInDao().deleteByBook(1);
        assertEquals(1, db.checkInDao().countAll());
        assertEquals(2L, db.checkInDao().getByDay(20261104).get(0).bookId);
    }

    @Test
    public void 区间单日单书查询在真机返回正确结果() {
        db.checkInDao().insert(ci(1, 20261103, true));
        db.checkInDao().insert(ci(2, 20261103, false));
        db.checkInDao().insert(ci(1, 20261201, false));
        assertEquals(3, db.checkInDao().countAll());
        assertEquals(2, db.checkInDao().getInRange(20261101, 20261130).size());
        assertEquals(1, db.checkInDao().getInRange(20261201, 20261231).size());
        assertEquals(2, db.checkInDao().getByDay(20261103).size());
        assertEquals(2, db.checkInDao().getByBook(1).size());
    }

    @Test
    public void 业务层toggle在真机Room上写入与撤销() {
        Book b = new Book();
        b.id = 99;
        b.readStatus = Book.READING;
        b.readingStartDate = "2020-01-01";
        b.readingRecords = "";
        int today = DateKeys.todayKey();

        assertEquals(CheckInStore.Result.ADDED,
                CheckInStore.toggle(db, b, today, true));
        assertEquals(1, db.checkInDao().countAll());
        assertEquals("打卡不能改写阅读状态", Book.READING, b.readStatus);

        assertEquals(CheckInStore.Result.REMOVED,
                CheckInStore.toggle(db, b, today, true));
        assertEquals(0, db.checkInDao().countAll());
        assertEquals(Book.READING, b.readStatus);
    }

    @Test
    public void App主库在真机上打开且迁移后有check_ins表() {
        BookshelfApp app = (BookshelfApp) ApplicationProvider.getApplicationContext();
        AppDatabase main = app.db();
        assertNotNull(main);
        SupportSQLiteDatabase sdb = main.getOpenHelper().getWritableDatabase();
        assertTrue("主库升级/新建后应有 check_ins", rowExists(sdb,
                "SELECT name FROM sqlite_master WHERE type='table' AND name='check_ins'"));
        // 只读校验，不往正式库写数据
        List<CheckIn> all = main.checkInDao().getInRange(0, 99999999);
        assertNotNull(all);
    }

    @Test
    public void 删书级联清理打卡且撤销能原样恢复() {
        BookshelfApp app = (BookshelfApp) ApplicationProvider.getApplicationContext();
        Book b = new Book();
        b.title = "打卡级联测试样本";
        long id = app.db().bookDao().insert(b);
        try {
            app.db().checkInDao().insert(ci(id, 20261105, true));
            assertEquals(1, app.db().checkInDao().getByBook(id).size());

            BookCascade.Deleted d = BookCascade.deleteRecoverable(app, id);
            assertEquals("快照要带上打卡记录", 1, d.checkIns.size());
            assertTrue("删书后打卡不能留孤儿",
                    app.db().checkInDao().getByBook(id).isEmpty());

            BookCascade.restore(app, d);
            assertEquals("撤销后打卡应原样恢复", 1,
                    app.db().checkInDao().getByBook(id).size());
        } finally {
            app.db().checkInDao().deleteByBook(id);
            app.db().bookDao().deleteById(id);
        }
    }
}
