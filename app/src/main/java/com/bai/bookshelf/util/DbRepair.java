package com.bai.bookshelf.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.AppDatabase;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 数据库打不开时的兜底（v5.8）。
 *
 * <p>恢复流程（v5.8.3 起）：<b>校验副本 → 写临时库 → 关旧库 → 原子换文件 → 重开</b>。
 * 只有「临时库完整写进应用目录」之后才动旧库；换文件失败会把旧库原样换回来，
 * 也就是说恢复失败时用户手里的库还是原来那个，不会出现「修到一半两头空」。
 *
 * <p>换文件期间在 SharedPreferences 里留一条 {@code repair_pending} 标记：万一进程
 * 在这个窗口被杀，下次启动看到标记直接进修复页，而不是带着半个库继续跑。
 *
 * <p>被换下来的坏库不删，改名成 {@code bookshelf.db.broken-时间戳} 留在 databases/ 里，
 * 「备份文件管理」可以看到并导出，万一以后还想捞数据。
 */
public final class DbRepair {

    private static final String TAG = "DbRepair";

    private static final String PREFS = "repair_prefs";
    private static final String KEY_PENDING = "repair_pending";
    private static final String KEY_REASON = "repair_reason";
    private static final String KEY_BROKEN_PATH = "broken_db_path";
    /** 被换下来的库文件名前缀 */
    public static final String BROKEN_SUFFIX = ".broken-";

    private static volatile String broken;
    private static volatile boolean repairing;

    private DbRepair() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ==================== 状态 ====================

    /** 启动时打开数据库失败，记下原因（内存，供修复页显示） */
    public static void markBroken(Throwable t) {
        broken = describe(t);
    }

    public static boolean isBroken() {
        return broken != null;
    }

    /** 正在执行恢复：这段时间后台查询还会陆续抛异常，全部按预期吞掉 */
    public static boolean isRepairing() {
        return repairing;
    }

    /** 上次恢复没走完（换文件过程中进程被杀），启动时要直接进修复页 */
    public static boolean isPending(Context ctx) {
        return prefs(ctx).getBoolean(KEY_PENDING, false);
    }

    public static String pendingReason(Context ctx) {
        return prefs(ctx).getString(KEY_REASON, "");
    }

    /** 下次启动要不要进修复页；进入修复页时把原因读出来 */
    public static void restoreBrokenState(Context ctx) {
        if (isPending(ctx)) {
            String reason = pendingReason(ctx);
            broken = reason.isEmpty() ? "上次恢复数据库没有完成" : reason;
        }
    }

    /** 失败原因（给修复页显示，截断到 300 字） */
    public static String message() {
        String m = broken;
        return m == null ? "" : m;
    }

    public static void clear(Context ctx) {
        broken = null;
        prefs(ctx).edit().remove(KEY_PENDING).remove(KEY_REASON).apply();
    }

    private static void markPending(Context ctx, String reason) {
        prefs(ctx).edit().putBoolean(KEY_PENDING, true)
                .putString(KEY_REASON, reason == null ? "" : reason).apply();
    }

