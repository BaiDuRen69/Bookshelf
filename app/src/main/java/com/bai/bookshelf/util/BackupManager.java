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
 * 备份规则（zip 包内含 data.json + media/ + meta.json，见 {@link ZipBackup}）：
 * 1. 藏书为 0 本时不备份（没内容没必要留档）；
 * 2. 新备份与最近一份备份的**内容校验和**相同则跳过（v5.7：meta 里带了打包时刻，
 *    不能再比字节）；
 * 3. 每个存放位置（应用内部存储 backups/ 目录 + 用户选的 SAF 文件夹）只保留最近 5 份，更旧的自动清理；
 * 4. 应用内部存储是默认存放位置（v2.8 用户要求）；SAF 文件夹是可选的外部副本。
 */
public final class BackupManager {

    private static final String PREFS = "backup_prefs";
    private static final String KEY_ENABLED = "auto_enabled";
    private static final String KEY_TREE_URI = "tree_uri";
    private static final String KEY_TREE_NAME = "tree_name";
    /** 外部副本最近一次写成功 / 写失败的时刻，以及失败原因（v5.8 诚实化） */
    private static final String KEY_SAF_OK_AT = "saf_ok_at";
    private static final String KEY_SAF_FAIL_AT = "saf_fail_at";
    private static final String KEY_SAF_FAIL_MSG = "saf_fail_msg";
    /** 用户关掉「备份太久没更新」提醒的时刻，30 天内不再打扰 */
    private static final String KEY_REMINDER_OFF_AT = "reminder_off_at";
    /** 最新备份超过这个天数就提醒一次 */
    public static final int STALE_DAYS = 30;
    /** 关掉提醒后安静多少天 */
    private static final int REMINDER_SNOOZE_DAYS = 30;

    /** 备份文件名前缀（用于在两个存放位置里识别本应用产生的备份） */
    private static final String BACKUP_PREFIX = "小书房备份_";
    /** 备份文件扩展名 */
    private static final String BACKUP_EXT = ".zip";
    /** 应用内部存储备份目录（filesDir 下） */
    private static final String INTERNAL_DIR = "backups";
    /** 最多保留的备份份数 */
    private static final int MAX_KEEP = 5;

    /** 一次备份的结果（v5.7：把 meta 带回去，UI 能说清「几本、校验和是否写下」） */
    public static class Outcome {
        /** 备份成功写出的 meta；null 表示没做成 */
        public BackupMeta meta;
        /** zip 字节数（meta 为 null 时无意义） */
        public long bytes;
        /** 藏书为空，无需备份 */
        public boolean empty;
        /** 备份失败 */
        public boolean failed;
        /** 内部备份成功但外部副本写失败（授权失效 / 空间不足） */
        public boolean safFailed;
        /** 外部副本失败原因，原样给用户看 */
        public String safError;
    }

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

    /** 外部副本最近一次写成功的时刻（0 = 从没成功过） */
    public static long safLastOkAt(Context ctx) {
        return prefs(ctx).getLong(KEY_SAF_OK_AT, 0L);
    }

    /**
     * 外部副本「设了文件夹但写不进去」。
     *
     * <p>以前 SAF 写失败被 catch 掉，用户以为每天都在备份，其实外部副本早就停了；
     * 现在把失败时间记下来，页面直接说「自 X 起未更新」。
     */
    public static boolean safBroken(Context ctx) {
        if (getTreeUri(ctx) == null) {
            return false;
        }
        return prefs(ctx).getLong(KEY_SAF_FAIL_AT, 0L) > safLastOkAt(ctx);
    }

    /** 最近一次外部副本失败的时刻 */
    public static long safLastFailAt(Context ctx) {
        return prefs(ctx).getLong(KEY_SAF_FAIL_AT, 0L);
    }

    public static String safFailMessage(Context ctx) {
        return prefs(ctx).getString(KEY_SAF_FAIL_MSG, "");
    }

    private static void markSafOk(Context ctx) {
        prefs(ctx).edit().putLong(KEY_SAF_OK_AT, System.currentTimeMillis())
                .remove(KEY_SAF_FAIL_MSG).apply();
    }

    private static void markSafFail(Context ctx, Exception e) {
        String msg = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        prefs(ctx).edit().putLong(KEY_SAF_FAIL_AT, System.currentTimeMillis())
                .putString(KEY_SAF_FAIL_MSG, msg).apply();
    }

    /** 用户关掉「备份太久没更新」提醒，30 天内不再提示 */
    public static void snoozeStaleReminder(Context ctx) {
        prefs(ctx).edit().putLong(KEY_REMINDER_OFF_AT, System.currentTimeMillis()).apply();
    }

    /** 最新备份超过 {@link #STALE_DAYS} 天、且用户最近没关掉提醒 */
    public static boolean staleReminderNeeded(Context ctx) {
        File latest = latestInternalBackup(ctx);
        if (latest == null) {
            // 从没备份过说明自动备份没开，属于另一件事，不在这里催
            return false;
        }
        long age = System.currentTimeMillis() - latest.lastModified();
        if (age < STALE_DAYS * 24L * 3600_000L) {
            return false;
        }
        long off = prefs(ctx).getLong(KEY_REMINDER_OFF_AT, 0L);
        return System.currentTimeMillis() - off >= REMINDER_SNOOZE_DAYS * 24L * 3600_000L;
    }

