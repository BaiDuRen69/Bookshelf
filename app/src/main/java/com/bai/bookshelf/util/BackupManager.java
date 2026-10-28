package com.bai.bookshelf.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import com.bai.bookshelf.app.BookshelfApp;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 自动备份管理（v2.8 起改为 zip 整包备份）：
 * 保存「是否开启自动备份」与「额外备份文件夹（SAF Uri，可选）」配置，
 * 并在每次启动应用时静默执行备份（失败不打扰用户）。
 *
 * 备份规则（zip 包内含 data.json + media/，见 {@link ZipBackup}）：
 * 1. 藏书为 0 本时不备份（没内容没必要留档）；
 * 2. 新备份内容与最近一次备份完全相同则跳过（避免每次启动都产生重复文件）；
 * 3. 每个存放位置（应用内部存储 backups/ 目录 + 用户选的 SAF 文件夹）只保留最近 5 份，更旧的自动清理；
 * 4. 应用内部存储是默认存放位置（v2.8 用户要求）；SAF 文件夹是可选的外部副本。
 */
public final class BackupManager {

    private static final String PREFS = "backup_prefs";
    private static final String KEY_ENABLED = "auto_enabled";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_TREE_NAME = "tree_name";

    /** 备份文件名前缀（用于在两个存放位置里识别本应用产生的备份） */
    private static final String BACKUP_PREFIX = "小书房备份_";
    /** 备份文件扩展名 */
    private static final String BACKUP_EXT = ".zip";
    /** 应用内部存储备份目录（filesDir 下） */
    private static final String INTERNAL_DIR = "backups";
    /** 最多保留的备份份数 */
    private static final int MAX_KEEP = 5;

    private BackupManager() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static Uri getTreeUri(Context ctx) {
        String s = prefs(ctx).getString(KEY_TREE_URI, null);
        return s == null ? null : Uri.parse(s);
    }

    public static String getTreeName(Context ctx) {
        return prefs(ctx).getString(KEY_TREE_NAME, "");
    }

    /** 保存用户选择的备份文件夹（调用方需先 takePersistableUriPermission） */
    public static void setTree(Context ctx, Uri treeUri, String displayName) {
        prefs(ctx).edit()
                .putString(KEY_TREE_URI, treeUri.toString())
                .putString(KEY_TREE_NAME, displayName)
                .apply();
    }

    /**
     * 每次启动时调用：开了自动备份就静默生成 zip 备份。
     * 在后台线程执行，任何失败都只忽略不弹窗。
     */
    public static void autoBackupIfEnabled(Context ctx) {
        if (!isEnabled(ctx)) {
            return;
        }
        BookshelfApp app = BookshelfApp.get();
        // v4.4：走 bg 维护线程——zip 整包（全库 JSON + 全部封面图压缩）耗时可能以秒计，
        // 原先挤在 io 线程里会把书架首屏的字典查询堵在后面，「正在加载书架」久转不停
        app.bg(() -> {
            try {
                if (app.db().bookDao().count() == 0) {
                    return; // 规则 1：藏书为零不备份
                }
                if (backedUpToday(ctx)) {
                    return; // v4.45：每天最多一次，避免每次启动都压缩整库
                }
                byte[] zip = ZipBackup.create(ctx);
                // 默认存放位置：应用内部存储 backups/ 目录
                writeInternal(ctx, zip);
                // 可选外部副本：用户选过的 SAF 文件夹
                Uri treeUri = getTreeUri(ctx);
                if (treeUri != null) {
                    writeTree(ctx, treeUri, zip);
                }
            } catch (Exception ignored) {
                // 静默失败：文件夹授权失效等情况，用户下次手动备份时会发现
            }
        });
    }

    /** 最近一份内部备份（没有则 null） */
    public static File latestInternalBackup(Context ctx) {
        File[] all = listInternal(new File(ctx.getFilesDir(), INTERNAL_DIR));
        return all.length == 0 ? null : all[all.length - 1];
    }

