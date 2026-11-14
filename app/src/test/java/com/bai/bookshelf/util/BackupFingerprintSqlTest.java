package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.io.FileReader;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 指纹类聚合的 SQL 语义（卡 8 C 组，坑 14）。
 *
 * <p>自动备份靠指纹决定「跳过还是照打」，指纹里每段摘要都必须满足两件事：
 * ① 空表要出得来一个值，不能是 NULL——NULL 会被拼进摘要串，下次内容真变了也分不出来；
 * ② 小数位的调价要看得见——{@code CAST(45.5 AS INTEGER)} 与 {@code CAST(45.9 AS INTEGER)}
 * 都是 45，只有先乘 100 再取整才分得开。
 *
 * <p>这两条原先只在真机上才能撞出来（坑 14 就是这么踩的），这里用 sqlite-jdbc 直接跑真
 * SQLite 把它们钉住；表用的是 {@code schemas/…}{@code /26.json} 里的真 createSql，
 * 列类型（{@code listPrice REAL}）跟着实体走，不会因为手写 DDL 走样。
 */
public class BackupFingerprintSqlTest {

    private static final String SCHEMA_DIR = "schemas/com.bai.bookshelf.data.AppDatabase";

    /** {@code ExcerptDao.fingerprint()} 的实际语句（data/dao/ExcerptDao.java:29） */
    private static final String EXCERPT_FINGERPRINT_SQL =
            "SELECT COUNT(*) || '/' || IFNULL(MAX(updatedAt), 0) FROM excerpts";

    @Test
    public void 空表上裸SUM是NULL必须用IFNULL兜成一个值() throws Exception {
        Connection db = openAt(26);
        try {
            assertNull("SQLite 对空表的 SUM 给的就是 NULL",
                    object(db, "SELECT SUM(updatedAt) FROM excerpts"));
            assertNull("books 上的 SUM(listPrice) 同理",
                    object(db, "SELECT SUM(listPrice) FROM books"));
            assertEquals("兜住以后是 0", 0L, number(db, "SELECT IFNULL(SUM(listPrice), 0) FROM books"));
            assertEquals("摘抄指纹在空表上必须出得来 0/0", "0/0",
                    string(db, EXCERPT_FINGERPRINT_SQL));
        } finally {
            db.close();
        }
    }

    @Test
    public void 摘抄摘要随行数与最后修改时间变化() throws Exception {
        Connection db = openAt(26);
        try {
            exec(db, "INSERT INTO excerpts (bookId, page, content, createdAt, updatedAt)"
                    + " VALUES (1, 10, '甲', 1000, 2000)");
            String one = string(db, EXCERPT_FINGERPRINT_SQL);
            assertEquals("一条、最大 2000", "1/2000", one);

            exec(db, "INSERT INTO excerpts (bookId, page, content, createdAt, updatedAt)"
                    + " VALUES (2, 3, '乙', 1000, 3000)");
            assertEquals("加一条就看得见", "2/3000", string(db, EXCERPT_FINGERPRINT_SQL));

            // 行数不变，只把最大那条改小：摘要必须跟着变，否则「只改内容」会被当成没变跳过
            exec(db, "UPDATE excerpts SET updatedAt = 1500 WHERE bookId = 2");
            assertEquals("最大落回另一条的 2000", "2/2000", string(db, EXCERPT_FINGERPRINT_SQL));
            assertNotEquals("行数没变也不能和 2/3000 撞", "2/3000",
                    string(db, EXCERPT_FINGERPRINT_SQL));
            assertNotEquals("也不能和最初那条的摘要撞", one, string(db, EXCERPT_FINGERPRINT_SQL));
        } finally {
            db.close();
        }
    }

    @Test
    public void 角分级的调价只有先乘一百再取整才看得见() throws Exception {
        Connection db = openAt(26);
        try {
            assertEquals("坑 14 的陷阱：45.5 与 45.9 直接取整都是 45",
                    number(db, "SELECT CAST(45.5 AS INTEGER)"),
                    number(db, "SELECT CAST(45.9 AS INTEGER)"));
            assertEquals(45L, number(db, "SELECT CAST(45.5 AS INTEGER)"));
            assertEquals("乘 100 以后分得开", 4550L, number(db, "SELECT CAST(45.5 * 100 AS INTEGER)"));
            assertEquals(4590L, number(db, "SELECT CAST(45.9 * 100 AS INTEGER)"));

            exec(db, "INSERT INTO books (isbn, title, pages, listPrice, stockStatus, readStatus,"
                    + " ownType, purchasePrice, rating, createdAt, updatedAt, dirty)"
                    + " VALUES ('9787500000001', '样本', 200, 45.5, 0, 0, 0, 39.0, 0,"
                    + " 1700000000000, 1700000000000, 0)");
            long before = number(db, "SELECT SUM(CAST(listPrice * 100 AS INTEGER)) FROM books");
            assertEquals(4550L, before);

            exec(db, "UPDATE books SET listPrice = 45.9");
            long after = number(db, "SELECT SUM(CAST(listPrice * 100 AS INTEGER)) FROM books");
            assertEquals("四角的调价必须落到摘要里", 4590L, after);
            assertNotEquals("写成 SUM(CAST(listPrice AS INTEGER)) 就是 45 = 45，检测不到",
                    number(db, "SELECT SUM(CAST(listPrice AS INTEGER)) FROM books"), after);
        } finally {
            db.close();
        }
    }

    // ==================== 工具（照抄 MigrationSmokeTest） ====================

    /** 用 {@code schemas/<version>.json} 里的 createSql 在内存里建一个空库 */
    private static Connection openAt(int version) throws Exception {
        File f = new File(SCHEMA_DIR + "/" + version + ".json");
        assertTrue("找不到 " + f.getAbsolutePath() + "（单测工作目录应在 app/ 下）", f.exists());
        JsonObject root = JsonParser.parseReader(new FileReader(f)).getAsJsonObject();
        Connection c = DriverManager.getConnection("jdbc:sqlite::memory:");
        try (Statement s = c.createStatement()) {
            for (JsonElement e : root.getAsJsonObject("database").getAsJsonArray("entities")) {
                JsonObject entity = e.getAsJsonObject();
                String table = entity.get("tableName").getAsString();
                String sql = entity.get("createSql").getAsString().replace("${TABLE_NAME}", table);
                s.execute(sql);
            }
        }
        return c;
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    private static Object object(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getObject(1) : null;
        }
    }

    private static String string(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long number(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1L;
        }
    }
}
