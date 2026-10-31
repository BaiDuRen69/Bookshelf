package com.bai.bookshelf.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/**
 * 存量图片一次性压缩（v5.8.20）：v5.8.19 之前相册选图是原样复制字节的，
 * 相机原图 3–5MB 一张，老用户的 filesDir 里躺着一堆巨型封面。
 * 新图在 {@link CoverEditor} 写入时就已经压过，这里只补历史文件，跑一次就记下来。
 *
 * <p>只动应用私有目录下的三个图片目录，逐张交给 {@link ImageCodec}——
 * 它保证「新文件更小且能重新解码」之后才替换原文件，所以中途退出也不会留下坏图。
 */
public final class ImageMigration {

    /** 迁移口径版本：以后要扩目录或改参数就加 1，老用户会再跑一次 */
    public static final int SCHEMA = 1;

    private static final String PREFS = "image_migration";
    private static final String KEY_DONE = "done_schema";
    private static final String[] DIRS = {"covers", "note_images", "excerpt_images"};

    /** 一次迁移的统计（写进 prefs，备份相关页面可查） */
    public static final class Result {
        public boolean ran;
        public int scanned;
        public int shrunk;
        public long savedBytes;
    }

    private ImageMigration() {
    }

    /** 是否已经跑过当前口径（调用方用它决定要不要排队后台任务） */
    public static boolean alreadyDone(Context ctx) {
        return prefs(ctx).getInt(KEY_DONE, 0) >= SCHEMA;
    }

    /** 在后台线程调用：已跑过则直接返回，不做任何事 */
    public static Result runIfNeeded(Context app) {
        Result result = new Result();
        if (alreadyDone(app)) {
            return result;
        }
        for (String dir : DIRS) {
            walk(new File(app.getFilesDir(), dir), result);
        }
        result.ran = true;
        prefs(app).edit()
                .putInt(KEY_DONE, SCHEMA)
                .putInt("shrunk", result.shrunk)
                .putLong("saved_bytes", result.savedBytes)
                .apply();
        return result;
    }

    private static void walk(File dir, Result result) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File file : children) {
            if (file.isDirectory()) {
                walk(file, result);
                continue;
            }
            String name = file.getName();
            if (name.endsWith(".tmp")) {
                continue; // 上一次没清掉的临时文件，不参与也不统计
            }
            result.scanned++;
            if (!ImageCodec.needsShrink(file)) {
                continue;
            }
            long before = file.length();
            if (ImageCodec.shrinkInPlace(file)) {
                result.shrunk++;
                result.savedBytes += Math.max(0, before - file.length());
            }
        }
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