    /** 迁移失败时最有用的是「哪个迁移 + 哪张表」，整段堆栈反而没人看 */
    public static String describe(Throwable t) {
        if (t == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(t.getClass().getSimpleName());
        if (t.getMessage() != null) {
            sb.append("：").append(t.getMessage());
        }
        Throwable cause = t.getCause();
        int depth = 0;
        while (cause != null && cause != t && depth++ < 3) {
            sb.append("\n← ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null) {
                sb.append("：").append(cause.getMessage());
            }
            cause = cause.getCause();
        }
        String s = sb.toString();
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }

    // ==================== 副本 / 坏库 ====================

    public static boolean hasPreMigrationCopy(Context ctx) {
        return !PreMigrationBackup.list(ctx).isEmpty();
    }

    public static String latestCopyLabel(Context ctx) {
        List<File> copies = PreMigrationBackup.list(ctx);
        return copies.isEmpty() ? null
                : PreMigrationBackup.describe(copies.get(copies.size() - 1));
    }

    /** 被换下来的坏库（新 → 旧） */
    public static List<File> listBrokenFiles(Context ctx) {
        File dir = ctx.getDatabasePath(AppDatabase.DB_NAME).getParentFile();
        List<File> out = new ArrayList<>();
        if (dir == null) {
            return out;
        }
        File[] all = dir.listFiles((d, name) -> name.startsWith(AppDatabase.DB_NAME + BROKEN_SUFFIX));
        if (all != null) {
            Arrays.sort(all, Comparator.comparingLong(File::lastModified).reversed());
            out.addAll(Arrays.asList(all));
        }
        return out;
    }

    public static File lastBrokenFile(Context ctx) {
        String p = prefs(ctx).getString(KEY_BROKEN_PATH, null);
        if (p == null) {
            return null;
        }
        File f = new File(p);
        return f.exists() ? f : null;
    }

    // ==================== 恢复 ====================

    /**
     * 写回最新一份升级前副本：校验 → 写临时库 → 关旧库 → 原子换文件 → 重开。
     *
     * @throws Exception 任何一步失败都不会把正在用的库换掉（换过之后失败会回滚）
     */
    public static void restoreFromPreMigration(Context ctx) throws Exception {
        List<File> copies = PreMigrationBackup.list(ctx);
        if (copies.isEmpty()) {
            Log.w(TAG, "没有可用的升级前副本，恢复作罢（当前库没被动过）");
            throw new IOException("没有可用的升级前副本");
        }
        File copyDir = copies.get(copies.size() - 1);
        File srcDb = new File(copyDir, AppDatabase.DB_NAME);
        Log.i(TAG, "准备从升级前副本恢复：" + copyDir.getName());
        // 1. 校验：SQLite 头不对的副本直接拒绝，不浪费后面的步骤
        validateDatabaseFile(srcDb);

        File dbFile = ctx.getDatabasePath(AppDatabase.DB_NAME);
        File tmp = new File(dbFile.getPath() + ".restoring");
        try {
            // 2. 写临时库（同目录，后面 rename 才是原子的）
            deleteDbFiles(tmp);
            copyFile(srcDb, tmp);
            copyFile(new File(copyDir, AppDatabase.DB_NAME + "-wal"),
                    new File(tmp.getPath() + "-wal"));
            copyFile(new File(copyDir, AppDatabase.DB_NAME + "-shm"),
                    new File(tmp.getPath() + "-shm"));
            if (tmp.length() != srcDb.length()) {
                Log.w(TAG, "临时库写入不完整：" + tmp.length() + " / " + srcDb.length()
                        + " 字节，旧库保持原样");
                throw new IOException("临时库写入不完整（" + tmp.length() + " / " + srcDb.length() + " 字节）");
            }
            // 3. 关旧库 → 换文件（失败回滚）
            swapIn(ctx, dbFile, tmp);
            Log.i(TAG, "升级前副本已换入，数据库重开完成");
            BackupManager.recordRestoreResult(ctx, true, "从升级前副本恢复：" + copyDir.getName());
        } finally {
            deleteDbFiles(tmp);
        }
    }

    /**
     * 放弃当前库，从一个 zip 备份重建。
     *
     * <p>先整包校验（读进内存比对 SHA-256），校验不过一个字节都不动；通过后才把旧库换下。
     *
     * @return 恢复的书本数
     */
    public static int restoreFromZip(Context ctx, Uri uri) throws Exception {
        // 1. 校验：不合格直接抛，旧库原样不动
        BackupInspector.Info info = BackupInspector.inspect(ctx, uri);
        if (!info.ok()) {
            Log.w(TAG, "zip 校验没过，旧库一个字节没动：" + info.reject);
            throw new IOException(info.reject);
        }
        BookshelfApp app = BookshelfApp.get();
        File dbFile = ctx.getDatabasePath(AppDatabase.DB_NAME);
        File stamp = brokenTarget(dbFile);
        markPending(ctx, "正在从 zip 备份重建数据库");
        repairing = true;
        try {
            app.closeDatabase();
            moveDbFiles(dbFile, stamp);
            prefs(ctx).edit().putString(KEY_BROKEN_PATH, stamp.getPath()).apply();
            app.reopenDatabase(); // 建一个干净的新库（最新 schema）
            app.db().getOpenHelper().getWritableDatabase();
            app.markDbReady();
            ZipRestore.Result r = ZipRestore.restore(ctx, uri, app.db());
            clear(ctx);
            BackupManager.recordRestoreResult(ctx, true,
                    "从 zip 重建：" + r.books + " 种 · 图片 " + r.media + " 张");
            return r.books;
        } catch (Throwable t) {
            Log.w(TAG, "从 zip 重建失败，准备回滚到旧库（旧库在 " + stamp.getName() + "）", t);
            rollback(app, dbFile, stamp);
            BackupManager.recordRestoreResult(ctx, false, "从 zip 重建失败：" + describe(t));
            throw t instanceof Exception ? (Exception) t : new IOException(describe(t));
        } finally {
            repairing = false;
        }
    }

    /** 关库 → 旧库改名 → 临时库换进来 → 重开；中途失败回滚 */
    private static void swapIn(Context ctx, File dbFile, File tmp) throws Exception {
        BookshelfApp app = BookshelfApp.get();
        File brokenTarget = brokenTarget(dbFile);
        markPending(ctx, message().isEmpty() ? "正在从升级前副本恢复" : message());
        repairing = true;
        boolean swapped = false;
        try {
            app.closeDatabase();
            moveDbFiles(dbFile, brokenTarget);
            prefs(ctx).edit().putString(KEY_BROKEN_PATH, brokenTarget.getPath()).apply();
            if (!tmp.renameTo(dbFile)) {
                throw new IOException("无法把临时库写进数据库目录");
            }
            swapped = true;
            renameSidecars(tmp, dbFile);
            app.reopenDatabase();
            app.db().getOpenHelper().getWritableDatabase();
            app.markDbReady();
            clear(ctx);
        } catch (Throwable t) {
            Log.w(TAG, "换入临时库失败，准备回滚（旧库在 " + brokenTarget.getName() + "）", t);
            rollback(app, dbFile, brokenTarget);
            throw t instanceof Exception ? (Exception) t : new IOException(describe(t));
        } finally {
            repairing = false;
            if (!swapped) {
                deleteDbFiles(tmp);
            }
        }
    }

    /** 把旧库换回来并重开；尽力而为，失败只记日志 */
    private static void rollback(BookshelfApp app, File dbFile, File brokenTarget) {
        try {
            app.closeDatabase();
            // 只有「原库确实被挪走」过才需要换回来；否则 dbFile 还是原库，
            // 这里一删就是真丢数据（早前版本在这里踩过坑）
            if (brokenTarget.exists()) {
                deleteDbFiles(dbFile);
                moveDbFiles(brokenTarget, dbFile);
            }
            app.reopenDatabase();
        } catch (Throwable t) {
            // 回滚都失败就只能等下次启动进修复页（repair_pending 还留着）
            Log.w(TAG, "回滚旧库失败：库里可能既不是新库也不是原库，下次启动会直接进修复页", t);
        }
    }

    // ==================== 文件工具 ====================

    private static File brokenTarget(File dbFile) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        return new File(dbFile.getPath() + BROKEN_SUFFIX + stamp);
    }

