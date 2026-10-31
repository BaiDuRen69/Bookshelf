package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Excerpt;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 书摘导出 Markdown（v5.8.19）：文件名净化、front matter 能被 Obsidian 认、
 * 按书 / 按标签 / 图片 / 目录都进同一个 zip。
 */
public class MarkdownTextTest {

    private static final String STAMP = "2026-10-31 15:00";

    private Book book(long id, String title, String tags) {
        Book book = new Book();
        book.id = id;
        book.title = title;
        book.author = "刘震云";
        book.publisher = "人民文学出版社";
        book.isbn = "9787020002207";
        book.pages = 260;
        book.ratingAvg = 8.5;
        book.doubanRating = "8.8";
        book.tags = tags;
        book.review = "一句短评";
        return book;
    }

    private Excerpt excerpt(long id, long bookId, String content, String tags) {
        Excerpt excerpt = new Excerpt();
        excerpt.id = id;
        excerpt.bookId = bookId;
        excerpt.content = content;
        excerpt.tags = tags;
        excerpt.page = 42;
        excerpt.createdAt = 1770000000000L;
        return excerpt;
    }

    private List<String> entries(File zip) throws Exception {
        List<String> names = new ArrayList<>();
        ZipFile zf = new ZipFile(zip);
        try {
            Enumeration<? extends ZipEntry> it = zf.entries();
            while (it.hasMoreElements()) {
                names.add(it.nextElement().getName());
            }
        } finally {
            zf.close();
        }
        Collections.sort(names);
        return names;
    }

    private String readEntry(File zip, String name) throws Exception {
        ZipFile zf = new ZipFile(zip);
        try {
            ZipEntry entry = zf.getEntry(name);
            if (entry == null) {
                return null;
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[2048];
            int read;
            java.io.InputStream in = zf.getInputStream(entry);
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            in.close();
            return new String(out.toByteArray(), "UTF-8");
        } finally {
            zf.close();
        }
    }

    @Test
    public void 文件名去掉文件系统不吃的字符() {
        assertEquals("一日三秋", MarkdownText.fileName(" 一日三秋 "));
        assertEquals("a_b_c", MarkdownText.fileName("a/b:c"));
        assertEquals("未命名", MarkdownText.fileName("   "));
        assertEquals("未命名", MarkdownText.fileName(null));
        StringBuilder longName = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            longName.append('字');
        }
        assertEquals(60, MarkdownText.fileName(longName.toString()).length());
    }

    @Test
    public void 书文件带_front_matter_与摘抄() {
        Book book = book(1, "《一日三秋》", "小说， 中国");
        Excerpt e1 = excerpt(11, 1, "第一行\n第二行", "人生");
        e1.note = "一句批注";
        Excerpt e2 = excerpt(12, 1, "另一条", "");
        String md = MarkdownText.bookFile(book, Arrays.asList(e1, e2),
                Collections.emptyMap(), STAMP);

        assertTrue(md, md.startsWith("---\n"));
        assertTrue("书名两侧的引号要转义掉: " + md, md.contains("title: \"一日三秋\""));
        assertTrue(md, md.contains("author: \"刘震云\""));
        assertTrue(md, md.contains("isbn: \"9787020002207\""));
        assertTrue("tags 要是数组，Obsidian 才认", md.contains("tags: [\"小说\", \"中国\"]"));
        assertTrue(md, md.contains("rating: 8.5"));
        assertTrue(md, md.contains("## 摘抄（2 条）"));
        assertTrue("换行要缩进在同一条目里", md.contains("第一行\n  第二行"));
        assertTrue(md, md.contains("第 42 页"));
        assertTrue(md, md.contains("#人生"));
        assertTrue(md, md.contains("批注：一句批注"));
        assertTrue(md, md.contains("## 短评"));
        assertTrue(md, md.contains("一句短评"));
        assertFalse("不该出现空的图片引用", md.contains("!["));
    }

    @Test
    public void 图片按相对路径引用_缺文件的不引用() {
        Book book = book(1, "一日三秋", "小说");
        Excerpt withImage = excerpt(11, 1, "带图", "");
        withImage.imagePath = "/data/user/0/x/files/excerpt_images/a.jpg";
        java.util.Map<String, String> refs = new java.util.HashMap<>();
        refs.put(withImage.imagePath, "一日三秋_1.jpg");
        String md = MarkdownText.bookFile(book, Collections.singletonList(withImage),
                refs, STAMP);
        assertTrue(md, md.contains("](assets/一日三秋_"));

        Excerpt missing = excerpt(12, 1, "图没了", "");
        missing.imagePath = "/gone/b.jpg";
        String md2 = MarkdownText.bookFile(book, Collections.singletonList(missing),
                refs, STAMP);
        assertFalse("引用不到的图片不要写进 md", md2.contains("assets/"));
    }

