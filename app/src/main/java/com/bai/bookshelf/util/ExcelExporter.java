package com.bai.bookshelf.util;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.bai.bookshelf.data.entity.Book;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import com.bai.bookshelf.util.XlsxParts.CoverRef;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Excel 导出器：零第三方依赖，直接按 OOXML 规范写最小 .xlsx 文件
 * （xlsx 本质是一个包含若干 XML 的 zip 包）。
 * 文本单元格用 inlineStr 内联字符串，省去 sharedStrings.xml；
 * 数字列（页数/定价/实付/评分）写成数值类型，方便 Excel 里排序统计。
 */
public final class ExcelExporter {

    /** 导出列头（ISBN 必带，含 v2/v6/v7 新增字段；v2.3 移除副标题/各本存放位置） */
    private static final String[] HEADERS = {
            "ISBN", "书名", "作者", "译者", "国家/地区", "出版社",
            "版次", "印次", "页数", "字数", "定价", "豆瓣评分", "分类", "标签",
            "封面", "存放地点", "藏书状态", "阅读状态", "来源", "来源渠道",
            "购买日期", "购买价格", "评分", "备注", "录入时间", "拥有数量",
            "系列", "卷号", "阅读顺序"
    };

    private ExcelExporter() {
    }

    /**
     * 导出全部藏书到应用专属目录，返回生成的文件。
     *
     * @param locNames 地点 id → 地点名 的映射（把 locationId 翻译成人看得懂的名字）
     */
    public static File export(Context ctx, List<Book> books, Map<Long, String> locNames) throws IOException {
        String fileName = "小书房藏书_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".xlsx";
        File dir = ctx.getExternalFilesDir("export");
        if (dir != null && !dir.exists()) {
            dir.mkdirs();
        }
        File out = new File(dir, fileName);
        try (FileOutputStream fos = new FileOutputStream(out)) {
            exportToStream(ctx, fos, books, locNames);
        }
        return out;
    }
    /**
     * 导出到用户自选的文件夹（SAF DocumentTree）。
     *
     * @param treeUri 用户刚选好的文件夹 Uri（返回即写，无需持久化授权）
     * @return 导出结果：文件名 + 实际嵌入的封面数
     */
    public static ExportResult exportToTree(Context ctx, android.net.Uri treeUri,
                                            List<Book> books, Map<Long, String> locNames) throws IOException {
        String fileName = "小书房藏书_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".xlsx";
        int images = writeToTree(ctx, treeUri, books, locNames, fileName);
        return new ExportResult(fileName, images);
    }

    /** 导出结果：文件名 + 成功嵌入的封面图片数 */
    public static class ExportResult {
        public final String fileName;
        public final int imageCount;

        ExportResult(String fileName, int imageCount) {
            this.fileName = fileName;
            this.imageCount = imageCount;
        }
    }

    /**
     * 在 SAF 文件夹里新建 xlsx 并写入（导出与自动备份共用）
     *
     * @return 实际嵌入封面的图片数
     */
    private static int writeToTree(Context ctx, android.net.Uri treeUri,
                                   List<Book> books, Map<Long, String> locNames,
                                   String fileName) throws IOException {
        android.net.Uri docUri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                treeUri, android.provider.DocumentsContract.getTreeDocumentId(treeUri));
        android.net.Uri created = android.provider.DocumentsContract.createDocument(
                ctx.getContentResolver(), docUri,
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", fileName);
        if (created == null) {
            throw new IOException("无法在所选文件夹创建文件，请重新选择文件夹");
        }
        try (java.io.OutputStream os = ctx.getContentResolver().openOutputStream(created)) {
            if (os == null) {
                throw new IOException("无法写入所选文件夹");
            }
            return exportToStream(ctx, os, books, locNames);
        }
    }

    /** 封面解析的并发数：同时也是「同时在内存里的图片张数」上限 */
    private static final int COVER_THREADS = 4;
    /** 网络封面单张上限：超过直接放弃这张 */
    private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;
    /** 嵌入图的长边上限（Excel 里只显示 48x64 的框，没必要留全尺寸） */
    private static final int COVER_MAX_EDGE = 200;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;

