package com.bai.bookshelf.util;

import android.content.Context;
import android.net.Uri;

import com.bai.bookshelf.BuildConfig;
import com.bai.bookshelf.R;
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
        /** 打卡条数；-1 表示读不出来 */
        public int checkIns = -1;
        /** 包里的打卡行早于精确记录（没有 seq 或 seq<=0）：恢复时统一按第 1 轮处理 */
        public boolean checkInsBeforeSeq;
        /** 包里有 settings 块（v5.9.5 起才有）；false ⇒ 相伴天数等偏好无法随包迁移 */
        public boolean settingsPresent;
        /** 包里有 wish_books 键（v5.9.5 起才有）；false ⇒ 老包，恢复时想读清单整段跳过 */
        public boolean wishesPresent;
        /** 想读条数；-1 表示读不出来（与「包里真的是空清单」区分开） */
        public int wishes = -1;
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
            String base;
            if (legacy()) {
                base = "旧版备份（无 meta，未校验）";
            } else if (!meta.hasChecksum()) {
                base = "无校验和（未校验）";
            } else {
                base = checksumOk ? "校验通过" : "校验不通过";
            }
            // v5.9.1 第六步：v25 之前的包没有 seq 列，恢复后全落第 1 轮，先把话说清楚
            return checkInsBeforeSeq ? base + "。该包早于精确记录，seq 按 1 处理" : base;
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
            sb.append("\n打卡 ").append(checkIns < 0 ? "未知" : checkIns + " 条");
            sb.append("\n完整性：").append(verifyText());
            return sb.toString();
        }

        /**
         * 恢复前预览 + 覆盖情况（设置块 / 想读清单）。
         * 无参那份保持纯函数，JVM 单测不碰 Context；新增两行走资源，
         * 不往 Java 里加中文字面量（count_cn_literals 护栏余量为 0）。
         */
        public String preview(Context ctx) {
            StringBuilder sb = new StringBuilder(preview());
            sb.append('\n').append(ctx.getString(settingsPresent
                    ? R.string.backup_preview_settings_yes
                    : R.string.backup_preview_settings_no));
            sb.append('\n');
            if (!wishesPresent) {
                sb.append(ctx.getString(R.string.backup_preview_wishes_absent));
            } else if (wishes < 0) {
                sb.append(ctx.getString(R.string.backup_preview_wishes_unknown));
            } else {
                sb.append(ctx.getString(R.string.backup_preview_wishes, wishes));
            }
            return sb.toString();
        }
    }

    private BackupInspector() {
    }

    /** 顺序读完整个 zip 到内存（JVM 单测与小包用；大包的恢复走 {@link #readStreaming}） */
    public static Entries read(InputStream in) throws IOException {
        Entries out = new Entries();
        Map<String, byte[]> collected = new LinkedHashMap<>();
        Streaming s = readStreaming(in, new MediaSink() {
            @Override
            public java.io.OutputStream open(String relativeName) {
                return new java.io.ByteArrayOutputStream() {
                    @Override
                    public void close() throws java.io.IOException {
                        collected.put(relativeName, toByteArray());
                    }
                };
            }
        });
        out.dataJson = s.dataJson;
        out.metaJson = s.metaJson;
        out.totalBytes = s.totalBytes;
        out.media.putAll(collected);
        return out;
    }

    /** 媒体条目的落点：边解压边写，别让整包图片堆在内存里 */
    public interface MediaSink {
        /**
         * 打开一个输出流承接该条目（调用方负责关闭）；返回 null 表示丢弃内容。
         *
         * @param relativeName 去掉 {@code media/} 前缀后的名字（相对 filesDir）
         */
        java.io.OutputStream open(String relativeName) throws IOException;
    }

    /** 流式读取结果：内存里只有 data/meta 两份 JSON 与逐条摘要，不含图片字节 */
    public static final class Streaming {
        public String dataJson;
        public String metaJson;
        public long totalBytes;
        public int mediaCount;
        /** 边解边算出来的总校验和（与 {@link BackupChecksum#finish()} 同规则） */
        public String checksum;
    }

    /**
     * 流式读包（v5.8.20）：media 交给 sink 落盘、校验和边读边算，
     * 恢复 1GB 备份的内存占用从「整包大小」降到 O(条目数)。
     *
     * @param sink 传 null 表示只看内容、不落盘（整包校验用）
     */
    public static Streaming readStreaming(InputStream in, MediaSink sink) throws IOException {
        Streaming out = new Streaming();
        BackupChecksum checksum = new BackupChecksum();
        byte[] buf = new byte[8192];
        try (ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                if (BackupFormat.ENTRY_META.equals(name)) {
                    // meta.json 自己不进校验和，也不计入 totalBytes（与打包端口径一致）
                    out.metaJson = new String(readAll(zis), StandardCharsets.UTF_8);
                } else if (BackupFormat.ENTRY_DATA.equals(name)) {
                    byte[] data = readAll(zis);
                    out.dataJson = new String(data, StandardCharsets.UTF_8);
                    checksum.add(name, data);
                    if (BackupFormat.isPayload(name)) {
                        out.totalBytes += data.length;
                    }
                } else if (name.startsWith(BackupFormat.MEDIA_PREFIX)) {
                    String relative = name.substring(BackupFormat.MEDIA_PREFIX.length());
                    java.security.MessageDigest md = BackupChecksum.newEntryDigest(name);
                    java.io.OutputStream target = sink == null ? null : sink.open(relative);
                    long written = 0;
                    int len;
                    while ((len = zis.read(buf)) > 0) {
                        md.update(buf, 0, len);
                        if (target != null) {
                            target.write(buf, 0, len);
                        }
                        written += len;
                    }
                    if (target != null) {
                        target.close();
                    }
                    checksum.addDigest(name, md);
                    out.mediaCount++;
                    if (BackupFormat.isPayload(name)) {
                        out.totalBytes += written;
                    }
                } else {
                    // 不认识的条目：跳过，也不计入校验和（打包端同样没算它）
                    while (zis.read(buf) > 0) {
                        // 丢弃
                    }
                }
            }
        }
        out.checksum = checksum.finish();
        return out;
    }

    /** 按当前 App 版本校验 */
    public static Info validateCurrent(Entries entries) {
        return validate(entries, BuildConfig.VERSION_CODE, AppDatabase.VERSION);
    }

    /** 按当前 App 版本校验（流式读包的结果） */
    public static Info validateStreamingCurrent(Streaming s) {
        return validateStreaming(s, BuildConfig.VERSION_CODE, AppDatabase.VERSION);
    }

    /** 校验流式读包结果：校验和读的时候就已经算好，不必把图片留在内存里重算 */
    public static Info validateStreaming(Streaming s, long currentAppCode, int currentDbVersion) {
        return validateCore(s.dataJson, s.metaJson, s.mediaCount, s.totalBytes, s.checksum,
                currentAppCode, currentDbVersion);
    }

    /**
     * 校验包内容。
     *
     * @param currentAppCode 当前 App 的 versionCode
     * @param currentDbVersion 当前 Room schema 版本
     */
    public static Info validate(Entries e, long currentAppCode, int currentDbVersion) {
        // 缺 data.json 时不重算：recompute 需要它，且这种包本来就该直接拒
        String checksum = e.dataJson == null ? null : recompute(e);
        return validateCore(e.dataJson, e.metaJson, e.media.size(), e.totalBytes, checksum,
                currentAppCode, currentDbVersion);
    }

    private static Info validateCore(String dataJson, String metaJson, int mediaCount,
                                     long totalBytes, String checksum,
                                     long currentAppCode, int currentDbVersion) {
        Info i = new Info();
        i.totalBytes = totalBytes;
        i.mediaCount = mediaCount;
        i.meta = BackupMeta.parse(metaJson);
        if (dataJson == null || dataJson.trim().isEmpty()) {
            i.reject = "备份文件缺少 data.json，不是小书房生成的 zip 备份";
            return i;
        }
        i.books = countBooks(dataJson);
        i.checkIns = countCheckIns(dataJson);
        i.checkInsBeforeSeq = checkInsWithoutSeq(dataJson);
        readCoverage(dataJson, i);
        if (i.books < 0 && i.meta != null) {
            i.books = i.meta.books;
        }
        if (i.wishes < 0 && i.meta != null && i.meta.wishes != null) {
            i.wishes = i.meta.wishes;
        }
        if (i.meta == null) {
            return i; // 旧备份：没有可校验的东西，按内容恢复
        }
        if (!i.meta.hasChecksum()) {
            return i;
        }
        i.checksumOk = i.meta.checksum.equalsIgnoreCase(checksum);
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

    /** 整包校验（含解压，耗时以秒计）：文件入口。流式读，不把图片读进内存 */
    public static Info inspect(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            return validateStreamingCurrent(readStreaming(in, null));
        }
    }

    /** 整包校验：用户选中的 Uri（SAF / 下载目录） */
    public static Info inspect(Context ctx, Uri uri) throws IOException {
        InputStream in = ctx.getContentResolver().openInputStream(uri);
        if (in == null) {
            throw new IOException("无法读取所选文件");
        }
        try {
            return validateStreamingCurrent(readStreaming(in, null));
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

    /** 从 data.json 数打卡条数；旧包没有该键按 0 处理，不报错 */
    private static int countCheckIns(String dataJson) {
        try {
            com.google.gson.JsonObject o = JsonParser.parseString(dataJson).getAsJsonObject();
            return o.has("check_ins") && o.get("check_ins").isJsonArray()
                    ? o.getAsJsonArray("check_ins").size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 一次解析读出覆盖情况（设置块 / 想读键 / 想读条数）。
     * 三个字段共用一次解析：data.json 可能有上千本书，为每个字段各解析一遍整包太贵。
     */
    private static void readCoverage(String dataJson, Info i) {
        try {
            com.google.gson.JsonObject o = JsonParser.parseString(dataJson).getAsJsonObject();
            i.settingsPresent = o.has("settings") && o.get("settings").isJsonObject();
            i.wishesPresent = o.has("wish_books") && o.get("wish_books").isJsonArray();
            i.wishes = i.wishesPresent ? o.getAsJsonArray("wish_books").size() : -1;
        } catch (Exception e) {
            i.settingsPresent = false;
            i.wishesPresent = false;
            i.wishes = -1;
        }
    }

    /** 包里只要有打卡行缺 seq（或 seq<=0），恢复时就该按第 1 轮处理并提示 */
    private static boolean checkInsWithoutSeq(String dataJson) {
        try {
            com.google.gson.JsonObject o = JsonParser.parseString(dataJson).getAsJsonObject();
            if (!o.has("check_ins") || !o.get("check_ins").isJsonArray()) {
                return false;
            }
            for (com.google.gson.JsonElement e : o.getAsJsonArray("check_ins")) {
                if (!e.isJsonObject()) {
                    continue;
                }
                com.google.gson.JsonObject row = e.getAsJsonObject();
                if (!row.has("seq")) {
                    return true;
                }
                try {
                    if (row.get("seq").getAsInt() <= 0) {
                        return true;
                    }
                } catch (Exception ignored) {
                    return true; // 非数字的 seq 同样不可用
                }
            }
            return false;
        } catch (Exception e) {
            return false;
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
