package com.bai.bookshelf;

import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * API 级别守卫（v5.8.3）。
 *
 * <p>minSdk 是 24，但 {@code String.join} / {@code List.of} / {@code Map.of} /
 * {@code Set.of} / {@code java.time.*} 都是 API 26+。Android Lint 的 NewApi 只查
 * android.* 的方法，java.* 里的新 API 它不管，所以用这条源码扫描兜住，
 * 防止再有人（包括我）顺手写一个 {@code String.join} 上去。
 *
 * <p>要新增白名单，必须同时确认 minSdk 会跟着上调。
 */
public class ApiLevelGuardTest {

    /** 命中即失败：这些调用在 API 24/25 上会 NoSuchMethodError / NoClassDefFoundError */
    private static final String[] BANNED = {
            "String.join(",
            "List.of(",
            "Map.of(",
            "Set.of(",
            "Objects.requireNonNullElse(",
            "java.time.",
    };

    @Test
    public void 源码里不能出现只有API26才有的接口() throws Exception {
        File root = new File("src/main/java");
        assertTrue("找不到源码目录：" + root.getAbsolutePath(), root.isDirectory());
        List<String> hits = new ArrayList<>();
        collect(root, hits);
        if (!hits.isEmpty()) {
            StringBuilder sb = new StringBuilder("以下位置用了 API 26+ 的接口（minSdk 24）：");
            for (String h : hits) {
                sb.append('\n').append(h);
            }
            throw new AssertionError(sb.toString());
        }
    }

    private static void collect(File dir, List<String> hits) throws Exception {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File f : children) {
            if (f.isDirectory()) {
                collect(f, hits);
                continue;
            }
            if (!f.getName().endsWith(".java")) {
                continue;
            }
            List<String> lines = Files.readAllLines(f.toPath(), StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.trim();
                if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                    continue;
                }
                for (String bad : BANNED) {
                    if (line.contains(bad)) {
                        hits.add(f.getName() + ":" + (i + 1) + " → " + trimmed);
                    }
                }
            }
        }
    }
}
