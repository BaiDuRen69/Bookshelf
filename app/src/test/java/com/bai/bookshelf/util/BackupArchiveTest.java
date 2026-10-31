package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 备份包 meta + 校验和的端到端单测（v5.7 数据安全）：
 * 用真实的 {@link ZipBackup#writeZip} 装配，再用 {@link BackupInspector} 读包校验，
 * 覆盖「完好 / 篡改 / 旧包无 meta / 备份来自更高版本 / 缺 data.json」五种情况。
 */
public class BackupArchiveTest {

    private static final long CURRENT_CODE = 102L;
    private static final int CURRENT_DB = 23;

    private static final String DATA =
            "{\"books\":[{\"id\":1},{\"id\":2},{\"id\":3}],\"locations\":[{\"id\":1}]}";

    private static byte[] pack(String dataJson, List<ZipBackup.MediaItem> media,
                               BackupMeta meta) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ZipBackup.writeZip(bos, dataJson.getBytes(StandardCharsets.UTF_8), media, meta);
        return bos.toByteArray();
    }

    private static List<ZipBackup.MediaItem> media() {
        List<ZipBackup.MediaItem> list = new ArrayList<>();
        list.add(new ZipBackup.BytesItem(BackupFormat.MEDIA_PREFIX + "covers/cover_1.jpg",
                "jpeg-bytes-one".getBytes(StandardCharsets.UTF_8)));
        list.add(new ZipBackup.BytesItem(BackupFormat.MEDIA_PREFIX + "notes/note_2.png",
                "png-bytes-two".getBytes(StandardCharsets.UTF_8)));
        return list;
    }

    private static BackupMeta meta(long appCode, int dbVersion) {
        BackupMeta m = new BackupMeta();
        m.format = 1;
        m.appVersion = "5.6.1";
        m.appCode = appCode;
        m.dbVersion = dbVersion;
        m.createdAt = 1_762_000_000_000L;
        m.books = 3;
        return m;
    }

    private static BackupInspector.Entries entriesOf(byte[] zip) throws IOException {
        return BackupInspector.read(new ByteArrayInputStream(zip));
    }

    private static BackupInspector.Info validate(byte[] zip) throws IOException {
        return BackupInspector.validate(entriesOf(zip), CURRENT_CODE, CURRENT_DB);
    }

    @Test
    public void 完好备份校验通过并给出预览() throws Exception {
        BackupMeta meta = meta(CURRENT_CODE, CURRENT_DB);
        byte[] zip = pack(DATA, media(), meta);
        BackupInspector.Info info = validate(zip);

        assertTrue(info.ok());
        assertTrue(info.checksumOk);
        assertFalse(info.legacy());
        assertEquals(3, info.books);
        assertEquals(2, info.mediaCount);
        assertEquals(64, meta.checksum.length());
        assertEquals("校验通过", info.verifyText());
        assertEquals(meta.media, info.mediaCount);
        assertTrue(info.preview().contains("v5.6.1（DB 23）"));
        assertTrue(info.preview().contains("3 本"));
        // 读包算出的数据字节与 meta.bytes 同口径（都不含 meta 自己）
        assertEquals(meta.bytes, info.totalBytes);
        assertEquals(DATA.length() + "jpeg-bytes-one".length() + "png-bytes-two".length(),
                info.totalBytes);
    }

    /** 校验和只跟内容有关：条目顺序、打包时刻都不该影响它 */
    @Test
    public void 条目顺序与打包时刻不影响校验和() throws Exception {
        BackupMeta a = meta(CURRENT_CODE, CURRENT_DB);
        pack(DATA, media(), a);

        List<ZipBackup.MediaItem> shuffled = media();
        Collections.reverse(shuffled);
        BackupMeta b = meta(CURRENT_CODE, CURRENT_DB);
        b.createdAt = a.createdAt + 60_000;
        pack(DATA, shuffled, b);
        assertEquals(a.checksum, b.checksum);

        // 内容差一个字节就是另一个校验和
        BackupMeta c = meta(CURRENT_CODE, CURRENT_DB);
        pack(DATA.replace("\"id\":3", "\"id\":4"), media(), c);
        assertFalse(a.checksum.equals(c.checksum));
    }

    /** 篡改：内容换了但 meta 里还是旧校验和 ⇒ 拒绝恢复 */
    @Test
    public void 篡改内容被拦下() throws Exception {
        BackupMeta good = meta(CURRENT_CODE, CURRENT_DB);
        pack(DATA, media(), good);

        BackupMeta reused = meta(CURRENT_CODE, CURRENT_DB);
        byte[] tampered = pack(DATA.replace("\"id\":1", "\"id\":9"), media(), reused);
        // 把篡改包的 meta 换成原始校验和，模拟「文件被改动过但仍带着旧指纹」
        reused.checksum = good.checksum;
        BackupInspector.Entries e = entriesOf(tampered);
        e.metaJson = reused.toJson();
        BackupInspector.Info info = BackupInspector.validate(e, CURRENT_CODE, CURRENT_DB);

        assertFalse(info.checksumOk);
        assertNotNull(info.reject);
        assertTrue(info.reject.contains("校验"));
        assertEquals("校验不通过", info.verifyText());
    }

    /** v5.7 之前的包没有 meta：不拦截，但要说清「未校验」 */
    @Test
    public void 旧版备份无meta仍可恢复() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(BackupFormat.ENTRY_DATA);
            entry.setTime(0);
            zos.putNextEntry(entry);
            zos.write(DATA.getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        BackupInspector.Info info = validate(bos.toByteArray());

        assertTrue(info.ok());
        assertTrue(info.legacy());
        assertFalse(info.checksumOk);
        assertEquals(3, info.books);
        assertEquals("旧版备份（无 meta，未校验）", info.verifyText());
        assertTrue(info.preview().contains("旧版备份"));
    }

    /** 新版本 App / 新 schema 生成的备份拿到旧 App 上：拒绝并提示升级，不崩溃 */
    @Test
    public void 更高版本的备份要求先升级() throws Exception {
        byte[] zip = pack(DATA, media(), meta(CURRENT_CODE + 1, CURRENT_DB + 1));
        BackupInspector.Info info = validate(zip);

        assertFalse(info.ok());
        assertTrue(info.reject.contains("升级"));
        assertTrue(info.checksumOk); // 内容是完好的，只是版本更高
    }

    /** 不是本应用生成的 zip（缺 data.json）：给出人话原因 */
    @Test
    public void 缺dataJson识别为非本应用备份() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(bos)) {
            java.util.zip.ZipEntry entry = new java.util.zip.ZipEntry(BackupFormat.ENTRY_META);
            entry.setTime(0);
            zos.putNextEntry(entry);
            zos.write(meta(CURRENT_CODE, CURRENT_DB).toJson().getBytes(StandardCharsets.UTF_8));
            zos.closeEntry();
        }
        BackupInspector.Info info = validate(bos.toByteArray());

        assertFalse(info.ok());
        assertTrue(info.reject.contains("data.json"));
    }

    /** 有 meta 但没有校验和（半成品包）：按未校验放行，不算损坏 */
    @Test
    public void 无校验和按未校验放行() throws Exception {
        byte[] zip = pack(DATA, media(), meta(CURRENT_CODE, CURRENT_DB));
        BackupInspector.Entries e = entriesOf(zip);
        // 全新 meta 对象：没经过 writeZip，所以 checksum 仍为空 ⇒ 模拟「打包时没写下校验和」
        e.metaJson = meta(CURRENT_CODE, CURRENT_DB).toJson();
        BackupInspector.Info info = BackupInspector.validate(e, CURRENT_CODE, CURRENT_DB);

        assertTrue(info.ok());
        assertFalse(info.checksumOk);
        assertEquals("无校验和（未校验）", info.verifyText());
    }

    @Test
    public void meta读写往返() {
        BackupMeta m = meta(CURRENT_CODE, CURRENT_DB);
        m.media = 2;
        m.bytes = 1234;
        m.algorithm = BackupChecksum.ALGORITHM;
        m.checksum = "ab12";
        BackupMeta back = BackupMeta.parse(m.toJson());

        assertNotNull(back);
        assertEquals(CURRENT_CODE, back.appCode);
        assertEquals(CURRENT_DB, back.dbVersion);
        assertEquals(3, back.books);
        assertEquals(2, back.media);
        assertEquals("v5.6.1（DB 23）", back.versionText());
        assertTrue(back.hasChecksum());
        assertFalse(back.timeText().isEmpty());
        assertNull(BackupMeta.parse("不是 JSON"));
        assertNull(BackupMeta.parse(null));
    }

    /** 只读 meta 的快速通道（备份列表标注用，不解压整包） */
    @Test
    public void 顺序流读meta() throws Exception {
        byte[] zip = pack(DATA, media(), meta(CURRENT_CODE, CURRENT_DB));
        BackupMeta m = BackupInspector.readMeta(new ByteArrayInputStream(zip));

        assertNotNull(m);
        assertEquals(3, m.books);
        assertEquals(2, m.media);
        assertEquals(BackupChecksum.ALGORITHM, m.algorithm);
        assertTrue(m.hasChecksum());
    }
}
