package com.bai.bookshelf.util;

import android.content.Context;

import com.bai.bookshelf.data.AppDatabase;

import java.io.File;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 备份前置指纹（v5.8.21 评审 #7）：用一次「stat + count」的开销，换掉一次整包压缩。
 *
 * <p>自动备份原先每天启动都要先把 data.json + 全部封面打成 zip、算完校验和，
 * 才发现内容和上一份一样（{@link BackupManager} 规则 2）。这里先把决定内容的
 * 东西摘要一遍——库文件的时间戳/大小、各表行数、图片目录的逐文件路径/大小/修改时间——
 * 指纹和上次备份成功时一致，就认定「没变」，连打包都不做。
 *
 * <p>方向性保守：任何取不到数据的情况一律返回 {@code null}，调用方按「变了」处理照常打包。
 * 宁可多做一次备份，也不能少做一次。
 */
public final class BackupFingerprint {

    private BackupFingerprint() {
    }

    /**
     * 计算当前状态的指纹（需在后台线程调用：要读库、要遍历图片目录）。
     *
     * @param db 主库；为 null 时只按文件算
     * @return 64 位十六进制摘要；任何异常返回 null（视为「内容变了」）
     */
    public static String of(Context ctx, AppDatabase db) {
        try {
            StringBuilder sb = new StringBuilder(1024);
            appendDatabase(sb, ctx);
            appendRows(sb, db);
            appendMedia(sb, ctx);
            // v5.9.5 第 1 项：必须把设置算进来，否则「只改设置没改库」会被当成内容没变跳过
            appendSettings(sb, ctx);
            if (sb.length() == 0) {
                return null;
            }
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return BackupChecksum.hex(md.digest(sb.toString()
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Throwable ignored) {
            return null; // 摘要算法缺失 / OOM / IO：按「变了」处理
        }
    }

    /** 库文件（含 WAL）：任何一次写库都会动它们的时间戳或大小 */
    private static void appendDatabase(StringBuilder sb, Context ctx) {
        String name = AppDatabase.DB_NAME;
        for (String suffix : new String[]{"", "-wal", "-shm"}) {
            File f = ctx.getDatabasePath(name + suffix);
            sb.append("db|").append(suffix).append('|')
                    .append(f.lastModified()).append('|').append(f.length()).append('\n');
        }
    }

    /** 各表行数：库文件被复用导致大小恰好不变时的第二重信号 */
    private static void appendRows(StringBuilder sb, AppDatabase db) {
        if (db == null) {
            return;
        }
        sb.append("t|books|").append(db.bookDao().count()).append('\n');
        sb.append("t|copies|").append(db.bookCopyDao().count()).append('\n');
        sb.append("t|loans|").append(db.borrowRecordDao().count()).append('\n');
        sb.append("t|note_images|").append(db.noteImageDao().count()).append('\n');
        // 摘抄自带「行数/最大 updatedAt」摘要：改一行内容也看得见
        sb.append("t|excerpts|").append(db.excerptDao().fingerprint()).append('\n');
        sb.append("t|locations|").append(db.locationDao().count()).append('\n');
        sb.append("t|channels|").append(db.sourceChannelDao().count()).append('\n');
        sb.append("t|tags|").append(db.tagDao().count()).append('\n');
        sb.append("t|categories|").append(db.categoryDao().count()).append('\n');
        sb.append("t|countries|").append(db.countryDao().count()).append('\n');
        sb.append("t|publishers|").append(db.publisherDao().count()).append('\n');
        sb.append("t|borrowers|").append(db.borrowerDao().count()).append('\n');
        // 第 ④ 步：打卡变化必须触发自动备份，否则指纹相同会跳过整包
        sb.append("t|check_ins|").append(db.checkInDao().countAll()).append('\n');
        // 想读清单同理：只改想读不改藏书时，库文件时间戳会变，但行数信号也得跟上
        sb.append("t|wish_books|").append(db.wishBookDao().count()).append('\n');
    }

    /** 图片目录：与 ZipBackup 打包完全同一套取文件规则，两边看到的必须是同一个集合 */
    private static void appendMedia(StringBuilder sb, Context ctx) {
        File filesDir = ctx.getFilesDir();
        List<File> found = new ArrayList<>();
        ZipBackup.collectImages(filesDir, filesDir, found);
        Collections.sort(found, new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return ZipBackup.relPath(filesDir, a).compareTo(ZipBackup.relPath(filesDir, b));
            }
        });
        sb.append("m|n|").append(found.size()).append('\n');
        long bytes = 0;
        for (File f : found) {
            bytes += f.length();
            sb.append("m|").append(ZipBackup.relPath(filesDir, f)).append('|')
                    .append(f.length()).append('|').append(f.lastModified()).append('\n');
        }
        sb.append("m|sum|").append(bytes).append('\n');
    }

    /**
     * 白名单设置：与 {@link SettingsBackup#export} 共用同一张表。
     * 摘要算法缺失时抛到这里，整颗指纹返回 null，自动备份照常做一次。
     */
    private static void appendSettings(StringBuilder sb, Context ctx) {
        sb.append("s|").append(SettingsBackup.fingerprint(ctx)).append('\n');
    }
}