    /** 最新备份是多少天前（没有备份返回 -1） */
    public static long latestBackupAgeDays(Context ctx) {
        File latest = latestInternalBackup(ctx);
        return latest == null ? -1
                : (System.currentTimeMillis() - latest.lastModified()) / (24L * 3600_000L);
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
                ZipBackup.Bundle bundle = ZipBackup.create(ctx);
                // 默认存放位置：应用内部存储 backups/ 目录
                writeInternal(ctx, bundle.zip, bundle.meta.checksum);
                // 可选外部副本：用户选过的 SAF 文件夹
                Uri treeUri = getTreeUri(ctx);
                if (treeUri != null) {
                    try {
                        writeTree(ctx, treeUri, bundle.zip, bundle.meta.checksum);
                        markSafOk(ctx);
                    } catch (Exception e) {
                        // 内部备份已经写成，外部副本失败只记账不在后台弹窗；
                        // 我的页会显示「外部副本自 X 起未更新」
                        markSafFail(ctx, e);
                    }
                }
            } catch (Exception ignored) {
                // 内部备份失败：装不下/IO 异常，用户下次手动备份时能立刻看到
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
     * <p>v5.7：返回 {@link Outcome}，UI 能说清册数与字节数，而不只是一个数字。
     */
    public static Outcome backupNow(Context ctx) {
        Outcome o = new Outcome();
        try {
            BookshelfApp app = BookshelfApp.get();
            if (app.db().bookDao().count() == 0) {
                o.empty = true;
                return o;
            }
            ZipBackup.Bundle bundle = ZipBackup.create(ctx);
            o.meta = bundle.meta;
            writeInternal(ctx, bundle.zip, bundle.meta.checksum);
            Uri treeUri = getTreeUri(ctx);
            if (treeUri != null) {
                try {
                    writeTree(ctx, treeUri, bundle.zip, bundle.meta.checksum);
                    markSafOk(ctx);
                } catch (Exception e) {
                    // 外部副本失败不影响内部备份，但必须让用户知道它没写进去
                    markSafFail(ctx, e);
                    o.safFailed = true;
                    o.safError = safFailMessage(ctx);
                }
            }
            File latest = latestInternalBackup(ctx);
            o.bytes = latest == null ? bundle.zip.length : latest.length();
            return o;
        } catch (Exception e) {
            o.failed = true;
            return o;
        }
    }

    // ======================= 备份可信度（v5.7 数据安全） =======================

    /** 一份内部备份的校验结论 */
    public static class Verified {
        public File file;
        /** 整包校验结论（含 checksum 比对） */
        public BackupInspector.Info info;
        /** 读包失败（文件损坏 / 不是 zip） */
        public boolean unreadable;
    }

    /**
     * 逐个校验内部备份（「验证备份」用；整包解压，耗时以秒计，必须走后台线程）。
     *
     * @return 旧→新排序；没有备份时为空列表
     */
    public static List<Verified> verifyInternal(Context ctx) {
        List<Verified> out = new ArrayList<>();
        for (File f : listInternalBackups(ctx)) {
            Verified v = new Verified();
            v.file = f;
            try {
                v.info = BackupInspector.inspect(f);
            } catch (Exception e) {
                v.unreadable = true;
            }
            out.add(v);
        }
        return out;
    }

    /**
     * 一份备份的人话标注（只读 meta，不解压）：
     * {@code 128 本 · v5.6.1（DB 23）}；旧包读不出 meta 时返回 null 让调用方退回文件大小。
     */
    public static String describeBackup(File f) {
        BackupMeta meta = BackupInspector.readMeta(f);
        if (meta == null) {
            return null;
        }
        return meta.books + " 本 · " + meta.versionText();
    }

    /** 内容是否与最近一份备份一致（v5.7：比校验和；旧包无比对基准时退回比字节） */
    private static boolean unchanged(File latest, byte[] zip, String checksum) {
        BackupMeta prev = BackupInspector.readMeta(latest);
        if (prev != null && prev.hasChecksum()) {
            return prev.checksum.equalsIgnoreCase(checksum);
        }
        if (prev != null) {
            // 有 meta 但没有校验和（理论上只出现在半成品包）：保守起见按「变了」处理
            return false;
        }
        byte[] latestData = readFile(latest);
        return latestData != null && Arrays.equals(latestData, zip);
    }

    // ======================= 应用内部存储 =======================

    /** 写入内部存储 backups/ 目录（带内容去重 + 轮换清理） */
    private static void writeInternal(Context ctx, byte[] zip, String checksum) {
        File dir = new File(ctx.getFilesDir(), INTERNAL_DIR);
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        File[] existing = listInternal(dir);
        if (existing.length > 0
                && unchanged(existing[existing.length - 1], zip, checksum)) {
            return; // 规则 2：内容没变，跳过
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
    private static void writeTree(Context ctx, Uri treeUri, byte[] zip, String checksum)
            throws Exception {
        List<BackupFile> existing = listBackups(ctx, treeUri);
        if (!existing.isEmpty()) {
            BackupFile latest = existing.get(existing.size() - 1);
            BackupMeta prev;
            try (InputStream in = ctx.getContentResolver().openInputStream(latest.uri)) {
                prev = in == null ? null : BackupInspector.readMeta(in);
            } catch (Exception e) {
                prev = null;
            }
            if (prev != null && prev.hasChecksum()
                    && prev.checksum.equalsIgnoreCase(checksum)) {
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

    /** 读取内部备份文件全部字节；失败返回 null（旧备份无 meta 时的字节比对用） */
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
