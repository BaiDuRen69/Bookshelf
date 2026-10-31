package com.bai.bookshelf.util;

/**
 * zip 备份包的条目约定（v5.7 数据安全）：打包、解包、校验共用同一份常量。
 *
 * 包结构：{@code data.json}（全部表数据）+ {@code media/<相对路径>}（图片）
 * + {@code meta.json}（v5.7 起的元信息与校验和）。
 */
public final class BackupFormat {

    public static final String ENTRY_DATA = "data.json";
    /** v5.7 新增；更早的备份包里没有这个条目，按「未校验的旧备份」处理 */
    public static final String ENTRY_META = "meta.json";
    public static final String MEDIA_PREFIX = "media/";

    private BackupFormat() {
    }

    /**
     * 该条目是否属于「被校验的数据」。
     * meta.json 自身不能参与校验和，否则写进去的值再也算不回来。
     */
    public static boolean isPayload(String entryName) {
        return ENTRY_DATA.equals(entryName) || entryName.startsWith(MEDIA_PREFIX);
    }

    /** media 条目名去掉前缀后的相对 filesDir 路径 */
    public static String stripMedia(String entryName) {
        return entryName.startsWith(MEDIA_PREFIX)
                ? entryName.substring(MEDIA_PREFIX.length()) : entryName;
    }
}
