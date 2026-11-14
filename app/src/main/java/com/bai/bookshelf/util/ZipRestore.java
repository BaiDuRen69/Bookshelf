package com.bai.bookshelf.util;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Borrower;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.data.entity.Country;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.data.entity.Publisher;
import com.bai.bookshelf.data.entity.SourceChannel;
import com.bai.bookshelf.data.entity.Tag;
import com.bai.bookshelf.data.entity.WishBook;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.List;

/**
 * zip 备份恢复（v2.8）：解析 {@link ZipBackup} 生成的 .zip，
 * 展开 media/ 到 filesDir，并在一个 Room 事务里清空全部表后按备份 id 重写。
 *
 * 语义为**整包替换**（与 xlsx 导入的「按 ISBN 合并」不同）——调用前必须向用户确认。
 *
 * <p>v5.7 数据安全：先用 {@link BackupInspector} 把包读进内存并校验，
 * 校验不通过 / 备份来自更高版本时直接抛异常，一个字节都不落到 filesDir。
 */
public final class ZipRestore {

    private static final String TAG = "ZipRestore";

    /** 恢复结果统计 */
    public static class Result {
        public int books;  // 恢复的书籍数
        public int media;  // 恢复的媒体文件数
        /** 备份包没有 meta（v5.7 之前生成）⇒ 未能校验 */
        public boolean legacy;
        /** 备份的 App 版本低于当前版本（正常升级路径，恢复后由迁移补齐） */
        public int fromDbVersion;
        /** 包里带白名单设置且至少写入了一项（v5.9.5 第 1 项；不含 API 密钥与应用锁） */
        public boolean settingsApplied;
        /** 包里到底有没有 settings 块。与 settingsApplied 分开：老包是「没有」，新包可能是「有但没写成」 */
        public boolean settingsPresent;
        /** 恢复的想读条数；包里没这个键时保持 0 且 wishesPresent=false（本机清单原样留着） */
        public int wishes;
        public boolean wishesPresent;
    }

    private ZipRestore() {
    }

    /** 恢复期间图片的暂存目录名（在 filesDir 下，校验通过后才挪进正式目录） */
    private static final String STAGING_DIR = ".restore_staging";

