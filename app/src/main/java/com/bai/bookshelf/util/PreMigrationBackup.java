package com.bai.bookshelf.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.bai.bookshelf.data.AppDatabase;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * DB 升级前的原始数据库副本（v5.7 数据安全）。
 *
 * <p>Room 的迁移都是 ALTER/UPDATE，正常不会丢数据；但只要迁移脚本出错，App 就打不开书架，
 * 这时唯一的现场还原手段就是这份副本。所以它在<b>冷启动、Room 第一次打开数据库之前</b>把
 * {@code databases/bookshelf.db}（连同 -wal / -shm）整份复制进
 * {@code filesDir/backups/premigration/<时间戳>/}，只保留最近 2 份。
 *
 * <p>它不是 zip 备份：没有图片、schema 还是旧版本，恢复要人工搬文件，
 * 所以定位是「出问题时交给开发者兜底」，日常恢复仍走「我的 → 导入备份」。
 */
public final class PreMigrationBackup {

    private static final String PREFS = "app_prefs";
    private static final String KEY_DB_VERSION = "db_version";
    private static final String DIR = "backups/premigration";
    private static final int MAX_KEEP = 2;

    private PreMigrationBackup() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /**
     * 在 Room 首次打开数据库之前调用。
     *
     * @return 上次的数据库版本；0 = 全新安装或老装机首次跑到本版本（此时也会先留一份副本）
     */
    public static int prepareBeforeOpen(Context ctx) {
        File dbFile = ctx.getDatabasePath(AppDatabase.DB_NAME);
        if (!dbFile.exists()) {
            return 0; // 全新安装：没有东西可保护
        }
        int target = AppDatabase.VERSION;
        SharedPreferences p = prefs(ctx);
        int known = p.getInt(KEY_DB_VERSION, 0);
        if (known == target) {
            return known; // 版本一致，本次启动不迁移
        }
        // known == 0：老装机（本版本才开始记 db_version），既然 DB 文件存在就一律留一份，
        // 并用 -1 表示「刚升级、旧版本未知」，宁可多留一次也不要漏
        copy(ctx, known, target);
        return -1;
    }

    /** Room 打开成功之后调用：记下当前版本，下次启动只在版本变化时再复制 */
    public static void markOpened(Context ctx, int version) {
        prefs(ctx).edit().putInt(KEY_DB_VERSION, version).apply();
    }

    /** 本次启动是否真的发生了 schema 版本变化（用于随后立刻做一次 zip 备份） */
    public static boolean migrated(int oldVersion, int targetVersion) {
        return oldVersion < 0 || (oldVersion > 0 && oldVersion != targetVersion);
    }

    /** 已有副本目录（旧→新） */
    public static List<File> list(Context ctx) {
        File dir = new File(ctx.getFilesDir(), DIR);
        File[] children = dir.listFiles(File::isDirectory);
        if (children == null || children.length == 0) {
            return new ArrayList<>();
        }
        Arrays.sort(children, Comparator.comparing(f -> f.getName()));
        return new ArrayList<>(Arrays.asList(children));
    }

    /** 副本份数（「验证备份」里报告用） */
    public static int count(Context ctx) {
        return list(ctx).size();
    }

    /** 一份副本的说明文字，如 {@code DB 22 → 23 · 2026-10-30 12:00} */
    public static String describe(File copy) {
        return copy.getName().replace('_', ' ');
    }

    /**
     * 把一份升级前副本写回 {@code databases/}（v5.8 数据库修复页）。
     *
     * <p>调用前必须已经 {@code db.close()}：主库和 -wal/-shm 被占用时写进去的是半成品。
     * 副本是旧 schema，写回后把 {@code db_version} 重置成副本的版本，
     * 这样下次打开会重新跑迁移（迁移本身有问题时会再次失败，那就该走 zip 恢复）。
     *
     * @param copy {@link #list(Context)} 返回的副本目录
     * @return 副本对应的旧版本（0 = 目录名里没写清）
     */
    public static int restore(Context ctx, File copy) throws java.io.IOException {
        File src = new File(copy, AppDatabase.DB_NAME);
        if (!src.exists()) {
            throw new java.io.IOException("副本里没有数据库文件");
        }
        File dbFile = ctx.getDatabasePath(AppDatabase.DB_NAME);
        File dir = dbFile.getParentFile();
        if (dir != null && !dir.exists() && !dir.mkdirs()) {
            throw new java.io.IOException("无法创建数据库目录");
        }
        // 先删掉旧的 -wal/-shm：它们是别的库的旁路文件，留着会和写回的库错配
        for (String suffix : new String[]{"-wal", "-shm"}) {
            File f = new File(dbFile.getPath() + suffix);
            if (f.exists()) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
        copyStrict(src, dbFile);
        copyStrict(new File(copy, AppDatabase.DB_NAME + "-wal"),
                new File(dbFile.getPath() + "-wal"));
        copyStrict(new File(copy, AppDatabase.DB_NAME + "-shm"),
                new File(dbFile.getPath() + "-shm"));
        int old = versionOf(copy);
        prefs(ctx).edit().putInt(KEY_DB_VERSION, Math.max(0, old)).apply();
        return old;
    }

    /** 从副本目录名（{@code 20261030_120000_db22_to_23}）里取旧版本 */
    private static int versionOf(File copy) {
        String name = copy.getName();
        int at = name.lastIndexOf("_db");
        if (at < 0) {
            return 0;
        }
        int end = name.indexOf("_to_", at);
        String s = end < 0 ? name.substring(at + 3) : name.substring(at + 3, end);
        if ("?".equals(s)) {
            return 0;
        }
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void copyStrict(File src, File dst) throws java.io.IOException {
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

    private static void copy(Context ctx, int oldVersion, int targetVersion) {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date());
        String from = oldVersion > 0 ? String.valueOf(oldVersion) : "?";
        File target = new File(new File(ctx.getFilesDir(), DIR),
                stamp + "_db" + from + "_to_" + targetVersion);
        if (!target.mkdirs()) {
            return;
        }
        File dbFile = ctx.getDatabasePath(AppDatabase.DB_NAME);
        copyFile(dbFile, new File(target, AppDatabase.DB_NAME));
        // WAL / SHM 必须跟着走，否则副本是个只有旧主库、丢未落盘写入的半成品
        copyFile(new File(dbFile.getPath() + "-wal"),
                new File(target, AppDatabase.DB_NAME + "-wal"));
        copyFile(new File(dbFile.getPath() + "-shm"),
                new File(target, AppDatabase.DB_NAME + "-shm"));
        // 只留最近 MAX_KEEP 份
        List<File> all = list(ctx);
        while (all.size() > MAX_KEEP) {
            deleteRecursive(all.remove(0));
        }
    }

    private static void copyFile(File src, File dst) {
        if (src == null || !src.exists()) {
            return;
        }
        try (InputStream in = new FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        } catch (Exception ignored) {
            // 副本失败不阻断启动：自动备份仍然会跑
        }
    }

    private static void deleteRecursive(File f) {
        File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) {
                c.delete();
            }
        }
        f.delete();
    }
}
