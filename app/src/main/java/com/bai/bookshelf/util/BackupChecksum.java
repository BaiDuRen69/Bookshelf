package com.bai.bookshelf.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/**
 * 备份包内容校验和（v5.7 数据安全）：SHA-256。
 *
 * 规则：每个数据条目单独求 {@code h = SHA-256(条目名 UTF-8 + 0x00 + 内容)}，
 * 再把全部「条目名 + h」按条目名升序拼接求一次 SHA-256，得到总校验和。
 *
 * **Why 按条目名排序而不是按 zip 里的先后顺序**：备份 zip 常被微信 / 网盘原样转发，
 * 但也可能被压缩工具重新打包而改变条目顺序。按序汇总会把完好无损的备份误判成损坏、
 * 进而拒绝恢复；按名汇总对顺序不敏感，同时仍能抓出截断与内容改动。
 */
public final class BackupChecksum {

    public static final String ALGORITHM = "SHA-256";

    /** 条目名 → 该条目内容的十六进制摘要（TreeMap 保证升序） */
    private final Map<String, String> byEntry = new TreeMap<>();

    /** 新建条目摘要：调用方往里喂内容即可（流式写 zip 时用这个，避免整图进内存） */
    public static MessageDigest newEntryDigest(String entryName) {
        MessageDigest md = digest();
        md.update(entryName.getBytes(StandardCharsets.UTF_8));
        md.update((byte) 0);
        return md;
    }

    /** 条目内容已在内存里时直接计入 */
    public void add(String entryName, byte[] content) {
        MessageDigest md = newEntryDigest(entryName);
        md.update(content);
        byEntry.put(entryName, hex(md.digest()));
    }

    /** 流式路径：摘要已由 {@link #newEntryDigest} 喂完内容 */
    public void addDigest(String entryName, MessageDigest filled) {
        byEntry.put(entryName, hex(filled.digest()));
    }

    /** 总校验和（64 位小写十六进制）；空包也会给出稳定值 */
    public String finish() {
        MessageDigest md = digest();
        for (Map.Entry<String, String> e : byEntry.entrySet()) {
            md.update(e.getKey().getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(e.getValue().getBytes(StandardCharsets.US_ASCII));
            md.update((byte) 0);
        }
        return hex(md.digest());
    }

    /** 已计入的条目数（打包时用来填 meta.media） */
    public int entries() {
        return byEntry.size();
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
