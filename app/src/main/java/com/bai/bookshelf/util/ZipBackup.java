package com.bai.bookshelf.util;

import android.content.Context;

import com.bai.bookshelf.BuildConfig;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.data.entity.NoteImage;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.DigestOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * zip 整包备份（v2.8）：替代原 xlsx 自动备份（xlsx 存不了图片）。
 *
 * 备份包结构（条目名见 {@link BackupFormat}）：
 * - {@code data.json}：全部表数据（书籍、逐本副本、借出记录、备注图片记录、地点/渠道/标签/分类/国家/出版社/借阅人库），
 *   图片字段（coverUrl / note_images.path）只存相对 filesDir 的路径（如 {@code covers/cover_1.jpg}），换设备也能恢复；
 * - {@code media/}：App 内部存储（filesDir）下的全部图片文件，按原相对层级打包；
 * - {@code meta.json}（v5.7）：App 版本、DB 版本、打包时间、册数、图片数与 {@link BackupChecksum} 校验和。
 *
 * 数据条目仍不写时间戳、条目时间固定为 0；meta 里带打包时刻，所以「内容没变就跳过备份」
 * 从「比字节」改成「比校验和」（见 {@link BackupManager}）。
 */
public final class ZipBackup {

    /** 参与备份的图片扩展名（小写） */
    private static final String[] IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif"};


    /**
     *  zip 里的一个媒体条目。
     *
     * <p>真实备份用文件实现（流式读入，不把整张封面进内存），
     * 单测里用字节数组实现，走同一条 {@link #writeZip} 代码路径。
     */
    public interface MediaItem {
        /** zip 内条目名（含 {@code media/} 前缀） */
        String name();

        InputStream open() throws IOException;
    }

    /** filesDir 下的图片文件 */
    private static class FileItem implements MediaItem {
        private final String entryName;
        private final File file;

        FileItem(String entryName, File file) {
            this.entryName = entryName;
            this.file = file;
        }

        @Override
        public String name() {
            return entryName;
        }

        @Override
        public InputStream open() throws IOException {
            return new FileInputStream(file);
        }
    }

    /** 内存字节（单测 / 小附件用） */
    public static class BytesItem implements MediaItem {
        private final String entryName;
        private final byte[] content;

        public BytesItem(String entryName, byte[] content) {
            this.entryName = entryName;
            this.content = content;
        }

        @Override
        public String name() {
            return entryName;
        }

        @Override
        public InputStream open() {
            return new java.io.ByteArrayInputStream(content);
        }
    }

    private ZipBackup() {
    }