    /**
     * 把藏书数据写成 xlsx 并输出到给定流（调用方负责打开/关闭外层资源）。
     *
     * <p>v5.8.21（评审 #6）：封面不再全驻留内存。每张图压成 JPEG 先落 cacheDir 临时文件，
     * 内存里只留「文件路径 + 宽高」，写 zip 时再逐张流进去；并发仍是
     * {@value #COVER_THREADS} 张一批，所以峰值与藏书数量无关，只与并发数有关。
     *
     * @return 实际嵌入封面的图片数（失败/无封面的书不计入）
     */
    public static int exportToStream(Context ctx, java.io.OutputStream out, List<Book> books,
                                     Map<Long, String> locNames) throws IOException {
        // 1. 组装全部行（第一行表头）；封面列写入原始路径/URL（v4.0，评审 #2）：
        //    嵌入图仅供查看，导入时以「封面」列文本为准还原 book.coverUrl，
        //    本地封面可直接找回文件，网络封面可重新加载——Excel 不再弄丢封面信息
        List<String[]> rows = new ArrayList<>();
        rows.add(HEADERS);
        List<String> coverUrls = new ArrayList<>(); // 各书 coverUrl 原值，供封面解析使用
        for (Book b : books) {
            String cover = b.coverUrl == null ? "" : b.coverUrl.trim();
            rows.add(new String[]{
                    b.isbn, b.title, b.author, b.translator, b.country, b.publisher,
                    b.publishDate, b.printing, String.valueOf(b.pages), b.wordCount,
                    String.valueOf(b.listPrice), b.doubanRating, b.category, b.tags,
                    cover, // 封面：原始路径/URL（嵌入图之外的可恢复数据源）
                    b.locationId == null ? "" : locNames.getOrDefault(b.locationId, ""),
                    StatusText.stock(b.stockStatus), StatusText.read(b.readStatus),
                    StatusText.own(b.ownType), b.sourceChannel,
                    b.purchaseDate, String.valueOf(b.purchasePrice),
                    String.valueOf(b.rating), b.note, StatusText.formatTime(b.createdAt),
                    String.valueOf(b.quantity),
                    b.seriesName == null ? "" : b.seriesName,
                    b.seriesVolume == null ? "" : b.seriesVolume,
                    String.valueOf(b.seriesOrder)
            });
            coverUrls.add(cover);
        }

        // 2. 封面逐张压到临时目录（下标即槽位，无封面的位置留 null）
        File stage = new File(ctx.getCacheDir(), "xlsx_covers_" + System.nanoTime());
        //noinspection ResultOfMethodCallIgnored
        stage.mkdirs();
        try {
            CoverRef[] slots = resolveCovers(stage, coverUrls);
            List<CoverRef> valid = new ArrayList<>();
            for (CoverRef r : slots) {
                if (r != null) {
                    valid.add(r);
                }
            }

        // 第 4.3 步：打卡数据独立成第二张 sheet；没有记录就不生成该 sheet
        List<com.bai.bookshelf.data.entity.CheckIn> checkIns = loadCheckIns();
        boolean hasCheckinSheet = CheckInSheet.needed(checkIns);
        java.util.Map<Long, String> titles = new java.util.HashMap<>();
        for (Book b : books) {
            titles.put(b.id, com.bai.bookshelf.util.BookText.displayTitle(b.title));
        }
        List<String[]> checkinRows = CheckInSheet.rows(checkIns, titles);

        // 3. 写入 xlsx（zip 包）；确有封面时才附加 media/drawings 部件
        boolean hasImages = !valid.isEmpty();
            try (ZipOutputStream zos = new ZipOutputStream(out)) {
                put(zos, "[Content_Types].xml",
                        XlsxParts.contentTypesXml(hasImages, hasCheckinSheet));
                put(zos, "_rels/.rels", XlsxParts.relsXml());
                put(zos, "xl/workbook.xml", XlsxParts.workbookXml(hasCheckinSheet));
                put(zos, "xl/_rels/workbook.xml.rels",
                        XlsxParts.workbookRelsXml(hasCheckinSheet));
                put(zos, "xl/worksheets/sheet1.xml", XlsxParts.sheetXml(rows, hasImages));
                if (hasCheckinSheet) {
                    put(zos, "xl/worksheets/sheet2.xml",
                            XlsxParts.sheetXml(checkinRows, false));
                }
                if (hasImages) {
                    put(zos, "xl/worksheets/_rels/sheet1.xml.rels", XlsxParts.sheetRelsXml());
                    put(zos, "xl/drawings/drawing1.xml", XlsxParts.drawingXml(Arrays.asList(slots)));
                    put(zos, "xl/drawings/_rels/drawing1.xml.rels",
                            XlsxParts.drawingRelsXml(valid.size()));
                    // v3.1：文件名序号必须与 drawing 里的 rId 序号严格一致，
                    // 因此用 valid（已滤掉 null）的下标 +1，而不是原始行号
                    for (int i = 0; i < valid.size(); i++) {
                        putFile(zos, "xl/media/image" + (i + 1) + ".jpg", valid.get(i).file);
                    }
                }
            }
            return valid.size();
        } finally {
            deleteRecursively(stage);
        }
    }