    /** 今天是否已经备份过（自动备份每天最多一次） */
    private static boolean backedUpToday(Context ctx) {
        File latest = latestInternalBackup(ctx);
        if (latest == null) {
            return false;
        }
        java.util.Calendar a = java.util.Calendar.getInstance();
        a.setTimeInMillis(latest.lastModified());
        java.util.Calendar b = java.util.Calendar.getInstance();
        return a.get(java.util.Calendar.YEAR) == b.get(java.util.Calendar.YEAR)
                && a.get(java.util.Calendar.DAY_OF_YEAR) == b.get(java.util.Calendar.DAY_OF_YEAR);
    }

    /**
     * 手动立即备份（忽略「每天一次」限制）。
     *
     * @return 备份压缩包字节数；0 = 还没有藏书；-1 = 失败
     */
    public static long backupNow(Context ctx) {
        try {
            BookshelfApp app = BookshelfApp.get();
            if (app.db().bookDao().count() == 0) {
                return 0;
            }
            byte[] zip = ZipBackup.create(ctx);
            writeInternal(ctx, zip);
            Uri treeUri = getTreeUri(ctx);
            if (treeUri != null) {
                try {
                    writeTree(ctx, treeUri, zip);
                } catch (Exception ignored) {
                    // 外部文件夹授权失效不影响内部备份
                }
            }
            return zip.length;
        } catch (Exception e) {
            return -1;
        }
    }

