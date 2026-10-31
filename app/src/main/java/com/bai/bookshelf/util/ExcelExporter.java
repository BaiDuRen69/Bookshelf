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
import java.util.Date;
import com.bai.bookshelf.util.XlsxParts.CoverImage;

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
            exportToStream(fos, books, locNames);
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
            return exportToStream(os, books, locNames);
        }
    }

    /**
     * 把藏书数据写成 xlsx 并输出到给定流（调用方负责打开/关闭外层资源）。
     *
     * @return 实际嵌入封面的图片数（失败/无封面的书不计入）
     */
    public static int exportToStream(java.io.OutputStream out, List<Book> books,
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

        // 2. 统一解析封面：本地直接读，网络下载后压缩（v3.1 修复「网络封面不导出」）
        // 注意：必须用可变列表，resolveCovers 内部要按下标 set 回填结果
        List<CoverImage> covers = new ArrayList<>();
        for (int i = 0; i < coverUrls.size(); i++) {
            covers.add(null);
        }
        resolveCovers(coverUrls, covers);

        // 3. 写入 xlsx（zip 包）；确有封面时才附加 media/drawings 部件
        List<CoverImage> valid = new ArrayList<>();
        for (CoverImage c : covers) {
            if (c != null) {
                valid.add(c);
            }
        }
        boolean hasImages = !valid.isEmpty();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            put(zos, "[Content_Types].xml", XlsxParts.contentTypesXml(hasImages));
            put(zos, "_rels/.rels", XlsxParts.relsXml());
            put(zos, "xl/workbook.xml", XlsxParts.workbookXml());
            put(zos, "xl/_rels/workbook.xml.rels", XlsxParts.workbookRelsXml());
            put(zos, "xl/worksheets/sheet1.xml", XlsxParts.sheetXml(rows, hasImages));
            if (hasImages) {
                put(zos, "xl/worksheets/_rels/sheet1.xml.rels", XlsxParts.sheetRelsXml());
                put(zos, "xl/drawings/drawing1.xml", XlsxParts.drawingXml(covers));
                put(zos, "xl/drawings/_rels/drawing1.xml.rels", XlsxParts.drawingRelsXml(valid.size()));
                // v3.1：文件名序号必须与 drawing 里的 rId 序号严格一致，
                // 因此用 valid（已滤掉 null）的下标 +1，而不是原始行号
                for (int i = 0; i < valid.size(); i++) {
                    putBytes(zos, "xl/media/image" + (i + 1) + ".jpg", valid.get(i).bytes);
                }
            }
        }
        return valid.size();
    }

    /** 封面是网络链接（需要下载） */
    private static boolean isRemote(String coverUrl) {
        if (coverUrl == null) {
            return false;
        }
        String t = coverUrl.trim().toLowerCase(java.util.Locale.ROOT);
        return t.startsWith("http://") || t.startsWith("https://");
    }

    /** 网络封面下载的并发数与超时（导出是一次性操作，够用即可） */
    private static final int DOWNLOAD_THREADS = 4;
    private static final int CONNECT_TIMEOUT_MS = 8000;
    private static final int READ_TIMEOUT_MS = 12000;

    /**
     * 解析全部封面填入 covers（与 urls 同序，允许元素为 null）。
     *
     * <p>网络封面用线程池并发下载（每本书一张图，串行会非常慢），
     * 单张失败不影响其它图片——导出宁可少几张图，也要保证主流程成功。
     *
     * @param urls   各书的 coverUrl 原值（与 covers 同序）
     * @param covers 输出数组，与 urls 同序；无封面为 null
     */
    private static void resolveCovers(List<String> urls, List<CoverImage> covers) {
        java.util.concurrent.ExecutorService pool =
                java.util.concurrent.Executors.newFixedThreadPool(DOWNLOAD_THREADS);
        try {
            for (int i = 0; i < urls.size(); i++) {
                final int idx = i;
                final String url = urls.get(i);
                if (url == null || url.trim().isEmpty()) {
                    continue;
                }
                if (isRemote(url)) {
                    pool.execute(() -> covers.set(idx, readRemoteCover(url)));
                } else {
                    covers.set(idx, readLocalCover(url));
                }
            }
            pool.shutdown();
            // 等待全部下载完成（导出是一次性同步流程，必须拿到完整结果才能写 zip）
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
    }

    /**
     * 读取本地封面文件并压缩成 JPEG。
     *
     * @return 压缩后的 JPEG 字节 + 原始宽高；失败返回 null（该格留空）
     */
    private static CoverImage readLocalCover(String coverUrl) {
        File f = new File(coverUrl.trim());
        if (!f.isFile()) {
            return null;
        }
        try {
            // v4.16：原用 Files.readAllBytes(f.toPath())，该 API 需要 API 26，
            // 而 App 的 minSdk 是 24 —— Android 7.0/7.1 上导出会直接崩。
            // 改用 FileInputStream，API 24 起可用，行为不变。
            byte[] raw = readAllBytesCompat(f);
            return compressBitmap(raw);
        } catch (Exception e) {
            return null; // 单张封面失败不拖垮整个导出
        }
    }

    /** 读取文件全部字节（兼容 API 24/25，不依赖 java.nio.file.Files） */
    private static byte[] readAllBytesCompat(File file) throws Exception {
        try (java.io.FileInputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(
                    (int) Math.max(file.length(), 1024));
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /**
     * 下载网络封面并压缩成 JPEG（v3.1 新增）。
     *
     * <p>此前实现里网络链接被直接 return null，导致扫码录入的封面（绝大多数是 ISBN 接口返回的
     * 网络图）一张都进不了 xlsx——这就是「导出后封面列还是空的」的根因。
     */
    private static CoverImage readRemoteCover(String url) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new java.net.URL(url.trim()).openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) Bookshelf/3.1");
            conn.connect();
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            try (java.io.InputStream in = conn.getInputStream()) {
                byte[] raw = readAll(in);
                return compressBitmap(raw);
            }
        } catch (Exception e) {
            return null; // 下载/解码失败都跳过这一张
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(buf)) > 0) {
            total += n;
            // 单张封面超过 8MB 视为异常响应，直接放弃，避免把大图全塞进内存
            if (total > 8 * 1024 * 1024) {
                return null;
            }
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    /**
     * 图片字节 → 200px 上限的 JPEG（保持宽高比）。
     *
     * <p>用两次解码：第一次只读尺寸（inJustDecodeBounds）算 inSampleSize，
     * 第二次按采样解码，避免大图 OOM。
     */
    private static CoverImage compressBitmap(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
            int iw = bounds.outWidth;
            int ih = bounds.outHeight;
            if (iw <= 0 || ih <= 0) {
                return null;
            }
            int sample = 1;
            while (Math.max(iw, ih) / sample > 200) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
            if (bmp == null) {
                return null;
            }
            // 用解码后的实际宽高算比例，保证 Excel 里不被拉伸变形
            int aw = bmp.getWidth();
            int ah = bmp.getHeight();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            bmp.compress(Bitmap.CompressFormat.JPEG, 85, bos);
            bmp.recycle();
            return new CoverImage(bos.toByteArray(), aw, ah);
        } catch (Throwable e) {
            return null; // 含 OutOfMemoryError：单张失败不拖垮整个导出
        }
    }

    // ======================= 以下为各 XML 部件的生成 =======================

    private static void put(ZipOutputStream zos, String name, String content) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(content.getBytes(StandardCharsets.UTF_8));
        zos.closeEntry();
    }

    /** 二进制条目（封面图片等） */
    private static void putBytes(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

}
