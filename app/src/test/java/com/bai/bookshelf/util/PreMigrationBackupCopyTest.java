package com.bai.bookshelf.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 升级前副本「失败就不算留下」的不变式（EP-I1 T1）。
 *
 * <p>原先 {@code copyFile} 把异常整个吞掉：磁盘写满时目录建出来了、主库是 0 字节，
 * 但 {@code prepareBeforeOpen} 照样返回 -1，{@code BookshelfApp} 照样记 db_version。
 * 后果有两层——下次启动认为「已经保护过」不再复制，修复页还把空壳当成「有副本」
 * 点亮恢复按钮，用户点下去才失败。
 *
 * <p>这里只测纯文件决策 {@code copySnapshot} / {@code isUsableCopy} / {@code shouldMarkOpened}，
 * 与 {@link DbRepairRollbackTest} 同一套路：要 Context 与 SharedPreferences 的部分留在原方法里。
 */
public class PreMigrationBackupCopyTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void 源库读不出来时不留空壳目录也不落成功标记() throws Exception {
        // 用一个「目录」冒充主库：FileInputStream 打开目录必抛，走的就是复制失败那条路
        File srcIsDir = folder.newFolder("bookshelf.db");
        File target = new File(folder.getRoot(), "20261111_120000_db24_to_26");

        assertFalse("复制抛异常就不能算留下副本", PreMigrationBackup.copySnapshot(srcIsDir, target));

        assertFalse("半成品目录必须整个删掉，不留空壳", target.exists());
        assertFalse("下游据此判为「无副本」", PreMigrationBackup.isUsableCopy(target));
        assertFalse("副本没留下就不许记 db_version",
                PreMigrationBackup.shouldMarkOpened(PreMigrationBackup.COPY_FAILED));
    }

    @Test
    public void 复制出0字节主库时整份作废() throws Exception {
        File src = newFile("bookshelf.db", "");
        File target = new File(folder.getRoot(), "20261111_120100_db24_to_26");

        assertFalse("0 字节主库不是副本", PreMigrationBackup.copySnapshot(src, target));

        assertFalse("空壳目录要删掉", target.exists());
        assertFalse(PreMigrationBackup.isUsableCopy(target));
    }

    @Test
    public void 正常复制留下可用副本且主库与wal都到位() throws Exception {
        File src = newFile("bookshelf.db", "SQLite format 3 主库内容");
        File wal = newFile("bookshelf.db-wal", "未落盘的写入");
        File target = new File(folder.getRoot(), "20261111_120200_db24_to_26");

        assertTrue(PreMigrationBackup.copySnapshot(src, target));

        assertTrue("这次才算真留下了副本", PreMigrationBackup.isUsableCopy(target));
        assertArrayEquals("主库要逐字节一致", Files.readAllBytes(src.toPath()),
                Files.readAllBytes(new File(target, "bookshelf.db").toPath()));
        assertArrayEquals("wal 必须跟着走，否则副本丢未落盘写入",
                Files.readAllBytes(wal.toPath()),
                Files.readAllBytes(new File(target, "bookshelf.db-wal").toPath()));
        assertTrue("留下了副本才允许记 db_version", PreMigrationBackup.shouldMarkOpened(-1));
    }

    @Test
    public void 源库不存在时不算留下副本() throws Exception {
        File missing = new File(folder.getRoot(), "bookshelf.db");
        File target = new File(folder.getRoot(), "20261111_120300_db24_to_26");

        assertFalse(PreMigrationBackup.copySnapshot(missing, target));

        assertFalse(target.exists());
        assertFalse(PreMigrationBackup.isUsableCopy(target));
    }

    @Test
    public void 目标目录已存在时不碰里面已有的副本() throws Exception {
        File src = newFile("bookshelf.db", "新库内容");
        File target = folder.newFolder("20261111_120400_db24_to_26");
        File existing = new File(target, "bookshelf.db");
        write(existing, "上一次留下的好副本");

        assertFalse("目录已在就放弃本次复制", PreMigrationBackup.copySnapshot(src, target));

        assertTrue("别人的副本一个字节都不许删", existing.exists());
        assertEquals("上一次留下的好副本", read(existing));
    }

    @Test
    public void 只有副本失败才不许记版本() {
        assertFalse(PreMigrationBackup.shouldMarkOpened(PreMigrationBackup.COPY_FAILED));
        assertTrue("全新安装没有副本可留，照常记版本", PreMigrationBackup.shouldMarkOpened(0));
        assertTrue("留下了副本（旧版本未知）", PreMigrationBackup.shouldMarkOpened(-1));
        assertTrue("版本一致本次不迁移", PreMigrationBackup.shouldMarkOpened(26));
        assertTrue("迁移失败但副本留下了，仍要记版本以外的重试由开库失败决定",
                PreMigrationBackup.shouldMarkOpened(24));
        assertEquals("失败哨兵不能和「已留副本」撞值", -2, PreMigrationBackup.COPY_FAILED);
    }

    @Test
    public void 空壳目录下游一律判为无副本() throws Exception {
        File onlyDir = folder.newFolder("20261111_120500_db24_to_26");
        assertFalse("只有目录没有主库", PreMigrationBackup.isUsableCopy(onlyDir));

        File zero = folder.newFolder("20261111_120600_db24_to_26");
        write(new File(zero, "bookshelf.db"), "");
        assertFalse("主库 0 字节", PreMigrationBackup.isUsableCopy(zero));

        File good = folder.newFolder("20261111_120700_db24_to_26");
        write(new File(good, "bookshelf.db"), "SQLite format 3 有内容");
        assertTrue("有非空主库才算有副本", PreMigrationBackup.isUsableCopy(good));
    }

    // ==================== 工具 ====================

    private File newFile(String name, String content) throws IOException {
        File f = new File(folder.getRoot(), name);
        write(f, content);
        return f;
    }

    private static void write(File f, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}