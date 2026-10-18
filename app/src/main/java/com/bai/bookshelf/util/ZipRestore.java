package com.bai.bookshelf.util;

import android.content.Context;
import android.net.Uri;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Borrower;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.data.entity.Country;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.data.entity.Publisher;
import com.bai.bookshelf.data.entity.SourceChannel;
import com.bai.bookshelf.data.entity.Tag;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * zip 备份恢复（v2.8）：解析 {@link ZipBackup} 生成的 .zip，
 * 展开 media/ 到 filesDir，并在一个 Room 事务里清空全部表后按备份 id 重写。
 *
 * 语义为**整包替换**（与 xlsx 导入的「按 ISBN 合并」不同）——调用前必须向用户确认。
 */
public final class ZipRestore {

    private static final String ENTRY_DATA = "data.json";
    private static final String MEDIA_PREFIX = "media/";

    /** 恢复结果统计 */
    public static class Result {
        public int books;  // 恢复的书籍数
        public int media;  // 恢复的媒体文件数
    }

    private ZipRestore() {
    }

    /** 从 zip 备份恢复（需在后台线程调用；内部按 id 原样重写，恢复后藏书 id 与备份时一致） */
    public static Result restore(Context ctx, Uri uri, AppDatabase db) throws Exception {
        String json = null;
        java.util.LinkedHashMap<String, byte[]> media = new java.util.LinkedHashMap<>();

        // ---- 1. 读 zip：data.json + media/ ----
        InputStream rawIn = ctx.getContentResolver().openInputStream(uri);
        if (rawIn == null) {
            throw new IOException("无法读取所选文件");
        }
        try (ZipInputStream zis = new ZipInputStream(rawIn)) {
            ZipEntry e;
            while ((e = zis.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                if (ENTRY_DATA.equals(e.getName())) {
                    json = new String(readAll(zis), java.nio.charset.StandardCharsets.UTF_8);
                } else if (e.getName().startsWith(MEDIA_PREFIX)) {
                    media.put(e.getName().substring(MEDIA_PREFIX.length()), readAll(zis));
                }
            }
        }
        if (json == null) {
            throw new IOException("备份文件缺少 data.json，不是本应用生成的 zip 备份");
        }

        // ---- 2. 展开媒体文件到 filesDir（保持相对层级；防目录穿越） ----
        File filesDir = ctx.getFilesDir();
        String filesDirCanonical = filesDir.getCanonicalPath();
        Result result = new Result();
        for (java.util.Map.Entry<String, byte[]> en : media.entrySet()) {
            File out = new File(filesDir, en.getKey());
            if (!out.getCanonicalPath().startsWith(filesDirCanonical + File.separator)
                    && !out.getCanonicalPath().equals(filesDirCanonical)) {
                continue; // 非法路径，跳过
            }
            File parent = out.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                continue;
            }
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(en.getValue());
                result.media++;
            }
        }

        // ---- 3. 事务内清空 + 重写（要么全成功，要么全失败） ----
        Gson gson = new Gson();
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        String filesDirPath = filesDir.getAbsolutePath();

        db.runInTransaction(() -> {
            // 清空全部表
            db.bookDao().deleteAll();
            db.bookCopyDao().deleteAll();
            db.borrowRecordDao().deleteAll();
            db.noteImageDao().deleteAll();
            db.locationDao().deleteAll();
            db.sourceChannelDao().deleteAll();
            db.tagDao().deleteAll();
            db.categoryDao().deleteAll();
            db.countryDao().deleteAll();
            db.publisherDao().deleteAll();
            db.borrowerDao().deleteAll();

            // 按备份 id 重写（先主子表：书 → 副本/借出/图片/各库表）
            List<Book> books = readList(root, "books", new TypeToken<List<Book>>() {});
            for (Book b : books) {
                b.coverUrl = ZipBackup.toAbs(b.coverUrl, filesDirPath);
                db.bookDao().insert(b);
            }
            result.books = books.size();

            List<BookCopy> copies = readList(root, "book_copies", new TypeToken<List<BookCopy>>() {});
            db.bookCopyDao().insertAll(copies);
            for (BorrowRecord r : readList(root, "borrow_records", new TypeToken<List<BorrowRecord>>() {})) {
                db.borrowRecordDao().insert(r);
            }
            List<NoteImage> imgs = readList(root, "note_images", new TypeToken<List<NoteImage>>() {});
            for (NoteImage n : imgs) {
                n.path = ZipBackup.toAbs(n.path, filesDirPath);
                db.noteImageDao().insert(n);
            }
            for (Location x : readList(root, "locations", new TypeToken<List<Location>>() {})) {
                db.locationDao().insert(x);
            }
            for (SourceChannel x : readList(root, "source_channels", new TypeToken<List<SourceChannel>>() {})) {
                db.sourceChannelDao().insert(x);
            }
            for (Tag x : readList(root, "tags", new TypeToken<List<Tag>>() {})) {
                db.tagDao().insert(x);
            }
            for (Category x : readList(root, "categories", new TypeToken<List<Category>>() {})) {
                db.categoryDao().insert(x);
            }
            for (Country x : readList(root, "countries", new TypeToken<List<Country>>() {})) {
                db.countryDao().insert(x);
            }
            for (Publisher x : readList(root, "publishers", new TypeToken<List<Publisher>>() {})) {
                db.publisherDao().insert(x);
            }
            for (Borrower x : readList(root, "borrowers", new TypeToken<List<Borrower>>() {})) {
                db.borrowerDao().insert(x);
            }
        });
        return result;
    }

    private static <T> List<T> readList(JsonObject root, String key, TypeToken<List<T>> type) {
        if (!root.has(key) || !root.get(key).isJsonArray()) {
            return new java.util.ArrayList<>();
        }
        JsonArray arr = root.getAsJsonArray(key);
        Type listType = type.getType();
        return new Gson().fromJson(arr, listType);
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = in.read(buf)) > 0) {
            bos.write(buf, 0, len);
        }
        return bos.toByteArray();
    }
}
