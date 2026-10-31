package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.database.sqlite.SQLiteDatabaseLockedException;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteFullException;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.util.DbFailure;
import com.bai.bookshelf.util.DbRepair;
import com.bai.bookshelf.util.ZipBackup;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 数据库修复流程的真机测试（v5.8.4）。
 *
 * <p>只在 {@code .devtest} 隔离包里跑：测试会故意把库写坏，正式版书库不能遭这个罪
 * （debug 构建带 applicationIdSuffix ".devtest"，与正式版数据完全隔离）。
 *
 * <p>覆盖三条路径：坏库 → 升级前副本恢复、坏库 → zip 重建、异常分流（存储满 / 锁冲突
 * 不进修复页）。
 */
@RunWith(AndroidJUnit4.class)
public class DbRepairInstrumentedTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private File dbFile() {
        return ctx().getDatabasePath(AppDatabase.DB_NAME);
    }

    /** 插一本书，返回插入后应有的总数 */
    private int insertSampleBook() {
        Book b = new Book();
        b.title = "修复流程测试样本";
        b.isbn = "9780000000" + (System.currentTimeMillis() % 10000);
        b.author = "测试";
        b.pages = 120;
        b.listPrice = 39;
        b.purchasePrice = 29;
        b.createdAt = System.currentTimeMillis();
        b.updatedAt = b.createdAt;
        BookshelfApp.get().db().bookDao().insert(b);
        return BookshelfApp.get().db().bookDao().count();
    }

    /** 把主库写成全 0（头不是 SQLite），并清掉 -wal/-shm */
    private void corruptDatabase() throws Exception {
        BookshelfApp.get().closeDatabase();
        //noinspection ResultOfMethodCallIgnored
        new File(dbFile().getPath() + "-wal").delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dbFile().getPath() + "-shm").delete();
        try (FileOutputStream out = new FileOutputStream(dbFile())) {
            out.write(new byte[4096]);
        }
    }

    private static void copy(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }

    /** 造一份升级前副本（与 PreMigrationBackup 的目录格式一致） */
    private void makePreMigrationCopy() throws Exception {
        BookshelfApp.get().closeDatabase();
        // 先清掉应用自己留下的旧副本，避免恢复时拿到更早的一份（测试要的是刚拷的）
        for (File old : com.bai.bookshelf.util.PreMigrationBackup.list(ctx())) {
            File[] children = old.listFiles();
            if (children != null) {
                for (File c : children) {
                    //noinspection ResultOfMethodCallIgnored
                    c.delete();
                }
            }
            //noinspection ResultOfMethodCallIgnored
            old.delete();
        }
        File dir = new File(ctx().getFilesDir(),
                "backups/premigration/20990101_000000_db" + AppDatabase.VERSION
                        + "_to_" + AppDatabase.VERSION);
        assertTrue(dir.mkdirs() || dir.isDirectory());
        copy(dbFile(), new File(dir, AppDatabase.DB_NAME));
        File wal = new File(dbFile().getPath() + "-wal");
        if (wal.exists()) {
            copy(wal, new File(dir, AppDatabase.DB_NAME + "-wal"));
        }
        BookshelfApp.get().reopenDatabase();
        BookshelfApp.get().markDbReady();
    }

    @Test
    public void 坏库可以用升级前副本恢复() throws Exception {
        int expected = insertSampleBook();
        makePreMigrationCopy();
        corruptDatabase();

        DbRepair.restoreFromPreMigration(ctx());

        assertFalse(DbRepair.isBroken());
        assertEquals("恢复后藏书数要与副本一致",
                expected, BookshelfApp.get().db().bookDao().count());
        assertTrue("旧库应改名为 .broken-* 留档",
                !DbRepair.listBrokenFiles(ctx()).isEmpty());
    }

    @Test
    public void 坏库可以用zip备份重建() throws Exception {
        int expected = insertSampleBook();
        ZipBackup.Bundle bundle = ZipBackup.create(ctx());
        File zip = new File(ctx().getCacheDir(), "repair_test.zip");
        try (FileOutputStream out = new FileOutputStream(zip)) {
            out.write(bundle.zip);
        }
        corruptDatabase();

        int restored = DbRepair.restoreFromZip(ctx(), Uri.fromFile(zip));

        assertEquals("zip 里有多少本就恢复多少本", expected, restored);
        assertEquals(expected, BookshelfApp.get().db().bookDao().count());
        assertNotNull("坏库原件要留在 .broken-* 里",
                DbRepair.lastBrokenFile(ctx()));
    }

    @Test
    public void 年度目标跨年继承而不是重置成默认值() {
        android.content.SharedPreferences p = ctx().getSharedPreferences("ui_prefs", 0);
        int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        // 模拟去年设了 50 种
        p.edit().putInt("reading_goal_year", year - 1)
                .putInt("reading_goal", 50)
                .putBoolean("reading_goal_set", true)
                .remove("reading_goal_prompt_year").apply();

        assertEquals("跨年要沿用去年的 50", 50, com.bai.bookshelf.util.ReadingGoal.current(ctx()));
        assertEquals("年份要滚到今年", year, p.getInt("reading_goal_year", 0));
        // 1 月才问「还按 50 吗」；其他月份不打扰
        int month = java.util.Calendar.getInstance().get(java.util.Calendar.MONTH);
        assertEquals(month == java.util.Calendar.JANUARY,
                com.bai.bookshelf.util.ReadingGoal.needsJanuaryPrompt(ctx()));
        com.bai.bookshelf.util.ReadingGoal.markPrompted(ctx());
        assertFalse("问过就不再问", com.bai.bookshelf.util.ReadingGoal.needsJanuaryPrompt(ctx()));
    }

    @Test
    public void 存储满和锁冲突不当作库损坏() {
        assertTrue(DbFailure.isDatabaseBroken(new SQLiteException("no such table: books")));
        assertTrue(DbFailure.isDatabaseBroken(
                new SQLiteException("database disk image is malformed")));
        assertTrue(DbFailure.isDatabaseBroken(new SQLiteException(
                "Migration didn't properly handle: books(androidx.room.Entity)")));

        SQLiteFullException full = new SQLiteFullException("database or disk is full");
        SQLiteDatabaseLockedException locked =
                new SQLiteDatabaseLockedException("database is locked");
        assertFalse("磁盘满不是库坏了", DbFailure.isDatabaseBroken(full));
        assertFalse("锁冲突不是库坏了", DbFailure.isDatabaseBroken(locked));
        assertEquals(DbFailure.MSG_FULL, DbFailure.userMessage(full));
        assertEquals(DbFailure.MSG_LOCKED, DbFailure.userMessage(locked));
    }
}