    /**
     * 生成备份 zip 并<b>直接写到文件</b>（需在后台线程调用）。
     *
     * <p>v5.8.11：以前先打包进 ByteArrayOutputStream 再落盘，整库图片会在内存里
     * 再走一遍；现在 ZipOutputStream 直写文件、SHA-256 边写边算，
     * 内存峰值只剩 data.json 本身 + 8KB 缓冲。返回 meta（含校验和）。
     */
    public static BackupMeta createTo(Context ctx, File out) throws IOException {
        AppDatabase db = BookshelfApp.get().db();
        String filesDirPath = ctx.getFilesDir().getAbsolutePath();

        // ---- 1. data.json（图片字段降级为相对路径） ----
        List<Book> books = BookSanitizer.normalizeAll(db.bookDao().getAll());
        JsonObject root = new JsonObject();
        root.add("books", booksToJson(books, filesDirPath));
        root.add("book_copies", new Gson().toJsonTree(db.bookCopyDao().getAll()));
        root.add("borrow_records", new Gson().toJsonTree(db.borrowRecordDao().getAll()));
        root.add("note_images", noteImagesToJson(db.noteImageDao().getAll(), filesDirPath));
        root.add("excerpts", excerptsToJson(db.excerptDao().getAll(), filesDirPath));
        root.add("locations", new Gson().toJsonTree(db.locationDao().getAll()));
        root.add("source_channels", new Gson().toJsonTree(db.sourceChannelDao().getAll()));
        root.add("tags", new Gson().toJsonTree(db.tagDao().getAll()));
        root.add("categories", new Gson().toJsonTree(db.categoryDao().getAll()));
        root.add("countries", new Gson().toJsonTree(db.countryDao().getAll()));
        root.add("publishers", new Gson().toJsonTree(db.publisherDao().getAll()));
        root.add("borrowers", new Gson().toJsonTree(db.borrowerDao().getAll()));
        byte[] jsonBytes = new Gson().toJson(root).getBytes(StandardCharsets.UTF_8);

        // ---- 2. 收集 filesDir 下的图片（排序保证条目集合稳定） ----
        File filesDir = ctx.getFilesDir();
        List<File> found = new ArrayList<>();
        collectImages(filesDir, filesDir, found);
        Collections.sort(found, Comparator.comparing(f -> relPath(filesDir, f)));
        List<MediaItem> media = new ArrayList<>();
        for (File f : found) {
            media.add(new FileItem(BackupFormat.MEDIA_PREFIX + relPath(filesDir, f), f));
        }

        // ---- 3. 打包装配 ----
        BackupMeta meta = new BackupMeta();
        meta.format = 1;
        meta.appVersion = BuildConfig.VERSION_NAME;
        meta.appCode = BuildConfig.VERSION_CODE;
        meta.dbVersion = AppDatabase.VERSION;
        meta.createdAt = System.currentTimeMillis();
        meta.books = books.size();
        // ZipOutputStream.close() 会连底层 FileOutputStream 一起关掉，那样就没法 sync 了；
        // 用一个「关的时候只 flush」的包装，先 finish 再 fsync 落盘
        try (java.io.FileOutputStream raw = new java.io.FileOutputStream(out)) {
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
                    new java.io.FilterOutputStream(raw) {
                        @Override
                        public void close() throws IOException {
                            flush();
                        }
                    });
            writeZipTo(zos, jsonBytes, media, meta);
            zos.finish();
            zos.flush();
            raw.getFD().sync();
        }
        return meta;
    }

    /**
     * 组装一个备份包：{@code data.json → media/* → meta.json}。
     *
     * <p>meta 必须最后写：校验和要覆盖前面全部数据条目，而它自己不能参与计算。
     */
    public static void writeZip(OutputStream out, byte[] dataJson, List<MediaItem> media,
                                BackupMeta meta) throws IOException {
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            writeZipTo(zos, dataJson, media, meta);
        }
    }

    /** 往已建好的 ZipOutputStream 里写全部条目（调用方负责 finish/关流） */
    private static void writeZipTo(ZipOutputStream zos, byte[] dataJson, List<MediaItem> media,
                                   BackupMeta meta) throws IOException {
        BackupChecksum checksum = new BackupChecksum();
        long payload = 0;
        {
            payload += writeEntry(zos, BackupFormat.ENTRY_DATA, dataJson, checksum);
            for (MediaItem item : media) {
                try (InputStream in = item.open()) {
                    payload += writeEntry(zos, item.name(), in, checksum);
                }
            }
            meta.algorithm = BackupChecksum.ALGORITHM;
            meta.media = Math.max(0, checksum.entries() - 1); // 减掉 data.json
            meta.bytes = payload;
            meta.checksum = checksum.finish();

            byte[] metaBytes = meta.toJson().getBytes(StandardCharsets.UTF_8);
            ZipEntry me = new ZipEntry(BackupFormat.ENTRY_META);
            me.setTime(0);
            zos.putNextEntry(me);
            zos.write(metaBytes);
            zos.closeEntry();
        }
    }

    /** 写入一个字节数组条目 */
    private static long writeEntry(ZipOutputStream zos, String name, byte[] content,
                                   BackupChecksum checksum) throws IOException {
        return writeEntry(zos, name, new ByteArrayInputStream(content), checksum);
    }

    /**
     * 流式写入一个条目，同时累计它的内容摘要（摘要边写边算，不让整张封面进内存）。
     *
     * @return 该条目的未压缩字节数
     */
    private static long writeEntry(ZipOutputStream zos, String name, InputStream in,
                                   BackupChecksum checksum) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zos.putNextEntry(entry);
        java.security.MessageDigest md = BackupChecksum.newEntryDigest(name);
        DigestOutputStream dos = new DigestOutputStream(zos, md);
        byte[] buf = new byte[8192];
        long total = 0;
        int len;
        while ((len = in.read(buf)) > 0) {
            dos.write(buf, 0, len);
            total += len;
        }
        dos.flush();
        checksum.addDigest(name, md);
        zos.closeEntry();
        return total;
    }

    /** 书籍 → JSON 数组：coverUrl 若是 filesDir 下的本地路径则降级为相对路径 */
    private static JsonArray booksToJson(List<Book> books, String filesDirPath) {
        Gson gson = new Gson();
        JsonArray arr = new JsonArray();
        for (Book b : books) {
            JsonObject o = gson.toJsonTree(b).getAsJsonObject();
            if (o.has("coverUrl")) {
                o.addProperty("coverUrl", toRel(o.get("coverUrl").getAsString(), filesDirPath));
            }
            arr.add(o);
        }
        return arr;
    }

    /** 备注图片 → JSON 数组：path 同样降级为相对路径 */
    private static JsonArray excerptsToJson(List<Excerpt> list, String filesDirPath) {
        Gson gson = new Gson();
        JsonArray arr = new JsonArray();
        for (Excerpt e : list) {
            JsonObject o = gson.toJsonTree(e).getAsJsonObject();
            if (o.has("imagePath")) {
                o.addProperty("imagePath", toRel(o.get("imagePath").getAsString(), filesDirPath));
            }
            arr.add(o);
        }
        return arr;
    }

    private static JsonArray noteImagesToJson(List<NoteImage> imgs, String filesDirPath) {
        Gson gson = new Gson();
        JsonArray arr = new JsonArray();
        for (NoteImage n : imgs) {
            JsonObject o = gson.toJsonTree(n).getAsJsonObject();
            if (o.has("path")) {
                o.addProperty("path", toRel(o.get("path").getAsString(), filesDirPath));
            }
            arr.add(o);
        }
        return arr;
    }

    /** 绝对路径 → 相对 filesDir 的路径；不在 filesDir 下（http 链接等）原样保留 */
    static String toRel(String path, String filesDirPath) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        String prefix = filesDirPath + File.separator;
        if (path.startsWith(prefix)) {
            return path.substring(prefix.length());
        }
        return path;
    }

    /** 相对路径 → 绝对路径；空/链接/已是绝对路径则原样保留（恢复用） */
    static String toAbs(String path, String filesDirPath) {
        if (path == null || path.isEmpty() || path.startsWith("http") || path.startsWith(File.separator)) {
            return path == null ? "" : path;
        }
        return new File(filesDirPath, path).getAbsolutePath();
    }

    /** 递归收集目录下的图片文件 */
    private static void collectImages(File root, File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                collectImages(root, f, out);
            } else if (isImage(f.getName())) {
                out.add(f);
            }
        }
    }

    private static boolean isImage(String name) {
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String ext : IMAGE_EXTS) {
            if (lower.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    /** 文件相对 filesDir 的路径（分隔符统一为 /，与 zip 条目名一致） */
    private static String relPath(File filesDir, File f) {
        String rel = f.getAbsolutePath().substring(filesDir.getAbsolutePath().length() + 1);
        return rel.replace(File.separatorChar, '/');
    }
}
