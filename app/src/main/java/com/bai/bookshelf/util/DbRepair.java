package com.bai.bookshelf.util;

import android.content.Context;
import android.net.Uri;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.AppDatabase;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 数据库打不开时的兜底（v5.8）。
 *
 * <p>Room 的迁移一旦抛异常，之前的表现是「书架空的」或者直接闪退，用户既不知道
 * 发生了什么，也没有自救路径。这里做两件事：把失败原因记下来，并提供两条恢复路线——
 * 写回升级前副本（旧 schema，会重新跑迁移），或者放弃坏库、直接从一个 zip 备份重建。
 *
 * <p>两条路线都会换一个新的 Room 实例：Room 会把第一次打开失败的异常缓存住，
 * 原实例即使文件已经修好也再打不开。
 */
public final class DbRepair {

    private static volatile String broken;

    private DbRepair() {
    }

    /** 启动时打开数据库失败，记下原因 */
    public static void markBroken(Throwable t) {
        broken = describe(t);
    }

    public static boolean isBroken() {
        return broken != null;
    }

    /** 失败原因（给修复页显示，截断到 300 字） */
    public static String message() {
        String m = broken;
        return m == null ? "" : m;
    }

    public static void clear() {
        broken = null;
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

    /** 有没有升级前副本可用来恢复 */
    public static boolean hasPreMigrationCopy(Context ctx) {
        return !PreMigrationBackup.list(ctx).isEmpty();
    }

    /** 最新一份升级前副本的说明；没有副本返回 null */
    public static String latestCopyLabel(Context ctx) {
        List<File> copies = PreMigrationBackup.list(ctx);
        return copies.isEmpty() ? null
                : PreMigrationBackup.describe(copies.get(copies.size() - 1));
    }

    /**
     * 写回最新一份升级前副本并重开数据库。
     *
     * <p>副本是旧 schema，重开时会再跑一遍迁移；如果迁移本身有问题会再次失败，
     * 调用方应提示用户改走 zip 恢复。
     */
    public static void restoreFromPreMigration(Context ctx) throws Exception {
        List<File> copies = PreMigrationBackup.list(ctx);
        if (copies.isEmpty()) {
            throw new IOException("没有可用的升级前副本");
        }
        File latest = copies.get(copies.size() - 1);
        BookshelfApp app = BookshelfApp.get();
        app.closeDatabase();
        PreMigrationBackup.restore(ctx, latest);
        app.reopenDatabase();
        app.db().getOpenHelper().getWritableDatabase();
        clear();
    }

    /**
     * 放弃当前库，从一个 zip 备份重建。
     *
     * <p>坏库改名留在原地（{@code bookshelf.db.broken-时间戳}）而不是删掉，
     * 万一以后还想捞数据还有东西可捞。
     *
     * @return 恢复的书本数
     */
    public static int restoreFromZip(Context ctx, Uri uri) throws Exception {
        BookshelfApp app = BookshelfApp.get();
        app.closeDatabase();
        moveBrokenAside(ctx);
        app.reopenDatabase(); // 建一个干净的空库（v 最新 schema）
        AppDatabase db = app.db();
        db.getOpenHelper().getWritableDatabase();
        ZipRestore.Result r = ZipRestore.restore(ctx, uri, db);
        clear();
        return r.books;
    }

    private static void moveBrokenAside(Context ctx) {
        File db = ctx.getDatabasePath(AppDatabase.DB_NAME);
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            File f = new File(db.getPath() + suffix);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(new File(f.getPath() + ".broken-" + stamp));
            }
        }
    }
}
