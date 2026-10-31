package com.bai.bookshelf.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import androidx.sqlite.db.SupportSQLiteDatabase;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 迁移冒烟测试（v5.8）：在 JVM 上用真 SQLite 把迁移逐级跑一遍。
 *
 * <p><b>为什么能脱离 Android 跑</b>：本项目的迁移只调 {@code database.execSQL(...)}，
 * 没有查表判断、没有 DAO。这里用一个动态代理把 {@link SupportSQLiteDatabase}
 * 的 execSQL 转给 sqlite-jdbc，其余方法给个无害默认值，于是 22 条迁移能在单测里执行。
 *
 * <p><b>覆盖范围</b>：Room 的 schema JSON 从 v12 才开始导出（{@code app/schemas/}），
 * 所以 v1–v11 没有可比对的旧表结构，只做「清单不缺级」检查；v12 → v23 的每一级都
 * 建旧库、插样本、升一级，然后拿升级后的库和下一版 schema 直接建出来的库比对
 * 表与列定义（名字/类型/非空/默认值），并核对行数没少。
 */
public class MigrationSmokeTest {

    private static final String SCHEMA_DIR = "schemas/com.bai.bookshelf.data.AppDatabase";
    private static final int FIRST_EXPORTED = 12;

    // ==================== 清单完整性 ====================

    @Test
    public void 迁移清单从一到最新每一级都不缺() {
        for (int v = 1; v < AppDatabase.VERSION; v++) {
            assertNotNull("缺少 " + v + "→" + (v + 1) + " 的迁移", AppDatabase.migration(v));
        }
    }

    // ==================== 逐级升级 ====================

    @Test
    public void 从十二版逐级升到最新_表结构与下一版一致且数据不丢() throws Exception {
        Connection db = openAt(FIRST_EXPORTED);
        try {
            insertBooks(db, 2);
            for (int v = FIRST_EXPORTED; v < AppDatabase.VERSION; v++) {
                AppDatabase.migration(v).migrate(proxy(db));
                assertEquals("升到 v" + (v + 1) + " 后藏书不能少", 2, count(db, "books"));
                assertSchemaLike(v + 1, db);
            }
            assertEquals("最新版仍是两本", 2, count(db, "books"));
        } finally {
            db.close();
        }
    }

    @Test
    public void 从十二版一次升到最新_三本书都还在() throws Exception {
        Connection db = openAt(FIRST_EXPORTED);
        try {
            insertBooks(db, 3);
            for (int v = FIRST_EXPORTED; v < AppDatabase.VERSION; v++) {
                AppDatabase.migration(v).migrate(proxy(db));
            }
            assertEquals(3, count(db, "books"));
            assertEquals("9787500000001", string(db, "SELECT isbn FROM books ORDER BY id LIMIT 1"));
            assertEquals("书名不能被迁移改掉", "样本 1", string(db, "SELECT title FROM books ORDER BY id LIMIT 1"));
        } finally {
            db.close();
        }
    }

    /** 22→23 的业务规则：已归还的借阅书要落成「电子书签」 */
    @Test
    public void 升到二十三版时已归还的借阅书落成电子书签() throws Exception {
        Connection db = openAt(22);
        try {
            insertBooks(db, 1);
            exec(db, "UPDATE books SET ownType = 1, borrowReturnDate = '2026-09-01', stockStatus = 0");
            AppDatabase.migration(22).migrate(proxy(db));
            assertEquals("已归还的借阅书 = 电子书签（3）", 3L, number(db,
                    "SELECT stockStatus FROM books LIMIT 1"));
            assertTrue("borrowRecords 列要补上", columns(db, "books").containsKey("borrowRecords"));
        } finally {
            db.close();
        }
    }

    // ==================== schema 比对 ====================

