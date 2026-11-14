package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import androidx.room.Room;
import androidx.sqlite.db.framework.FrameworkSQLiteDatabase;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.util.DbRepair;
import com.bai.bookshelf.util.PreMigrationBackup;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 真实升级路径取证（EP-I1 T2）：在真机 SQLite 上从旧版本走 Room 的 onUpgrade，
 * 而不是全新安装走 onCreate（后者会绕过迁移，等于没测）。
 *
 * <p><b>为什么用 device-protected storage</b>：升级前副本只在「记下的 db_version ≠ 目标版本」
 * 时才留。devtest 槽的 app_prefs 已经是 26，而本轮硬约束禁止改 prefs / pm clear。
 * DPS 是应用完全没用过的一套独立 files/databases/shared_prefs（{@code grep 全库 0 命中}），
 * 在它里面 known=0，于是「老装机升级」这条路径能原样跑起来，应用正常状态一个字节都不碰。
 *
 * <p><b>旧库怎么造</b>：先用 Room 建出 v26，再 {@code DROP TABLE check_ins} + {@code user_version=24}。
 * 这不是猜的——v24 与 v26 的 schema 只差 check_ins 一张表，13 张共有表的 createSql 与索引
 * 逐字一致（比对 app/schemas/24.json 与 26.json 得出）。v25 那一腿用真实的
 * {@link AppDatabase#MIGRATION_24_25} 建表，不手抄 SQL。
 */
@RunWith(AndroidJUnit4.class)
public class MigrationUpgradePathInstrumentedTest {

    private Context ctx;
    private File dbFile;
    private AppDatabase room;

    @Before
    public void setUp() {
        final Context dps = ApplicationProvider.getApplicationContext()
                .createDeviceProtectedStorageContext();
        // Room 的 Builder 会取 getApplicationContext()，不裹一层就会落回应用正常的库
        ctx = new ContextWrapper(dps) {
            @Override
            public Context getApplicationContext() {
                return dps;
            }
        };
        dbFile = ctx.getDatabasePath(AppDatabase.DB_NAME);
    }

    @After
    public void tearDown() {
        closeRoom();
        // 只删本测试在 DPS 里造出来的东西。db_version 也必须清：markOpened 写过它，
        // 留着的话下一轮 known==26 就不再留副本，测试会变成「只有第一次能过」
        ctx.getSharedPreferences("app_prefs", Context.MODE_PRIVATE).edit().clear().commit();
        for (File copy : PreMigrationBackup.list(ctx)) {
            deleteRecursive(copy);
        }
        delete(dbFile);
        delete(new File(dbFile.getPath() + "-wal"));
        delete(new File(dbFile.getPath() + "-shm"));
    }

    @Test
    public void 二十四版旧库升到二十六版_书与副本与借阅一条不少且留下副本() throws Exception {
        createOldLibraryAt24();
        int booksBefore = rawCount("books");
        int copiesBefore = rawCount("book_copies");
        int borrowsBefore = rawCount("borrow_records");
        assertEquals("造出来的旧库要有书", 3, booksBefore);
        assertEquals("造出来的旧库要有逐本副本", 4, copiesBefore);
        assertEquals("造出来的旧库要有借出记录", 2, borrowsBefore);
        assertFalse("v24 还没有打卡表", rawTableExists("check_ins"));

        int before = PreMigrationBackup.prepareBeforeOpen(ctx);
        assertEquals("DPS 里没记过版本，应按老装机留一份副本", -1, before);
        assertSnapshotTaken();

        openRoom();
        assertEquals("真实 onUpgrade 要升到 26", 26,
                room.getOpenHelper().getWritableDatabase().getVersion());

        assertEquals("书不能少", booksBefore, room.bookDao().count());
        assertEquals("逐本副本不能少", copiesBefore, (int) room.bookCopyDao().count());
        assertEquals("借出记录不能少", borrowsBefore, (int) room.borrowRecordDao().count());
        assertEquals("v24 没有打卡行，升上来是空表", 0, room.checkInDao().countAll());
        assertEquals("书名不能被迁移改掉", "升级样本 一",
                scalar("SELECT title FROM books ORDER BY id LIMIT 1"));
        assertEquals("借出状态不能被迁移改掉", "0",
                scalar("SELECT status FROM borrow_records ORDER BY id LIMIT 1"));

        assertTrue("副本留下了才允许记版本", PreMigrationBackup.shouldMarkOpened(before));
        PreMigrationBackup.markOpened(ctx, AppDatabase.VERSION);
        assertEquals("记过版本后同一版本不再复制", 26, PreMigrationBackup.prepareBeforeOpen(ctx));
        assertEquals("副本仍是一份，没有重复留", 1, PreMigrationBackup.list(ctx).size());
    }

    @Test
    public void 二十五版历史打卡升到二十六版全部落第一轮() throws Exception {
        createOldLibraryAt24();
        SQLiteDatabase raw = SQLiteDatabase.openDatabase(dbFile.getPath(), null,
                SQLiteDatabase.OPEN_READWRITE);
        try {
            AppDatabase.MIGRATION_24_25.migrate(new FrameworkSQLiteDatabase(raw));
            raw.execSQL("PRAGMA user_version = 25");
            // v25 的 check_ins 还没有 seq 列，历史行只能按五列插
            raw.execSQL("INSERT INTO check_ins (bookId, dayKey, createdAt, backfill, pages)"
                    + " VALUES (1, 20261103, 1000, 1, 0)");
            raw.execSQL("INSERT INTO check_ins (bookId, dayKey, createdAt, backfill, pages)"
                    + " VALUES (1, 20261105, 2000, 0, 12)");
            raw.execSQL("INSERT INTO check_ins (bookId, dayKey, createdAt, backfill, pages)"
                    + " VALUES (2, 20261106, 3000, 0, 30)");
        } finally {
            raw.close();
        }
        assertEquals("v25 形状要有打卡表", true, rawTableExists("check_ins"));
        assertEquals(3, rawCount("check_ins"));
        assertFalse("v25 还不该有 seq 列", rawColumnExists("check_ins", "seq"));

        assertEquals(-1, PreMigrationBackup.prepareBeforeOpen(ctx));
        assertSnapshotTaken();

        openRoom();
        assertEquals(26, room.getOpenHelper().getWritableDatabase().getVersion());
        assertEquals("打卡行一条不能少", 3, room.checkInDao().countAll());
        assertEquals("历史行全部落第一轮", 3L, longScalar("SELECT COUNT(*) FROM check_ins WHERE seq = 1"));
        assertEquals("不许有落在别的轮次", 0L, longScalar("SELECT COUNT(*) FROM check_ins WHERE seq <> 1"));
        assertEquals("backfill 原样保留", 1L,
                longScalar("SELECT backfill FROM check_ins WHERE dayKey = 20261103"));
        assertEquals("pages 原样保留", 12L,
                longScalar("SELECT pages FROM check_ins WHERE dayKey = 20261105"));
        assertEquals("书还在", 3, room.bookDao().count());
        assertEquals("复合索引要建起来", "index_check_ins_bookId_seq",
                scalar("SELECT name FROM sqlite_master WHERE type='index'"
                        + " AND name='index_check_ins_bookId_seq'"));
    }

    // ==================== 造旧库 ====================

    /** 建 v26 → 插样本 → 退回 v24 形状（v24 与 v26 只差 check_ins 一张表） */
    private void createOldLibraryAt24() {
        openRoom();
        long b1 = insertBook("升级样本 一", "9787500001001");
        long b2 = insertBook("升级样本 二", "9787500001002");
        insertBook("升级样本 三", "9787500001003");

        List<BookCopy> copies = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            copies.add(copy(b1, i));
        }
        copies.add(copy(b2, 1));
        room.bookCopyDao().insertAll(copies);

        room.borrowRecordDao().insert(borrow(b1, "样本借阅人 甲", BorrowRecord.STATUS_OUT));
        room.borrowRecordDao().insert(borrow(b2, "样本借阅人 乙", BorrowRecord.STATUS_RETURNED));
        closeRoom();

        SQLiteDatabase raw = SQLiteDatabase.openDatabase(dbFile.getPath(), null,
                SQLiteDatabase.OPEN_READWRITE);
        try {
            raw.execSQL("DROP TABLE IF EXISTS `check_ins`");
            raw.execSQL("PRAGMA user_version = 24");
        } finally {
            raw.close();
        }
    }

    private long insertBook(String title, String isbn) {
        Book b = new Book();
        b.title = title;
        b.isbn = isbn;
        b.author = "升级路径样本";
        b.pages = 240;
        b.listPrice = 45;
        b.purchasePrice = 32;
        b.createdAt = 1700000000000L;
        b.updatedAt = b.createdAt;
        return room.bookDao().insert(b);
    }

    private static BookCopy copy(long bookId, int index) {
        BookCopy c = new BookCopy();
        c.bookId = bookId;
        c.copyIndex = index;
        c.stockStatus = 0;
        c.ownType = 0;
        c.purchasePrice = 32;
        return c;
    }

    private static BorrowRecord borrow(long bookId, String who, int status) {
        BorrowRecord r = new BorrowRecord();
        r.bookId = bookId;
        r.borrower = who;
        r.lendDate = "2026-11-01";
        r.dueDate = "2026-12-01";
        r.status = status;
        r.copyIndex = 1;
        return r;
    }

    // ==================== 断言与查询 ====================

    /** 迁移前副本必须真的在、非 0 字节、与原库等长，且下游认它 */
    private void assertSnapshotTaken() {
        List<File> copies = PreMigrationBackup.list(ctx);
        assertEquals("要留下一份迁移前副本", 1, copies.size());
        File snap = new File(copies.get(0), AppDatabase.DB_NAME);
        assertTrue("副本主库必须存在：" + snap.getPath(), snap.isFile());
        assertTrue("副本不能是 0 字节", snap.length() > 0);
        assertEquals("副本要与原库等长", dbFile.length(), snap.length());
        assertTrue("副本要被判为可用", PreMigrationBackup.isUsableCopy(copies.get(0)));
        assertEquals("可用副本份数", 1, PreMigrationBackup.count(ctx));
        assertTrue("修复页据此判为「有副本」", DbRepair.hasPreMigrationCopy(ctx));
        assertNotNull("修复页要能报出副本标签", DbRepair.latestCopyLabel(ctx));
        // 断言只说「过了」，字节数要留下来才是可复核的证据（adb logcat -s MigrationUpgradePath）
        android.util.Log.i("MigrationUpgradePath", "snapshot OK: db=" + dbFile.length()
                + " bytes, copy=" + snap.length() + " bytes, dir=" + copies.get(0).getName()
                + ", usable=" + PreMigrationBackup.count(ctx)
                + ", label=" + DbRepair.latestCopyLabel(ctx));
    }

    private void openRoom() {
        room = Room.databaseBuilder(ctx, AppDatabase.class, AppDatabase.DB_NAME)
                .addMigrations(AppDatabase.migrations())
                .build();
        room.getOpenHelper().getWritableDatabase();
    }

    private void closeRoom() {
        if (room != null) {
            room.close();
            room = null;
        }
    }

    private int rawCount(String table) {
        SQLiteDatabase raw = SQLiteDatabase.openDatabase(dbFile.getPath(), null,
                SQLiteDatabase.OPEN_READONLY);
        try (Cursor c = raw.rawQuery("SELECT COUNT(*) FROM `" + table + "`", null)) {
            return c.moveToFirst() ? c.getInt(0) : -1;
        } finally {
            raw.close();
        }
    }

    private boolean rawTableExists(String table) {
        SQLiteDatabase raw = SQLiteDatabase.openDatabase(dbFile.getPath(), null,
                SQLiteDatabase.OPEN_READONLY);
        try (Cursor c = raw.rawQuery(
                "SELECT name FROM sqlite_master WHERE type='table' AND name=?", new String[]{table})) {
            return c.getCount() > 0;
        } finally {
            raw.close();
        }
    }

    private boolean rawColumnExists(String table, String column) {
        SQLiteDatabase raw = SQLiteDatabase.openDatabase(dbFile.getPath(), null,
                SQLiteDatabase.OPEN_READONLY);
        try (Cursor c = raw.rawQuery("PRAGMA table_info(`" + table + "`)", null)) {
            int idx = c.getColumnIndex("name");
            while (c.moveToNext()) {
                if (column.equals(c.getString(idx))) {
                    return true;
                }
            }
            return false;
        } finally {
            raw.close();
        }
    }

    private String scalar(String sql) {
        try (Cursor c = room.getOpenHelper().getWritableDatabase().query(sql)) {
            return c.moveToFirst() ? c.getString(0) : null;
        }
    }

    private long longScalar(String sql) {
        try (Cursor c = room.getOpenHelper().getWritableDatabase().query(sql)) {
            return c.moveToFirst() ? c.getLong(0) : -1;
        }
    }

    // ==================== 清理 ====================

    private static void delete(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                deleteRecursive(c);
            }
        }
        delete(f);
    }
}