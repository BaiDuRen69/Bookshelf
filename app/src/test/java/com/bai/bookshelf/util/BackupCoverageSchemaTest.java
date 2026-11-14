package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.junit.Test;

import java.io.File;
import java.io.FileReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * 备份覆盖面的结构闸：Room 声明几张表，打包端 / 恢复端 / 指纹就得各覆盖几张。
 *
 * <p>想读清单整表漏在包外（v5.9.5 缺口 B）之所以拖到换新机才暴露，是因为两侧都没有
 * 「按 schema 逐表点名」的断言：同机恢复时清单原样留着，看起来完全正常。
 * 这里不跑 Room，直接拿 {@code schemas/…/26.json} 的表名去源码里点名——
 * 以后新增一张表却忘了进备份，这条立刻红，而不是等用户丢数据。
 *
 * <p>最后一条是负向自测：把 wish_books 那行从源码文本里抽掉，闸必须报出它，
 * 否则这套点名就是恒真的假绿护栏。
 */
public class BackupCoverageSchemaTest {

    private static final String SCHEMA = "schemas/com.bai.bookshelf.data.AppDatabase/26.json";

    @Test
    public void 打包端必须把每张表写进dataJson() throws Exception {
        List<String> tables = tables();
        assertEquals("schema 26 的表数（口径：26.json 的 entities 条数）", 14, tables.size());
        List<String> missing = missing(source("util/ZipBackup.java"), "root.add(\"", tables);
        assertTrue("这些表没写进备份包：" + missing, missing.isEmpty());
    }

    @Test
    public void 恢复端必须把每张表读回来() throws Exception {
        List<String> missing = missing(source("util/ZipRestore.java"), "readList(root, \"", tables());
        assertTrue("这些表备份了却恢复不回来：" + missing, missing.isEmpty());
    }

    @Test
    public void 指纹的行数信号必须一张表一条() throws Exception {
        String src = source("util/BackupFingerprint.java");
        int lines = 0;
        for (int i = src.indexOf("sb.append(\"t|"); i >= 0; i = src.indexOf("sb.append(\"t|", i + 1)) {
            lines++;
        }
        assertEquals("指纹里的行数信号条数必须等于表数（少了就有一张表的变化看不见）",
                tables().size(), lines);
    }

    @Test
    public void 负向自测_抽掉一张表这条闸必须报出它() throws Exception {
        String src = source("util/ZipBackup.java");
        String mutated = src.replace("root.add(\"wish_books\"", "root.add(\"__dropped__\"");
        assertNotEquals("变异没生效，这条负向自测是空跑", src, mutated);
        List<String> missing = missing(mutated, "root.add(\"", tables());
        assertEquals("抽掉一张表就该只报这一张", 1, missing.size());
        assertEquals("wish_books", missing.get(0));
        assertFalse("原源码不该被这条闸报红", missing(src, "root.add(\"", tables()).contains("wish_books"));
    }

    // ==================== 工具 ====================

    /** 点名：源码里没有 {@code prefix + 表名 + "} 的表逐个列出来 */
    private static List<String> missing(String src, String prefix, List<String> tables) {
        List<String> out = new ArrayList<>();
        for (String t : tables) {
            if (!src.contains(prefix + t + "\"")) {
                out.add(t);
            }
        }
        return out;
    }

    private static List<String> tables() throws Exception {
        File f = new File(SCHEMA);
        assertTrue("找不到 " + f.getAbsolutePath() + "（单测工作目录应在 app/ 下）", f.exists());
        JsonObject root = JsonParser.parseReader(new FileReader(f)).getAsJsonObject();
        List<String> out = new ArrayList<>();
        for (JsonElement e : root.getAsJsonObject("database").getAsJsonArray("entities")) {
            out.add(e.getAsJsonObject().get("tableName").getAsString());
        }
        return out;
    }

    private static String source(String relative) throws Exception {
        File f = new File("src/main/java/com/bai/bookshelf/" + relative);
        assertTrue("找不到 " + f.getAbsolutePath(), f.exists());
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }
}