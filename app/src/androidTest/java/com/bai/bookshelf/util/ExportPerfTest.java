package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Excel 导出的内存/耗时基线（v5.8.21 评审 #6）：
 * 旧路径把全部封面压成 JPEG 收进 {@code List<CoverImage>} 后才开 zip，
 * 新路径边压边落临时文件、写包时逐张流进去。这条测试给出改前改后的同一份账。
 *
 * <p>指标：整程耗时、导出期间的堆峰值增量、产物字节数、实际嵌入的封面数。
 * 结果写 cacheDir/export_perf.txt 并打 logcat（TAG=ExportPerf）。
 */
@RunWith(AndroidJUnit4.class)
public class ExportPerfTest {

    private static final String TAG = "ExportPerf";

    private final StringBuilder report = new StringBuilder();

    private void raw(String s) {
        report.append(s).append('\n');
    }

    @Test
    public void 六百本带封面导出() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        List<File> files = PerfSeed.covers(ctx);
        List<Book> books = PerfSeed.books(files);
        long seedBytes = PerfSeed.coverBytes(files);
        raw(String.format(Locale.US, "样本：%d 本，封面源文件共 %.1f MB（均 %.0f KB）",
                books.size(), seedBytes / 1048576.0, seedBytes / 1024.0 / books.size()));

        // 图片按导出管线（长边 200、JPEG 85）重压一遍，得到「旧路径必须同时驻留」的量
        long resident = compressedSum(files);
        raw(String.format(Locale.US, "压缩后单张均 %.1f KB → 全量驻留需 %.1f MB",
                resident / 1024.0 / books.size(), resident / 1048576.0));

        Map<Long, String> locNames = new HashMap<>();
        File out = new File(ctx.getCacheDir(), "perf_export.xlsx");
        // 底线：同样 600 行但一张封面都不给，剩下的开销与封面无关
        List<Book> bare = PerfSeed.books(files);
        for (Book b : bare) {
            b.coverUrl = "";
        }
        run(ctx, bare, locNames, new File(ctx.getCacheDir(), "perf_export_bare.xlsx"),
                "无封面底线", 30_000);
        run(ctx, books, locNames, out, "带封面导出", 500_000);
        run(ctx, books, locNames, out, "第 2 次（缓存已热）", 500_000);

        // 回归护栏：封面一张都不能少，包结构不能坏（改的是「图从哪来」，不是「包长什么样」）
        File again = new File(ctx.getCacheDir(), "perf_check.xlsx");
        int count;
        try (FileOutputStream fos = new FileOutputStream(again)) {
            count = ExcelExporter.exportToStream(ctx, fos, books, locNames);
        }
        assertEquals("导出嵌入的封面数变了", books.size(), count);
        raw("包结构校验：" + verifyXlsx(again, books.size()));
        again.delete();

        try (FileOutputStream fos = new FileOutputStream(
                new File(ctx.getCacheDir(), "export_perf.txt"))) {
            fos.write(report.toString().getBytes("UTF-8"));
        }
        android.util.Log.i(TAG, report.toString());
    }

    /** 跑一次导出，同时用另一根线程采样堆峰值 */
    private void run(Context ctx, List<Book> books, Map<Long, String> locNames,
                     File out, String label, int minOutBytes) throws Exception {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        Thread.sleep(150);
        long baseline = rt.totalMemory() - rt.freeMemory();
        AtomicBoolean go = new AtomicBoolean(true);
        AtomicLong peak = new AtomicLong(baseline);
        Thread sampler = new Thread(() -> {
            Runtime r = Runtime.getRuntime();
            while (go.get()) {
                long used = r.totalMemory() - r.freeMemory();
                long cur = peak.get();
                if (used > cur && peak.compareAndSet(cur, used)) {
                    continue;
                }
                try {
                    Thread.sleep(2);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        sampler.start();
        long t0 = System.nanoTime();
        int images;
        try (FileOutputStream fos = new FileOutputStream(out)) {
            images = ExcelExporter.exportToStream(ctx, fos, books, locNames);
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        go.set(false);
        sampler.join();
        long peakDelta = peak.get() - baseline;
        raw(String.format(Locale.US, "%s：%d ms，嵌入 %d 张，产物 %.1f MB，堆峰值 +%.1f MB",
                label, ms, images, out.length() / 1048576.0, peakDelta / 1048576.0));
        assertTrue("导出耗时异常：" + ms, ms < 180_000);
        assertTrue("产物太小（" + out.length() + "），行没写进去", out.length() > minOutBytes);
    }

    /**
     * 校验产物包结构：必需部件齐全、媒体条目 1..N 连续无缺、首尾两张能解码、
     * drawing1.xml 里的图片锚点数与嵌入张数一致。
     *
     * @return 一行结论，写进报告
     */
    private String verifyXlsx(File xlsx, int expectImages) throws Exception {
        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(xlsx);
        try {
            for (String need : new String[]{"[Content_Types].xml", "xl/workbook.xml",
                    "xl/worksheets/sheet1.xml", "xl/drawings/drawing1.xml",
                    "xl/drawings/_rels/drawing1.xml.rels"}) {
                assertNotNull("缺部件 " + need, zip.getEntry(need));
            }
            java.util.Set<String> media = new java.util.HashSet<>();
            java.util.Enumeration<? extends java.util.zip.ZipEntry> en = zip.entries();
            while (en.hasMoreElements()) {
                String name = en.nextElement().getName();
                if (name.startsWith("xl/media/image")) {
                    media.add(name);
                }
            }
            assertEquals("媒体条目数对不上", expectImages, media.size());
            for (int i = 1; i <= expectImages; i++) {
                assertTrue("缺 xl/media/image" + i + ".jpg",
                        media.contains("xl/media/image" + i + ".jpg"));
            }
            for (int i : new int[]{1, expectImages}) {
                java.io.InputStream in = zip.getInputStream(zip.getEntry("xl/media/image" + i + ".jpg"));
                byte[] bytes = readAll(in);
                in.close();
                Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                assertTrue("第 " + i + " 张封面解码失败", bmp != null && bmp.getWidth() > 0);
                if (bmp != null) {
                    bmp.recycle();
                }
            }
            java.io.InputStream din = zip.getInputStream(zip.getEntry("xl/drawings/drawing1.xml"));
            String drawing = new String(readAll(din), "UTF-8");
            din.close();
            int pics = 0;
            int at = drawing.indexOf("<xdr:pic>");
            while (at >= 0) {
                pics++;
                at = drawing.indexOf("<xdr:pic>", at + 1);
            }
            assertEquals("drawing1.xml 里的图片数与嵌入数不一致", expectImages, pics);
            return "部件齐全 / " + media.size() + " 张图连续编号 / 首尾可解码 / 锚点 " + pics + " 个";
        } finally {
            zip.close();
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /** 按导出同样的口径（长边 ≤200、JPEG 85）压一遍，算出旧路径驻留的图片字节总量 */
    private long compressedSum(List<File> files) {
        long sum = 0;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        for (File f : files) {
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            int sample = 1;
            while (Math.max(bounds.outWidth, bounds.outHeight) / sample > 200) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            if (bmp != null) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos);
                sum += bos.size();
                bmp.recycle();
            }
        }
        return sum;
    }
}
