package com.bai.bookshelf.util;

import android.content.Context;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.NoteImage;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * zip 整包备份（v2.8）：替代原 xlsx 自动备份（xlsx 存不了图片）。
 *
 * 备份包结构：
 * - {@code data.json}：全部表数据（书籍、逐本副本、借出记录、备注图片记录、地点/渠道/标签/分类/国家/出版社/借阅人库），
 *   图片字段（coverUrl / note_images.path）只存相对 filesDir 的路径（如 {@code covers/cover_1.jpg}），换设备也能恢复；
 * - {@code media/}：App 内部存储（filesDir）下的全部图片文件，按原相对层级打包。
 *
 * 生成过程不写时间戳、条目时间固定为 0：相同数据 ⇒ 相同字节，供「内容没变就跳过备份」去重。
 */
public final class ZipBackup {

    private static final String ENTRY_DATA = "data.json";
    private static final String MEDIA_PREFIX = "media/";

    /** 参与备份的图片扩展名（小写） */
    private static final String[] IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".webp", ".gif"};

    private ZipBackup() {
    }

    /** 生成备份 zip 的全部字节（需在后台线程调用） */
    public static byte[] create(Context ctx) throws IOException {
        AppDatabase db = BookshelfApp.get().db();
        String filesDirPath = ctx.getFilesDir().getAbsolutePath();

        // ---- 1. data.json（图片字段降级为相对路径） ----
        JsonObject root = new JsonObject();
        root.add("books", booksToJson(com.bai.bookshelf.util.BookSanitizer.normalizeAll(db.bookDao().getAll()), filesDirPath));
        root.add("book_copies", new Gson().toJsonTree(db.bookCopyDao().getAll()));
        root.add("borrow_records", new Gson().toJsonTree(db.borrowRecordDao().getAll()));
        root.add("note_images", noteImagesToJson(db.noteImageDao().getAll(), filesDirPath));
        root.add("locations", new Gson().toJsonTree(db.locationDao().getAll()));
        root.add("source_channels", new Gson().toJsonTree(db.sourceChannelDao().getAll()));
        root.add("tags", new Gson().toJsonTree(db.tagDao().getAll()));
        root.add("categories", new Gson().toJsonTree(db.categoryDao().getAll()));
        root.add("countries", new Gson().toJsonTree(db.countryDao().getAll()));
        root.add("publishers", new Gson().toJsonTree(db.publisherDao().getAll()));
        root.add("borrowers", new Gson().toJsonTree(db.borrowerDao().getAll()));
        byte[] jsonBytes = new Gson().toJson(root).getBytes(java.nio.charset.StandardCharsets.UTF_8);

        // ---- 2. 收集 filesDir 下的图片（排序保证字节确定） ----
        File filesDir = ctx.getFilesDir();
        List<File> media = new ArrayList<>();
        collectImages(filesDir, filesDir, media);
        Collections.sort(media, Comparator.comparing(f -> relPath(filesDir, f)));

        // ---- 3. 流式写入 zip ----
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            ZipEntry dataEntry = new ZipEntry(ENTRY_DATA);
            dataEntry.setTime(0);
            zos.putNextEntry(dataEntry);
            zos.write(jsonBytes);
            zos.closeEntry();

            byte[] buf = new byte[8192];
            for (File f : media) {
                ZipEntry me = new ZipEntry(MEDIA_PREFIX + relPath(filesDir, f));
                me.setTime(0);
                zos.putNextEntry(me);
                try (FileInputStream in = new FileInputStream(f)) {
                    int len;
                    while ((len = in.read(buf)) > 0) {
                        zos.write(buf, 0, len);
                    }
                }
                zos.closeEntry();
            }
        }
        return bos.toByteArray();
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
        String lower = name.toLowerCase();
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
        return rel.replace(java.io.File.separatorChar, '/');
    }
}