    /** 第 4.3 步：打卡数据直接从库取；库未就绪 / 单测无 Application 时返回空列表 */
    private static List<com.bai.bookshelf.data.entity.CheckIn> loadCheckIns() {
        try {
            com.bai.bookshelf.app.BookshelfApp app =
                    com.bai.bookshelf.app.BookshelfApp.get();
            return app == null ? java.util.Collections.emptyList()
                    : app.db().checkInDao().getAll();
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
    }

    /**
     * 逐张解析封面到临时文件，返回与 urls 同序的数组（无封面/失败为 null）。
     *
     * <p>本地与网络封面都提交到同一个线程池：本地那批原先在主线程串行压图，
     * 600 张要 7 秒；并发后和下载共用同一道「同时最多 4 张在内存」的上限。
     */
    private static CoverRef[] resolveCovers(File stage, List<String> urls) {
        CoverRef[] slots = new CoverRef[urls.size()];
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(COVER_THREADS);
        try {
            for (int i = 0; i < urls.size(); i++) {
                final int idx = i;
                final String url = urls.get(i);
                if (url == null || url.trim().isEmpty()) {
                    continue;
                }
                pool.execute(() -> slots[idx] = isRemote(url)
                        ? fetchRemoteCover(url, stage, idx)
                        : compressLocalCover(url, stage, idx));
            }
            pool.shutdown();
            // 写包前必须拿到全部结果（一次性同步流程）
            while (!pool.isTerminated()) {
                try {
                    pool.awaitTermination(200, java.util.concurrent.TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return slots;
    }

    /** 本地封面：直接从文件降采样解码，压成 200px 内的 JPEG 落到临时目录 */
    private static CoverRef compressLocalCover(String coverUrl, File stage, int idx) {
        File src = new File(coverUrl.trim());
        if (!src.isFile()) {
            return null;
        }
        return writeScaledJpeg(src, new File(stage, idx + ".jpg"));
    }

    /**
     * 网络封面：先流式下载到临时文件（不整张进内存），再按本地封面同一条管线压缩。
     *
     * <p>v3.1 起才支持网络封面——此前直接 return null，扫码录入的封面一张都进不了 xlsx。
     */
    private static CoverRef fetchRemoteCover(String url, File stage, int idx) {
        File raw = new File(stage, idx + ".raw");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new java.net.URL(url.trim()).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Bookshelf/5.8");
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            if (!drainTo(conn.getInputStream(), raw)) {
                return null; // 超过单张上限的异常响应：这张不要，整包照旧
            }
            return writeScaledJpeg(raw, new File(stage, idx + ".jpg"));
        } catch (Exception e) {
            return null; // 下载/解码失败都跳过这一张
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
            //noinspection ResultOfMethodCallIgnored
            raw.delete();
        }
    }

    /** 把响应流写进文件；单张超过 {@value #MAX_DOWNLOAD_BYTES} 字节判为异常，返回 false */
    private static boolean drainTo(java.io.InputStream in, File dst) throws IOException {
        long got = 0;
        byte[] buf = new byte[8192];
        try (FileOutputStream fos = new FileOutputStream(dst)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                got += n;
                if (got > MAX_DOWNLOAD_BYTES) {
                    return false;
                }
                fos.write(buf, 0, n);
            }
        }
        return got > 0;
    }

    /**
     * 图片文件 → 长边 ≤{@value #COVER_MAX_EDGE} 的 JPEG 临时文件。
     *
     * <p>两次解码：第一次只读尺寸算 inSampleSize，第二次按采样解码，避免大图 OOM。
     * 单张失败返回 null（该格留空），不拖垮整个导出。
     */
    private static CoverRef writeScaledJpeg(File src, File dst) {
        Bitmap bmp = null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(src.getAbsolutePath(), bounds);
            int iw = bounds.outWidth;
            int ih = bounds.outHeight;
            if (iw <= 0 || ih <= 0) {
                return null;
            }
            int sample = 1;
            while (Math.max(iw, ih) / sample > COVER_MAX_EDGE) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            bmp = BitmapFactory.decodeFile(src.getAbsolutePath(), opts);
            if (bmp == null) {
                return null;
            }
            // 用解码后的实际宽高算比例，保证 Excel 里不被拉伸变形
            int aw = bmp.getWidth();
            int ah = bmp.getHeight();
            try (FileOutputStream fos = new FileOutputStream(dst)) {
                bmp.compress(Bitmap.CompressFormat.JPEG, 85, fos);
            }
            return new CoverRef(dst, aw, ah);
        } catch (Throwable e) {
            //noinspection ResultOfMethodCallIgnored
            dst.delete();
            return null; // 含 OutOfMemoryError：单张失败不拖垮整个导出
        } finally {
            if (bmp != null) {
                bmp.recycle();
            }
        }
    }

    /** 封面是网络链接（需要下载） */
    private static boolean isRemote(String coverUrl) {
        if (coverUrl == null) {
            return false;
        }
        String t = coverUrl.trim().toLowerCase(java.util.Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://");
    }

    private static void deleteRecursively(File dir) {
        if (dir == null || !dir.exists()) {
            return;
        }
        File[] children = dir.listFiles();
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
        dir.delete();
    }

    // ======================= 以下为各 XML 部件的生成 =======================

    private static void put(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /** 二进制条目：从临时文件流式写进 zip（8KB 缓冲，一次一张图） */
    private static void putFile(ZipOutputStream zos, String name, File src) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        try (java.io.FileInputStream in = new java.io.FileInputStream(src)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                zos.write(buf, 0, n);
            }
        }
        zos.closeEntry();
    }

}
