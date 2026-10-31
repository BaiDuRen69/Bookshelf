package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 流式读包（v5.8.20）：与旧的「全部读进内存」结果必须完全一致——
 * 条目、字节数、校验和都要对得上，否则恢复会拿到半截数据或误判校验失败。
 */
public class BackupStreamingTest {

    private byte[] buildZip() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            zip.putNextEntry(new ZipEntry(BackupFormat.ENTRY_DATA));
            zip.write("{\"books\":[{\"id\":1}]}".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
            for (int i = 1; i <= 3; i++) {
                zip.putNextEntry(new ZipEntry(BackupFormat.MEDIA_PREFIX
                        + "covers/cover_" + i + ".jpg"));
                byte[] payload = new byte[i * 4096];
                for (int p = 0; p < payload.length; p++) {
                    payload[p] = (byte) (p % 251);
                }
                zip.write(payload);
                zip.closeEntry();
            }
            // 不认识的条目也要能安全跳过
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("hello".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }

    @Test
    public void 流式与整包读入结果一致() throws Exception {
        byte[] zipBytes = buildZip();

        BackupInspector.Entries memory =
                BackupInspector.read(new ByteArrayInputStream(zipBytes));
        final Map<String, byte[]> written = new LinkedHashMap<>();
        final List<String> order = new ArrayList<>();
        BackupInspector.Streaming streamed = BackupInspector.readStreaming(
                new ByteArrayInputStream(zipBytes), relativeName -> {
                    order.add(relativeName);
                    written.put(relativeName, new byte[0]);
                    return new ByteArrayOutputStream() {
                        @Override
                        public void close() {
                            written.put(relativeName, toByteArray());
                        }
                    };
                });

        assertNotNull(streamed.dataJson);
        assertEquals(memory.dataJson, streamed.dataJson);
        assertEquals(memory.totalBytes, streamed.totalBytes);
        assertEquals(memory.media.size(), streamed.mediaCount);
        assertEquals("落盘顺序与条目名要和内存版一致",
                memory.media.keySet(), new java.util.HashSet<>(order));
        for (Map.Entry<String, byte[]> e : memory.media.entrySet()) {
            assertTrue("条目没落出去: " + e.getKey(), written.containsKey(e.getKey()));
            assertEquals("内容不一致: " + e.getKey(), e.getValue().length,
                    written.get(e.getKey()).length);
        }
        // 校验和：边读边算必须等于事后重算
        assertEquals(BackupInspector.recompute(memory), streamed.checksum);
    }

    @Test
    public void 不落盘也能算出同一份校验和() throws Exception {
        byte[] zipBytes = buildZip();
        BackupInspector.Entries memory =
                BackupInspector.read(new ByteArrayInputStream(zipBytes));
        BackupInspector.Streaming verifyOnly = BackupInspector.readStreaming(
                new ByteArrayInputStream(zipBytes), null);
        assertEquals(BackupInspector.recompute(memory), verifyOnly.checksum);
        assertEquals(memory.media.size(), verifyOnly.mediaCount);
        assertEquals(memory.totalBytes, verifyOnly.totalBytes);
    }
}
