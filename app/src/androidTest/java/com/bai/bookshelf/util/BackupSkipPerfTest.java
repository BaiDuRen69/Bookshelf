package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 自动备份前置指纹的账（v5.8.21 评审 #7）：同一批 600 张封面，
 * 旧路径每天启动都要「读全部图片 + SHA-256 + 压成 zip」才知道内容没变，
 * 新路径先算一次指纹（stat + 行数）决定要不要打包。
 *
 * <p>行数按 1000 本规模量：往临时库 perf_fp.db 里灌 1000 本再测，测完删库，
 * 不碰主库。结果写 cacheDir/backup_skip_perf.txt 并打 logcat（TAG=BackupSkip）。
 */
@RunWith(AndroidJUnit4.class)
public class BackupSkipPerfTest {

    private static final String TAG = "BackupSkip";
    private static final String TMP_DB = "perf_fp.db";

    private final StringBuilder report = new StringBuilder();

    private void raw(String s) {
        report.append(s).append('\n');
    }

    /** 指向假封面的媒体条目（与真实备份一样从文件流式读） */
    private static final class SeedItem implements ZipBackup.MediaItem {
        private final String name;
        private final File file;

        SeedItem(String name, File file) {
            this.name = name;
            this.file = file;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public java.io.InputStream open() throws java.io.IOException {
            return new java.io.FileInputStream(file);
        }
    }

    @Test
    public void 打包与指纹的成本对比() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        List<File> seed = PerfSeed.covers(ctx);
        long mediaBytes = PerfSeed.coverBytes(seed);

        // 把同一批封面放进 filesDir/covers（备份真正会走到的位置），测完清掉
        File coverDir = new File(ctx.getFilesDir(), "covers");
        //noinspection ResultOfMethodCallIgnored
        coverDir.mkdirs();
        List<File> placed = new ArrayList<>();
        for (int i = 0; i < seed.size(); i++) {
            File dst = new File(coverDir, "perf_probe_" + i + ".jpg");
            copy(seed.get(i), dst);
            placed.add(dst);
        }
        AppDatabase db = Room.databaseBuilder(ctx, AppDatabase.class, TMP_DB).build();
        File zip = new File(ctx.getCacheDir(), "perf_pack.zip");
        try {
            int books = seedDb(db);
            raw(String.format(Locale.US, "样本：%d 本 / %d 张封面共 %.1f MB",
                    books, placed.size(), mediaBytes / 1048576.0));

            long packMs = pack(ctx, placed, zip);
            raw(String.format(Locale.US, "打一次整包：%d ms，产出 %.1f MB",
                    packMs, zip.length() / 1048576.0));

            long fpMs = fingerprint(db, ctx);
            raw(String.format(Locale.US, "算一次指纹：%d ms", fpMs));

            // 语义护栏：稳、敏感、丢了不瞎跳
            String a = BackupFingerprint.of(ctx, db);
            String b = BackupFingerprint.of(ctx, db);
            assertNotNull("指纹算不出来（那自动备份会照常打包）", a);
            assertEquals("同样状态两次指纹不一致，等于永远跳不过", a, b);
            File probe = new File(coverDir, "perf_probe_0.jpg");
            long m0 = probe.lastModified();
            Thread.sleep(30);
            probe.setLastModified(m0 + 2000); // 只动时间戳：模拟「图被改过」
            assertNotEquals("改了封面时间戳指纹没变", a, BackupFingerprint.of(ctx, db));
            probe.setLastModified(m0);
            assertNotNull("没有库时也要能只按文件算", BackupFingerprint.of(ctx, null));
        } finally {
            for (File f : placed) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
            zip.delete();
            db.close();
            ctx.deleteDatabase(TMP_DB);
            for (String suffix : new String[]{"", "-wal", "-shm", ".lck"}) {
                File f = suffix.equals(".lck")
                        ? new File(ctx.getCacheDir(), TMP_DB + suffix)
                        : ctx.getDatabasePath(TMP_DB + suffix);
                if (f.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    f.delete(); // Room 的锁文件落在 cacheDir，deleteDatabase 管不到
                }
            }
            File[] left = coverDir.listFiles();
            if (left != null && left.length == 0) {
                //noinspection ResultOfMethodCallIgnored
                coverDir.delete();
            }
            try (FileOutputStream fos = new FileOutputStream(
                    new File(ctx.getCacheDir(), "backup_skip_perf.txt"))) {
                fos.write(report.toString().getBytes(StandardCharsets.UTF_8));
            }
            android.util.Log.i(TAG, report.toString());
        }
    }