    /** 预览一个 zip 备份：返回 {藏书册数, 解压后内容字节数}，读不出册数时为 -1 */
    public static long[] inspectBackup(Context ctx, Uri uri) {
        long bytes = 0;
        String json = null;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return new long[]{-1, -1};
            }
            try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(in)) {
                java.util.zip.ZipEntry entry;
                while ((entry = zis.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    byte[] data = readStream(zis);
                    bytes += data.length;
                    if ("data.json".equals(entry.getName())) {
                        json = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (Exception e) {
            return new long[]{-1, -1};
        }
        int books = -1;
        if (json != null) {
            try {
                org.json.JSONArray array = new org.json.JSONObject(json).optJSONArray("books");
                books = array == null ? 0 : array.length();
            } catch (Exception ignored) {
            }
        }
        return new long[]{books, bytes};
    }

    private static byte[] readStream(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) > 0) {
            out.write(buffer, 0, len);
        }
        return out.toByteArray();
    }

    // ======================= 应用内部存储 =======================

    /** 写入内部存储 backups/ 目录（带内容去重 + 轮换清理） */
    private static void writeInternal(Context ctx, byte[] zip) {
        File dir = new File(ctx.getFilesDir(), INTERNAL_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File[] existing = listInternal(dir);
        if (existing.length > 0) {
            File latest = existing[existing.length - 1];
            byte[] latestData = readFile(latest);
            if (latestData != null && Arrays.equals(latestData, zip)) {
                return; // 规则 2：内容没变，跳过
            }
        }
        File out = new File(dir, BACKUP_PREFIX + timestamp() + BACKUP_EXT);
        writeFile(out, zip);
        // 规则 3：只保留最近 MAX_KEEP 份
        File[] all = listInternal(dir);
        while (all.length > MAX_KEEP) {
            if (!all[0].delete()) {
                break; // 删不掉就停下，下轮再试
            }
            all = listInternal(dir);
        }
    }

    /** 内部备份文件按文件名（时间戳）升序 */
    private static File[] listInternal(File dir) {
        File[] files = dir.listFiles((d, name) ->
                name.startsWith(BACKUP_PREFIX) && name.endsWith(BACKUP_EXT));
        if (files == null) {
            return new File[0];
        }
        Arrays.sort(files, (x, y) -> x.getName().compareTo(y.getName()));
        return files;
    }

    // ======================= 备份文件管理（v4.23） =======================

    /**
     * 列出应用内部存储 backups/ 目录的全部备份文件（旧→新）。
     *
     * <p>审查 #3 修掉的缺口：内部备份此前是个「假安全网」——没有任何
     * 导出/分享入口，用户看不到也拿不走，卸载即全灭。本方法配合
     * 「我的 → 备份文件管理」把文件交还用户（查看 / 分享 / 删除）。
     */
    public static List<File> listInternalBackups(Context ctx) {
        return new ArrayList<>(Arrays.asList(listInternal(new File(ctx.getFilesDir(), INTERNAL_DIR))));
    }

    /** 删除一份内部备份文件。返回是否删除成功（文件不存在视为已删除）。 */
    public static boolean deleteInternalBackup(File f) {
        return !f.exists() || f.delete();
    }

    /** 备份文件名前缀（供管理页识别/展示用） */
    public static String backupPrefix() {
        return BACKUP_PREFIX;
    }

    // ======================= SAF 文件夹（外部副本） =======================

    /** 写入用户自选的 SAF 文件夹（带去重 + 轮换清理） */
    private static void writeTree(Context ctx, Uri treeUri, byte[] zip) throws Exception {
        List<BackupFile> existing = listBackups(ctx, treeUri);
        if (!existing.isEmpty()) {
            BackupFile latest = existing.get(existing.size() - 1);
            byte[] latestData = readAll(ctx, latest.uri);
            if (latestData != null && Arrays.equals(latestData, zip)) {
                return; // 规则 2：内容没变，跳过
            }
        }
        createAndWrite(ctx, treeUri, BACKUP_PREFIX + timestamp() + BACKUP_EXT, zip);
        // 规则 3：只保留最近 MAX_KEEP 份
        List<BackupFile> all = listBackups(ctx, treeUri);
        while (all.size() > MAX_KEEP) {
            BackupFile oldest = all.remove(0);
            try {
                DocumentsContract.deleteDocument(ctx.getContentResolver(), oldest.uri);
            } catch (Exception ignored) {
                break; // 删不掉就停下，下轮再试
            }
        }
    }

    /** 在 SAF 文件夹里新建文件并写入字节 */
    private static void createAndWrite(Context ctx, Uri treeUri, String fileName, byte[] data)
            throws Exception {
        android.net.Uri docUri = DocumentsContract.buildDocumentUriUsingTree(
                treeUri, DocumentsContract.getTreeDocumentId(treeUri));
        android.net.Uri created = DocumentsContract.createDocument(
                ctx.getContentResolver(), docUri, "application/zip", fileName);
        if (created == null) {
            throw new Exception("无法在所选文件夹创建文件，请重新选择文件夹");
        }
        try (java.io.OutputStream os = ctx.getContentResolver().openOutputStream(created)) {
            if (os == null) {
                throw new Exception("无法写入所选文件夹");
            }
            os.write(data);
        }
    }

    // ======================= 枚举与读取 =======================

    /** 文件夹里的一份备份文件 */
    private static class BackupFile {
        final String name;
        final Uri uri;

        BackupFile(String name, Uri uri) {
            this.name = name;
            this.uri = uri;
        }
    }

    /** 列出备份文件夹下所有「小书房备份_*.zip」，按文件名升序（时间戳命名 ⇒ 旧→新） */
    private static List<BackupFile> listBackups(Context ctx, Uri treeUri) {
        List<BackupFile> list = new ArrayList<>();
        try {
            Uri childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                    treeUri, DocumentsContract.getTreeDocumentId(treeUri));
            try (Cursor c = ctx.getContentResolver().query(childrenUri,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                    null, null, null)) {
                if (c == null) {
                    return list;
                }
                while (c.moveToNext()) {
                    String docId = c.getString(0);
                    String name = c.getString(1);
                    if (name != null && name.startsWith(BACKUP_PREFIX) && name.endsWith(BACKUP_EXT)) {
                        Uri docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId);
                        list.add(new BackupFile(name, docUri));
                    }
                }
            }
        } catch (Exception ignored) {
            // 枚举失败按空列表处理
        }
        list.sort((x, y) -> x.name.compareTo(y.name));
        return list;
    }

    /** 读取 SAF 文件全部字节；失败返回 null */
    private static byte[] readAll(Context ctx, Uri uri) {
        try (InputStream in = ctx.getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readFile(File f) {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static void writeFile(File f, byte[] data) {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(data);
        } catch (Exception ignored) {
            // 内部存储写失败只能放弃
        }
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
    }
}