    @Test
    public void 标签文件按书分组并计数() {
        Book a = book(1, "书甲", "小说");
        Book b = book(2, "书乙", "小说");
        List<Excerpt> aList = Arrays.asList(excerpt(11, 1, "甲一", "小说"),
                excerpt(12, 1, "甲二", "小说"));
        List<Excerpt> bList = Collections.singletonList(excerpt(21, 2, "乙一", "小说"));
        java.util.Map<Book, List<Excerpt>> byBook = new java.util.LinkedHashMap<>();
        byBook.put(a, aList);
        byBook.put(b, bList);
        String md = MarkdownText.tagFile("小说", byBook, Collections.emptyMap(), STAMP);
        assertTrue(md, md.contains("# 标签：小说（3 条 · 2 本）"));
        assertTrue(md, md.indexOf("## 书甲") < md.indexOf("## 书乙"));
        assertTrue(md, md.contains("甲一"));
        assertTrue(md, md.contains("乙一"));
    }

    @Test
    public void 目录页说清条数与导入方式() {
        String md = MarkdownText.indexFile(5, 2, 30, 7,
                new ArrayList<>(Arrays.asList("书甲", "书乙")),
                new ArrayList<>(Collections.singletonList("小说")), STAMP);
        assertTrue(md, md.contains("书摘条数：30"));
        assertTrue(md, md.contains("按书文件：5 个"));
        assertTrue(md, md.contains("图片：7 张"));
        assertTrue(md, md.contains("Obsidian"));
        assertTrue(md, md.contains("- [书甲](书甲.md)"));
        assertTrue(md, md.contains("(tags/小说.md)"));
    }

    @Test
    public void 打包导出按书与标签分文件并带上图片() throws Exception {
        Book a = book(1, "一日三秋", "小说");
        Book b = book(2, "另一本", "推理");
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "mdtest_" + System.currentTimeMillis());
        File images = new File(dir, "excerpt_images");
        assertTrue(images.mkdirs());
        File image = new File(images, "quote.jpg");
        try (FileOutputStream out = new FileOutputStream(image)) {
            out.write(new byte[]{1, 2, 3, 4, 5});
        }
        Excerpt ea = excerpt(11, 1, "甲的摘抄", "人生");
        ea.imagePath = image.getAbsolutePath();
        Excerpt eb = excerpt(21, 2, "乙的摘抄", "");
        Excerpt orphan = excerpt(31, 999, "书已删除", "");

        File zip = new File(dir, "out.zip");
        MarkdownExport.Result r = MarkdownExport.export(
                new ArrayList<>(Arrays.asList(a, b)),
                new ArrayList<>(Arrays.asList(ea, eb, orphan)), zip);

        assertEquals(2, r.bookFiles);
        assertEquals(2, r.excerptCount);
        assertEquals(1, r.images);
        assertTrue(zip.isFile() && zip.length() > 0);
        List<String> names = entries(zip);
        assertTrue(names.toString(), names.contains("INDEX.md"));
        assertTrue(names.toString(), names.contains("一日三秋.md"));
        assertTrue(names.toString(), names.contains("另一本.md"));
        assertTrue(names.toString(), names.contains("tags/人生.md"));
        assertTrue("没打标签的摘抄沿用书的标签: " + names, names.contains("tags/推理.md"));
        boolean assetFound = false;
        for (String name : names) {
            if (name.startsWith("assets/") && name.endsWith(".jpg")) {
                assetFound = true;
            }
        }
        assertTrue("图片要进 assets/", assetFound);
        assertEquals("书已删除的孤儿摘抄不导出", 2, r.excerptCount);
        String md = readEntry(zip, "一日三秋.md");
        assertTrue(md, md.contains("assets/"));
        assertTrue(md, md.contains("甲的摘抄"));
    }

    @Test
    public void 同名书不互相覆盖() throws Exception {
        Book a = book(1, "重名书", "小说");
        Book b = book(2, "重名书", "小说");
        File dir = new File(System.getProperty("java.io.tmpdir"),
                "mdtest2_" + System.currentTimeMillis());
        assertTrue(dir.mkdirs());
        File zip = new File(dir, "out.zip");
        MarkdownExport.Result r = MarkdownExport.export(
                new ArrayList<>(Arrays.asList(a, b)),
                new ArrayList<>(Arrays.asList(
                        excerpt(11, 1, "第一条", ""), excerpt(21, 2, "第二条", ""))), zip);
        assertEquals(2, r.bookFiles);
        List<String> names = entries(zip);
        assertTrue(names.toString(), names.contains("重名书.md"));
        assertTrue("第二本要换个文件名: " + names, names.contains("重名书_2.md"));
    }
}