    /** 灌 1000 本 + 副本/摘抄/借还/备注图，让每条 COUNT 都走真实规模 */
    private int seedDb(AppDatabase db) {
        if (db.bookDao().count() >= 1000) {
            return (int) db.bookDao().count();
        }
        long now = System.currentTimeMillis();
        List<com.bai.bookshelf.data.entity.BookCopy> copies = new ArrayList<>();
        List<com.bai.bookshelf.data.entity.Excerpt> excerpts = new ArrayList<>();
        List<com.bai.bookshelf.data.entity.BorrowRecord> loans = new ArrayList<>();
        List<com.bai.bookshelf.data.entity.NoteImage> images = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            Book b = new Book();
            b.title = "指纹库" + i;
            b.author = "作者" + (i % 23);
            b.isbn = String.format(Locale.US, "97872%08d", i);
            b.pages = 200 + i % 300;
            b.createdAt = now - i * 1000L;
            b.updatedAt = now;
            long id = db.bookDao().insert(b);
            com.bai.bookshelf.data.entity.BookCopy copy = new com.bai.bookshelf.data.entity.BookCopy();
            copy.bookId = id;
            copy.copyIndex = 1;
            copies.add(copy);
            com.bai.bookshelf.data.entity.Excerpt e = new com.bai.bookshelf.data.entity.Excerpt();
            e.bookId = id;
            e.content = "摘抄若干";
            e.createdAt = now;
            excerpts.add(e);
            com.bai.bookshelf.data.entity.BorrowRecord r =
                    new com.bai.bookshelf.data.entity.BorrowRecord();
            r.bookId = id;
            r.borrower = "借阅人" + (i % 11);
            r.lendDate = "2026-09-01";
            r.dueDate = "2026-09-20";
            r.status = com.bai.bookshelf.data.entity.BorrowRecord.STATUS_OUT;
            loans.add(r);
            com.bai.bookshelf.data.entity.NoteImage img =
                    new com.bai.bookshelf.data.entity.NoteImage();
            img.bookId = id;
            img.ownerKey = "book:" + id;
            img.path = "covers/perf_probe_" + (i % 600) + ".jpg";
            images.add(img);
        }
        db.bookCopyDao().insertAll(copies);
        db.noteImageDao().insertAll(images);
        for (com.bai.bookshelf.data.entity.Excerpt e : excerpts) {
            db.excerptDao().insert(e);
        }
        for (com.bai.bookshelf.data.entity.BorrowRecord r : loans) {
            db.borrowRecordDao().insert(r);
        }
        return (int) db.bookDao().count();
    }

    /** 旧路径：读全部图片 + 摘要 + 压缩写盘 */
    private long pack(Context ctx, List<File> files, File zip) throws Exception {
        List<ZipBackup.MediaItem> media = new ArrayList<>();
        for (int i = 0; i < files.size(); i++) {
            media.add(new SeedItem(BackupFormat.MEDIA_PREFIX + "covers/perf_probe_" + i + ".jpg",
                    files.get(i)));
        }
        byte[] dataJson = ("{\"books\":[" + repeat("{\"id\":1,\"title\":\"书\"}", 4000)
                + "]}").getBytes(StandardCharsets.UTF_8);
        BackupMeta meta = new BackupMeta();
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 2; run++) {
            long t0 = System.nanoTime();
            try (FileOutputStream fos = new FileOutputStream(zip)) {
                ZipBackup.writeZip(fos, dataJson, media, meta);
            }
            best = Math.min(best, (System.nanoTime() - t0) / 1_000_000);
        }
        assertTrue("校验和没算出来", meta.checksum != null && meta.checksum.length() == 64);
        assertEquals("媒体条目数对不上", media.size(), meta.media);
        return best;
    }

    /** 新路径：库文件 stat + 各表行数 + 图片目录摘要 */
    private long fingerprint(AppDatabase db, Context ctx) {
        BackupFingerprint.of(ctx, db); // 预热（首跑含 JIT / 目录缓存冷）
        long best = Long.MAX_VALUE;
        for (int run = 0; run < 5; run++) {
            long t0 = System.nanoTime();
            assertNotNull(BackupFingerprint.of(ctx, db));
            best = Math.min(best, (System.nanoTime() - t0) / 1_000_000);
        }
        return best;
    }

    private static String repeat(String unit, int times) {
        StringBuilder sb = new StringBuilder(unit.length() * times);
        for (int i = 0; i < times; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(unit);
        }
        return sb.toString();
    }

    private static void copy(File src, File dst) throws Exception {
        try (java.io.InputStream in = new java.io.FileInputStream(src);
             FileOutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
        }
    }
}