    /** 升级后的库要和「直接用目标版本 schema 建出来的库」逐表逐列一致 */
    private static void assertSchemaLike(int version, Connection actual) throws Exception {
        Connection expected = openAt(version);
        try {
            List<String> want = tables(expected);
            List<String> got = tables(actual);
            assertEquals("v" + version + " 的表清单", want, got);
            for (String table : want) {
                Map<String, String> ref = columns(expected, table);
                Map<String, String> after = columns(actual, table);
                assertEquals("v" + version + " 的 " + table + " 列名", ref.keySet(), after.keySet());
                for (String col : ref.keySet()) {
                    String[] r = ref.get(col).split("\\|", -1);
                    String[] a = after.get(col).split("\\|", -1);
                    String where = "v" + version + " 的 " + table + "." + col;
                    assertEquals(where + " 类型", r[0], a[0]);
                    assertEquals(where + " 非空", r[1], a[1]);
                    // Room 只在实体声明了默认值时才校验默认值：实体的 String 字段没有
                    // @ColumnInfo(defaultValue)，迁移里多写一个 DEFAULT '' 是允许的
                    // （v19→20 的借阅日期三列就是这样，真机升级一直正常）。
                    if (!"null".equals(r[2])) {
                        assertEquals(where + " 默认值", r[2], a[2]);
                    }
                }
            }
        } finally {
            expected.close();
        }
    }

    private static List<String> tables(Connection c) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT name FROM sqlite_master WHERE type='table'"
                     + " AND name NOT LIKE 'sqlite_%' ORDER BY name")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    /** 列名 → 「类型|非空|默认值」，忽略列顺序（Room 的 schema 校验也不看顺序） */
    private static Map<String, String> columns(Connection c, String table) throws Exception {
        Map<String, String> out = new TreeMap<>();
        try (Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("PRAGMA table_info(`" + table + "`)")) {
            while (rs.next()) {
                out.put(rs.getString("name"), rs.getString("type") + "|"
                        + rs.getInt("notnull") + "|" + rs.getString("dflt_value"));
            }
        }
        return out;
    }

    // ==================== 工具 ====================

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
                if (entity.has("indices")) {
                    for (JsonElement idx : entity.getAsJsonArray("indices")) {
                        String idxSql = idx.getAsJsonObject().get("createSql").getAsString();
                        s.execute(idxSql.replace("${TABLE_NAME}", table));
                    }
                }
            }
        }
        return c;
    }

    private static void insertBooks(Connection c, int n) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO books ("
                + "isbn, title, pages, listPrice, stockStatus, readStatus, ownType,"
                + "purchasePrice, rating, createdAt, updatedAt, dirty)"
                + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)")) {
            for (int i = 1; i <= n; i++) {
                ps.setString(1, "978750000000" + i);
                ps.setString(2, "样本 " + i);
                ps.setInt(3, 200 + i);
                ps.setDouble(4, 39.0 + i);
                ps.setInt(5, 0);
                ps.setInt(6, 0);
                ps.setInt(7, 0);
                ps.setDouble(8, 29.0 + i);
                ps.setInt(9, 0);
                ps.setLong(10, 1700000000000L + i);
                ps.setLong(11, 1700000000000L + i);
                ps.setInt(12, 0);
                ps.executeUpdate();
            }
        }
    }

    private static int count(Connection c, String table) throws Exception {
        return (int) number(c, "SELECT COUNT(*) FROM `" + table + "`");
    }

    private static String string(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : null;
        }
    }

    private static long number(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : -1;
        }
    }

    private static void exec(Connection c, String sql) throws Exception {
        try (Statement s = c.createStatement()) {
            s.execute(sql);
        }
    }

    /**
     * 把 Room 的 SupportSQLiteDatabase 接到 JDBC 上。
     *
     * <p>迁移只用到 execSQL；事务方法直接放行（JDBC 自动提交），其余方法返回类型默认值。
     * 用动态代理而不是实现整个接口，省掉 40 多个空方法，接口升级也不会再编译失败。
     */
    private static SupportSQLiteDatabase proxy(Connection c) {
        return (SupportSQLiteDatabase) Proxy.newProxyInstance(
                SupportSQLiteDatabase.class.getClassLoader(),
                new Class<?>[]{SupportSQLiteDatabase.class},
                (p, method, args) -> {
                    String name = method.getName();
                    switch (name) {
                        case "execSQL":
                            exec(c, (String) args[0]);
                            return null;
                        case "beginTransaction":
                        case "beginTransactionNonExclusive":
                        case "endTransaction":
                        case "setTransactionSuccessful":
                            return null;
                        case "inTransaction":
                        case "isOpen":
                            return true;
                        case "toString":
                            return "JdbcSupportDatabase";
                        case "hashCode":
                            return System.identityHashCode(p);
                        case "equals":
                            return p == args[0];
                        default:
                            return defaultValue(method.getReturnType());
                    }
                });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class) {
            return 0;
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == char.class) {
            return '\0';
        }
        return null;
    }
}