    /** 把暂存目录里的文件逐个挪到 filesDir 对应位置，返回成功数 */
    private static int moveInto(File root, File dir, File filesDir, String filesDirCanonical) {
        int moved = 0;
        File[] children = dir.listFiles();
        if (children == null) {
            return 0;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                moved += moveInto(root, child, filesDir, filesDirCanonical);
                continue;
            }
            String relative = child.getAbsolutePath().substring(root.getAbsolutePath().length() + 1)
                    .replace(java.io.File.separatorChar, '/');
            File out = new File(filesDir, relative);
            try {
                String canonical = out.getCanonicalPath();
                if (!canonical.startsWith(filesDirCanonical + java.io.File.separator)) {
                    // 包里的路径想往外写（恶意/损坏包），拦掉并记一笔：少一张图要能查出来
                    Log.w(TAG, "跳过越界条目（落点在 filesDir 之外）：" + relative);
                    continue;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    Log.w(TAG, "目录建不出来，跳过这张图：" + relative);
                    continue;
                }
                if (!child.renameTo(out) && !copyThen(child, out)) {
                    Log.w(TAG, "既搬不动也拷不动，跳过这张图：" + relative);
                    continue;
                }
                moved++;
            } catch (IOException e) {
                // 单个文件挪不动就跳过，不因此放弃整次恢复
                Log.w(TAG, "读取落点路径失败，跳过这张图：" + relative, e);
            }
        }
        return moved;
    }

    private static boolean copyThen(File from, File to) {
        try (java.io.InputStream in = new java.io.FileInputStream(from);
             java.io.OutputStream out = new java.io.FileOutputStream(to)) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            return from.delete();
        } catch (IOException e) {
            return false;
        }
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
                    child.delete();
                }
            }
        }
        dir.delete();
    }

    /** 从 zip 备份恢复（需在后台线程调用；内部按 id 原样重写，恢复后藏书 id 与备份时一致） */
    public static Result restore(Context ctx, Uri uri, AppDatabase db) throws Exception {
        File filesDir = ctx.getFilesDir();
        String filesDirCanonical = filesDir.getCanonicalPath();
        // ---- 1. 流式读包（v5.8.20）：图片边解边写进暂存目录，校验和边读边算 ----
        // 校验没过就整个暂存目录删掉走人，正式目录与数据库一个字节都不动；
        // 内存占用也从「整包大小」降到 O(条目数)，1GB 的备份不再需要 1GB 堆。
        final File staging = new File(filesDir, STAGING_DIR);
        deleteRecursively(staging);
        if (!staging.mkdirs()) {
            throw new IOException("暂存目录创建失败");
        }
        InputStream rawIn = ctx.getContentResolver().openInputStream(uri);
        if (rawIn == null) {
            deleteRecursively(staging);
            throw new IOException("无法读取所选文件");
        }
        BackupInspector.Streaming entries;
        try (rawIn) {
            entries = BackupInspector.readStreaming(rawIn, relativeName -> {
                File out = new File(staging, relativeName);
                try {
                    if (!out.getCanonicalPath().startsWith(
                            staging.getCanonicalPath() + File.separator)) {
                        Log.w(TAG, "解包时丢弃越界条目：" + relativeName);
                        return null; // 目录穿越：这个条目直接丢弃
                    }
                } catch (IOException e) {
                    Log.w(TAG, "解包时算不出落点，丢弃该条目：" + relativeName, e);
                    return null;
                }
                File parent = out.getParentFile();
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    Log.w(TAG, "暂存子目录建不出来，丢弃该条目：" + relativeName);
                    return null;
                }
                try {
                    return new FileOutputStream(out);
                } catch (IOException e) {
                    Log.w(TAG, "暂存文件打不开，丢弃该条目：" + relativeName, e);
                    return null;
                }
            });
        } catch (Exception e) {
            Log.w(TAG, "解包中途失败，暂存目录整个删掉，正式目录与数据库未动", e);
            deleteRecursively(staging);
            throw e;
        }
        BackupInspector.Info info = BackupInspector.validateStreamingCurrent(entries);
        if (!info.ok()) {
            Log.w(TAG, "校验没过，整包作废（暂存目录已删，正式目录与数据库未动）：" + info.reject);
            deleteRecursively(staging);
            throw new IOException(info.reject);
        }

        // ---- 2. 校验通过后才把暂存文件挪进正式目录（同盘 rename，失败退化成复制） ----
        Result result = new Result();
        result.legacy = info.legacy();
        result.fromDbVersion = info.meta == null ? 0 : info.meta.dbVersion;
        result.media = moveInto(staging, staging, filesDir, filesDirCanonical);
        deleteRecursively(staging);

        // ---- 3. 事务内清空 + 重写（要么全成功，要么全失败） ----
        Gson gson = new Gson();
        JsonObject root = JsonParser.parseString(entries.dataJson).getAsJsonObject();
        String filesDirPath = filesDir.getAbsolutePath();

        db.runInTransaction(() -> {
            // 第 4.3 步：旧包没有 check_ins 键时整段跳过（不删、不写、不报错）
            final boolean hasCheckIns = root.has("check_ins")
                    && root.get("check_ins").isJsonArray();
            // 想读清单同一条纪律：v5.9.5 之前的包没有这个键，此时删表就等于把用户
            // 现有的想读清单清空——换新机恢复反而丢数据，所以缺键必须整段跳过
            final boolean hasWishes = root.has("wish_books")
                    && root.get("wish_books").isJsonArray();
            // 清空全部表
            db.bookDao().deleteAll();
            db.bookCopyDao().deleteAll();
            db.borrowRecordDao().deleteAll();
            db.noteImageDao().deleteAll();
            db.excerptDao().deleteAll();
            db.locationDao().deleteAll();
            db.sourceChannelDao().deleteAll();
            db.tagDao().deleteAll();
            db.categoryDao().deleteAll();
            db.countryDao().deleteAll();
            db.publisherDao().deleteAll();
            db.borrowerDao().deleteAll();
            if (hasCheckIns) {
                db.checkInDao().deleteAll();
            }
            if (hasWishes) {
                db.wishBookDao().deleteAll();
            }

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
            for (Excerpt e : readList(root, "excerpts", new TypeToken<List<Excerpt>>() {})) {
                e.imagePath = ZipBackup.toAbs(e.imagePath, filesDirPath);
                db.excerptDao().insert(e);
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
            // v4.8：旧备份里没有二级分类树（或被裁剪），恢复后幂等补种缺失节点
            CategorySeeder.seedIfMissing(db);
            for (Country x : readList(root, "countries", new TypeToken<List<Country>>() {})) {
                db.countryDao().insert(x);
            }
            for (Publisher x : readList(root, "publishers", new TypeToken<List<Publisher>>() {})) {
                db.publisherDao().insert(x);
            }
            for (Borrower x : readList(root, "borrowers", new TypeToken<List<Borrower>>() {})) {
                db.borrowerDao().insert(x);
            }
            // 第 4.3 步：一次批量写回；缺键时整段跳过，保持库里现有打卡不变
            // v5.9.1 第六步：早于 v26 的包里没有 seq，恢复后统一落到第 1 轮
            if (hasCheckIns) {
                List<com.bai.bookshelf.data.entity.CheckIn> restored =
                        readList(root, "check_ins",
                                new TypeToken<List<com.bai.bookshelf.data.entity.CheckIn>>() {});
                for (com.bai.bookshelf.data.entity.CheckIn c : restored) {
                    if (c.seq < 1) {
                        c.seq = 1;
                    }
                }
                db.checkInDao().insertAll(restored);
            }
            if (hasWishes) {
                List<WishBook> wishes = readList(root, "wish_books",
                        new TypeToken<List<WishBook>>() {});
                db.wishBookDao().insertAll(wishes);
                result.wishes = wishes.size();
            }
            result.wishesPresent = hasWishes;
        });
        // v5.9.5 第 1 项：数据落库后再动偏好——设置恢复失败绝不能让整包恢复失败
        boolean settingsApplied = false;
        final boolean settingsPresent = root.has("settings") && root.get("settings").isJsonObject();
        if (settingsPresent) {
            try {
                settingsApplied = SettingsBackup.restore(ctx, root.getAsJsonObject("settings"));
            } catch (Throwable t) {
                // 书目已经写进去了，只有偏好没回来——不能崩，但要留下痕迹
                Log.w(TAG, "备份里的设置项没能恢复（藏书已恢复，主题/备份开关等沿用本机现状）", t);
            }
        }
        result.settingsApplied = settingsApplied;
        result.settingsPresent = settingsPresent;
        Log.i(TAG, "恢复完成：" + result.books + " 种 · 图片 " + result.media
                + " 张 · 来自 DB v" + result.fromDbVersion
                + (result.legacy ? "（旧版包）" : "")
                + "· 设置" + (settingsApplied ? "已恢复" : "未恢复"));
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
}
