package com.bai.bookshelf.util;

import android.content.Context;
import android.net.Uri;

import com.bai.bookshelf.BuildConfig;
import com.bai.bookshelf.data.AppDatabase;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 读备份包 → 校验完整性 → 给出恢复前预览（v5.7 数据安全）。
 *
 * 核心 {@link #validate} 是纯函数（传入当前 app/DB 版本），可在 JVM 单测里跑；
 * 带 Context 的重载只负责取流和填当前版本。
 */
public final class BackupInspector {

    /** 解出来的包内容：media 的 key 已去掉 {@code media/} 前缀 */
    public static class Entries {
        public String dataJson;
        public String metaJson;
        public final LinkedHashMap<String, byte[]> media = new LinkedHashMap<>();
        /** 各条目未压缩字节数之和 */
        public long totalBytes;
    }

    /** 校验与预览结论 */
    public static class Info {
        /** null ⇒ v5.7 之前的旧备份 */
        public BackupMeta meta;
        public boolean checksumOk;
        /** 藏书册数；-1 表示读不出来 */
        public int books = -1;
        public long totalBytes;
        public int mediaCount;
        /** 不可恢复的人话原因；null ⇒ 允许恢复 */
        public String reject;

        public boolean ok() {
            return reject == null;
        }

        /** 旧备份（无 meta）⇒ 只能按内容恢复，无法校验 */
        public boolean legacy() {
            return meta == null;
        }

        /** 完整性一栏的人话结论 */
        public String verifyText() {
            if (legacy()) {
                return "旧版备份（无 meta，未校验）";
            }
            if (!meta.hasChecksum()) {
                return "无校验和（未校验）";
            }
            return checksumOk ? "校验通过" : "校验不通过";
        }

        /** 恢复前预览：版本 / 时间 / 册数 / 完整性 */
        public String preview() {
            StringBuilder sb = new StringBuilder();
            sb.append(legacy() ? "备份版本：旧版备份（v2.8–v5.6）"
                    : "备份版本：" + meta.versionText());
            if (!legacy() && !meta.timeText().isEmpty()) {
                sb.append("\n备份时间：").append(meta.timeText());
            }
            sb.append("\n藏书：").append(books < 0 ? "未知" : books + " 种");
            if (mediaCount > 0) {
                sb.append(" · 图片 ").append(mediaCount).append(" 个");
            }
            sb.append("\n完整性：").append(verifyText());
            return sb.toString();
        }
    }

    private BackupInspector() {
    }

    /** 顺序读完整个 zip（恢复与整包校验用；调用方需在后台线程） */
    public static Entries read(InputStream in) throws IOException {
        Entries out = new Entries();
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                byte[] data = readAll(zis);
                String name = entry.getName();
                // meta.json 自己不算是「数据」：让 totalBytes 与 meta.bytes 同口径
                if (BackupFormat.isPayload(name)) {
                    out.totalBytes += data.length;
                }
                if (BackupFormat.ENTRY_DATA.equals(name)) {
                    out.dataJson = new String(data, StandardCharsets.UTF_8);
                } else if (BackupFormat.ENTRY_META.equals(name)) {
                    out.metaJson = new String(data, StandardCharsets.UTF_8);
                } else if (name.startsWith(BackupFormat.MEDIA_PREFIX)) {
                    out.media.put(name.substring(BackupFormat.MEDIA_PREFIX.length()), data);
                }
            }
        }
        return out;
    }

    /** 按当前 App 版本校验 */
    public static Info validateCurrent(Entries entries) {
        return validate(entries, BuildConfig.VERSION_CODE, AppDatabase.VERSION);
    }

    /**
     * 校验包内容。
     *
     * @param currentAppCode 当前 App 的 versionCode
     * @param currentDbVersion 当前 Room schema 版本
     */
    public static Info validate(Entries e, long currentAppCode, int currentDbVersion) {
        Info i = new Info();
        i.totalBytes = e.totalBytes;
        i.mediaCount = e.media.size();
        i.meta = BackupMeta.parse(e.metaJson);
        if (e.dataJson == null || e.dataJson.trim().isEmpty()) {
            i.reject = "备份文件缺少 data.json，不是小书房生成的 zip 备份";
            return i;
        }
        i.books = countBooks(e.dataJson);
        if (i.books < 0 && i.meta != null) {
            i.books = i.meta.books;
        }
        if (i.meta == null) {
            return i; // 旧备份：没有可校验的东西，按内容恢复
        }
        if (i.meta.hasChecksum()) {
            i.checksumOk = i.meta.checksum.equalsIgnoreCase(recompute(e));
        } else {
            return i;
        }
        if (!i.checksumOk) {
            i.reject = "校验和不通过：文件可能被截断或改动过，已拒绝恢复";
            return i;
        }
        if (i.meta.appCode > currentAppCode || i.meta.dbVersion > currentDbVersion) {
            i.reject = "这份备份来自更新的版本 " + i.meta.versionText()
                    + "，当前 App 无法安全恢复，请先升级小书房";
            return i;
        }
        return i;
    }

    /** 用包内容重算校验和 */
    public static String recompute(Entries e) {
        BackupChecksum c = new BackupChecksum();
        c.add(BackupFormat.ENTRY_DATA, e.dataJson.getBytes(StandardCharsets.UTF_8));
        for (Map.Entry<String, byte[]> m : e.media.entrySet()) {
            c.add(BackupFormat.MEDIA_PREFIX + m.getKey(), m.getValue());
        }
        return c.finish();
    }

    /** 整包校验（含解压，耗时以秒计）：文件入口 */
    public static Info inspect(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return validateCurrent(read(in));
        }
    }

    /** 整包校验：用户选中的 Uri（SAF / 下载目录） */
    public static Info inspect(Context ctx, Uri uri) throws IOException {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("无法读取所选文件");
        }
        try {
            return validateCurrent(read(in));
        } finally {
            in.close();
        }
    }

    /**
     * 只读 meta.json（随机访问，不解压整包）：
     * 备份列表标注与「内容没变就跳过」的去重比较用。读不出返回 null。
     */
    public static BackupMeta readMeta(File f) {
        try (ZipFile zip = new ZipFile(f)) {
            ZipEntry entry = zip.getEntry(BackupFormat.ENTRY_META);
            if (entry == null) {
                return null;
            }
            try (InputStream in = zip.getInputStream(entry)) {
                return BackupMeta.parse(new String(readAll(in), StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** 只读 meta.json（顺序流，SAF 外部副本用） */
    public static BackupMeta readMeta(InputStream in) {
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (BackupFormat.ENTRY_META.equals(entry.getName())) {
                    return BackupMeta.parse(
                            new String(readAll(zis), StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            return null;
        }
        return null;
    }

    /** 从 data.json 数藏书（旧备份没有 meta 时的册数来源） */
    private static int countBooks(String dataJson) {
        try {
            return JsonParser.parseString(dataJson).getAsJsonObject()
                    .getAsJsonArray("books").size();
        } catch (Exception e) {
            return -1;
        }
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