    /** 旧库连同 -wal/-shm 一起改名保留（不删） */
    private static void moveDbFiles(File from, File to) {
        rename(from, to);
        rename(new File(from.getPath() + "-wal"), new File(to.getPath() + "-wal"));
        rename(new File(from.getPath() + "-shm"), new File(to.getPath() + "-shm"));
    }

    private static void renameSidecars(File tmp, File dbFile) {
        rename(new File(tmp.getPath() + "-wal"), new File(dbFile.getPath() + "-wal"));
        rename(new File(tmp.getPath() + "-shm"), new File(dbFile.getPath() + "-shm"));
    }

    private static void rename(File from, File to) {
        if (from.exists()) {
            //noinspection ResultOfMethodCallIgnored
            from.renameTo(to);
        }
    }

    private static void deleteDbFiles(File base) {
        deleteQuietly(base);
        deleteQuietly(new File(base.getPath() + "-wal"));
        deleteQuietly(new File(base.getPath() + "-shm"));
    }

    private static void deleteQuietly(File f) {
        if (f != null && f.exists()) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    /**
     * 只认 SQLite 3 的文件头："SQLite format 3\0"。
     *
     * <p>不做完整的完整性检查（那需要打开库，而这时我们正想绕开 Room），
     * 但足以挡掉「副本压根没拷完」「复制成了 0 字节」这类低级错误。
     */
    private static void validateDatabaseFile(File f) throws IOException {
        if (!f.exists() || f.length() < 100) {
            throw new IOException("副本文件不存在或过小");
        }
        byte[] head = new byte[16];
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            raf.readFully(head);
        }
        String magic = new String(head, java.nio.charset.StandardCharsets.US_ASCII);
        if (!magic.startsWith("SQLite format 3")) {
            throw new IOException("副本不是有效的 SQLite 数据库");
        }
    }

    private static void copyFile(File src, File dst) throws IOException {
        if (src == null || !src.exists()) {
            return;
        }
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        }
    }
}
