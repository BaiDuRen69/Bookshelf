package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 恢复流式化的内存对比（v5.8.20）：同一份 24MB 备份，旧路径要把图片全留在内存，
 * 新路径边解边落盘。这条测试就是那笔「1GB 备份不再要 1GB 堆」的账。
 */
@RunWith(AndroidJUnit4.class)
public class BackupMemoryTest {

    private static final int PIECES = 6;
    private static final int PIECE_BYTES = 4 * 1024 * 1024;

    private byte[] buildZip() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(PIECES * PIECE_BYTES / 2);
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry(BackupFormat.ENTRY_DATA));
            zip.write("{\"books\":[]}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            byte[] payload = new byte[PIECE_BYTES];
            for (int i = 0; i < PIECES; i++) {
                zip.putNextEntry(new ZipEntry(BackupFormat.MEDIA_PREFIX
                        + "covers/big_" + i + ".jpg"));
                zip.write(payload);
                zip.closeEntry();
            }
        }
        return bos.toByteArray();
    }

    private long heapBytes() {
        Runtime rt = Runtime.getRuntime();
        System.gc();
        try {
            Thread.sleep(120);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        return rt.totalMemory() - rt.freeMemory();
    }

    @Test
    public void 流式读包不把图片留在堆里() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        byte[] zipBytes = buildZip();
        long mediaBytes = (long) PIECES * PIECE_BYTES;

        File staging = new File(ctx.getCacheDir(), "stream_staging");
        deleteRecursively(staging);
        assertTrue(staging.mkdirs());
        long before = heapBytes();
        BackupInspector.Streaming result = BackupInspector.readStreaming(
                new ByteArrayInputStream(zipBytes), relativeName -> {
                    File out = new File(staging, relativeName.replace('/', '_'));
                    return new FileOutputStream(out);
                });
        long held = heapBytes() - before;
        deleteRecursively(staging);

        // 内容确实读到了（6 张 4MB），但堆里只该留下 data.json 与逐条摘要
        assertEquals(PIECES, result.mediaCount);
        assertTrue("落盘字节数对不上: " + result.totalBytes,
                result.totalBytes >= mediaBytes);
        assertTrue("流式读包按住了 " + held + " 字节，接近整包 " + mediaBytes
                + " 说明没流起来", held < mediaBytes / 8);
    }

    private void deleteRecursively(File dir) {
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
}
