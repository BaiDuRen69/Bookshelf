package com.bai.bookshelf.util;

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
 * 恢复失败时的回滚不变式（卡 8 B 组，坑 52）。
 *
 * <p>从 zip 重建走的是「先把原库改名成 {@code *.broken-时间戳} → 建新库 → 失败换回来」。
 * 换回来这一步只有一种情况可以删现在库目录里那个文件：原库确实被挪走过。
 * 校验没过、压根没挪过的时候，库目录里躺着的仍是原库，删它等于删用户数据。
 *
 * <p>这条判断原先只在 {@code DbRepair.rollback} 里，方法要 {@code BookshelfApp}（Android
 * Application），JVM 单测进不去；现在把纯文件决策抽成 {@code DbRepair.rollbackFiles}，
 * 剩下的开关库动作仍留在原方法里，行为一个字没变。
 */
public class DbRepairRollbackTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    @Test
    public void 原库确实被挪走过才删掉新库并把旧库换回() throws Exception {
        File db = newDb("NEW-新库内容");
        write(db.getPath() + "-wal", "NEW-WAL");
        File broken = new File(db.getPath() + DbRepair.BROKEN_SUFFIX + "20261111_093000");
        write(broken.getPath(), "OLD-原库内容");
        write(broken.getPath() + "-wal", "OLD-WAL");

        assertTrue("旧库在位就该换回来", DbRepair.rollbackFiles(db, broken));

        assertEquals("库目录里必须换成原库", "OLD-原库内容", read(db));
        assertEquals("wal 要跟着一起回来", "OLD-WAL", read(new File(db.getPath() + "-wal")));
        assertFalse("旧库不能让主文件还留在 broken 位置", broken.exists());
        assertFalse("broken 的 wal 也不能剩", new File(broken.getPath() + "-wal").exists());
    }

    @Test
    public void 旧库没被挪走时新库一个字节都不许删() throws Exception {
        // zip 校验没过就抛了：原库从来没让位，brokenTarget 自然不存在
        File db = newDb("OLD-原库内容从来没动过");
        File neverMoved = new File(db.getPath() + DbRepair.BROKEN_SUFFIX + "20261111_093000");

        assertFalse("没有 broken 文件就不该动手", DbRepair.rollbackFiles(db, neverMoved));

        assertTrue("库文件必须还在", db.exists());
        assertEquals("内容必须原样", "OLD-原库内容从来没动过", read(db));
    }

    @Test
    public void 旧库只有主文件没有wal时回滚也不留半截() throws Exception {
        File db = newDb("NEW-新库内容");
        File broken = new File(db.getPath() + DbRepair.BROKEN_SUFFIX + "20261111_094000");
        write(broken.getPath(), "OLD-原库内容");

        assertTrue(DbRepair.rollbackFiles(db, broken));

        assertEquals("OLD-原库内容", read(db));
        assertFalse("不该凭空造出一个 wal", new File(db.getPath() + "-wal").exists());
    }

    @Test
    public void 回滚后重复调用不会把好库再换一次() throws Exception {
        File db = newDb("NEW-新库内容");
        File broken = new File(db.getPath() + DbRepair.BROKEN_SUFFIX + "20261111_095000");
        write(broken.getPath(), "OLD-原库内容");

        assertTrue(DbRepair.rollbackFiles(db, broken));
        assertFalse("第二次已经没有旧库可换了", DbRepair.rollbackFiles(db, broken));
        assertEquals("库里的内容还是那次换回来的原库", "OLD-原库内容", read(db));
    }

    // ==================== 工具 ====================

    private File newDb(String content) throws IOException {
        File db = new File(folder.getRoot(), "bookshelf.db");
        write(db.getPath(), content);
        return db;
    }

    private static void write(String path, String content) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(path)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static String read(File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}
