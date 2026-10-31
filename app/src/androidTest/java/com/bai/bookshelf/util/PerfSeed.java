package com.bai.bookshelf.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

import com.bai.bookshelf.data.entity.Book;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * 性能测试共用的假书库（v5.8.21）：同一批封面文件 + 同一份 Book 列表，
 * 改前改后跑的都是这一份，数字才叫同源对比。
 *
 * <p>封面按 v5.8.20 之后的真实形态造：长边 1080、JPEG 85，单张几十到两百 KB。
 * 生成一次后缓存在 cacheDir/perf_lib，重复跑不重建（也保证两轮之间字节完全一致）。
 */
final class PerfSeed {

    static final int BOOKS = 600;
    static final int COVER_W = 1080;
    static final int COVER_H = 1620;

    private PerfSeed() {
    }

    /** 缓存目录：perf_lib/covers/cover_i.jpg + 一份标记文件 */
    static File dir(Context ctx) {
        return new File(ctx.getCacheDir(), "perf_lib");
    }

    /** 造（或复用）假封面，返回文件列表；封面数等于 BOOKS */
    static List<File> covers(Context ctx) throws Exception {
        File root = dir(ctx);
        File coverDir = new File(root, "covers");
        File marker = new File(root, "SEALED_v2_" + BOOKS);
        File[] existing = coverDir.listFiles((d, name) -> name.endsWith(".jpg"));
        if (marker.exists() && existing != null && existing.length == BOOKS) {
            List<File> list = new ArrayList<>();
            for (int i = 0; i < BOOKS; i++) {
                list.add(new File(coverDir, "cover_" + i + ".jpg"));
            }
            return list;
        }
        //noinspection ResultOfMethodCallIgnored
        coverDir.mkdirs();
        long t0 = System.currentTimeMillis();
        List<File> out = new ArrayList<>();
        for (int i = 0; i < BOOKS; i++) {
            File f = new File(coverDir, "cover_" + i + ".jpg");
            writeCover(f, i);
            out.add(f);
        }
        //noinspection ResultOfMethodCallIgnored
        marker.createNewFile();
        android.util.Log.i("PerfSeed", "封面生成 " + out.size() + " 张 / "
                + (System.currentTimeMillis() - t0) + " ms");
        return out;
    }

    /** 一张「书封」：底色 + 色块 + 文字 + 随机噪点，压出来接近真实封面大小（纯噪声会大十几倍） */
    private static void writeCover(File f, int i) throws Exception {
        Bitmap bmp = Bitmap.createBitmap(COVER_W, COVER_H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.rgb(20 + i % 60, 90 + (i * 7) % 120, 140 - (i * 3) % 100));
        c.drawRect(0, 0, COVER_W, COVER_H, p);
        p.setColor(Color.WHITE);
        p.setTextSize(96);
        c.drawText("藏书 " + i, 60, 260, p);
        p.setColor(Color.argb(90, 255, 255, 255));
        for (int r = 0; r < 9; r++) {
            c.drawRoundRect(new RectF(60, 500 + r * 110, COVER_W - 60, 590 + r * 110),
                    18, 18, p);
        }
        // 噪点：JPEG 压不掉的高频信息，一张封面从「几乎透明」变成真实相机量级
        java.util.Random rnd = new java.util.Random(i * 31L + 7);
        p.setAntiAlias(false);
        for (int n = 0; n < 9000; n++) {
            p.setColor(Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)));
            int x = rnd.nextInt(COVER_W);
            int y = rnd.nextInt(COVER_H);
            c.drawRect(x, y, x + 14, y + 14, p);
        }
        try (FileOutputStream fos = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
        }
        bmp.recycle();
    }

    /** 与封面一一对应的藏书（coverUrl 指向假文件，其它字段够用即可） */
    static List<Book> books(List<File> files) {
        List<Book> list = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (int i = 0; i < files.size(); i++) {
            Book b = new Book();
            b.id = i + 1;
            b.title = "测试藏书" + i;
            b.author = "作者" + (i % 37);
            b.publisher = "测试出版社" + (i % 11);
            b.isbn = String.format(java.util.Locale.US, "97871%08d", i);
            b.pages = 200 + (i % 9) * 45;
            b.rating = i % 5;
            b.coverUrl = files.get(i).getAbsolutePath();
            b.createdAt = now - i * 1000L;
            b.updatedAt = now;
            list.add(b);
        }
        return list;
    }

    /** 这批封面的未压缩字节总数 */
    static long coverBytes(List<File> files) {
        long sum = 0;
        for (File f : files) {
            sum += f.length();
        }
        return sum;
    }

    static void deleteRecursively(File target) {
        if (target == null || !target.exists()) {
            return;
        }
        File[] children = target.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleteRecursively(child);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    child.delete();
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        target.delete();
    }
}
