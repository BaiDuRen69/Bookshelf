package com.bai.bookshelf.util;

import com.google.gson.Gson;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 备份包 {@code meta.json} 的内容（v5.7 数据安全）：恢复前预览、版本兼容判断与完整性校验用。
 *
 * 字段全部公开、由 Gson 直接读写；读侧只认已知字段，因此将来加字段不会让旧版本崩溃，
 * 而旧版本写出的包在新版本里读到的缺失字段为默认值。
 */
public class BackupMeta {

    /** meta 结构版本（当前 1） */
    public int format;
    /** 打包时的 versionName，如 {@code 5.6.1} */
    public String appVersion;
    /** 打包时的 versionCode */
    public long appCode;
    /** 打包时的 Room schema 版本 */
    public int dbVersion;
    /** 打包时间（epoch millis） */
    public long createdAt;
    /** 藏书册数 */
    public int books;
    /** 每日阅读打卡条数（第 4.3 步新增；旧包没有该字段，Gson 读到 0） */
    public int checkIns;
    /**
     * 想读清单条数。用 Integer 而不是 int：旧包没有这个字段，
     * 解析成 0 会与「包里真的是空清单」混为一谈，预览就分不清「不含」和「0 条」。
     */
    public Integer wishes;
    /** 图片文件条目数 */
    public int media;
    /** data.json + media 的原始字节数（未压缩） */
    public long bytes;
    public String algorithm;
    /** {@link BackupChecksum#finish()} 的十六进制结果 */
    public String checksum;

    public String toJson() {
        return new Gson().toJson(this);
    }

    /** 解析 meta.json；读不出返回 null（调用方按「旧备份、无元信息」处理） */
    public static BackupMeta parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            return new Gson().fromJson(json, BackupMeta.class);
        } catch (Exception e) {
            return null;
        }
    }

    /** 打包时间的人话形式（无时间戳时给出空串，避免显示 1970） */
    public String timeText() {
        return createdAt <= 0 ? ""
                : new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                        .format(new Date(createdAt));
    }

    /** 备份来源版本，如 {@code v5.6.1（DB 23）} */
    public String versionText() {
        String name = appVersion == null || appVersion.isEmpty() ? "?" : appVersion;
        return "v" + name + "（DB " + dbVersion + "）";
    }

    /** 校验和是否可用（旧包/半成品包为空串） */
    public boolean hasChecksum() {
        return checksum != null && !checksum.isEmpty();
    }
}
