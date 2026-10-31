package com.bai.bookshelf.util;

import android.content.Context;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Excerpt;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 书摘导出 Markdown（v5.8.19）：把摘抄按书 / 按标签写成 .md，连同图片打成一个 zip。
 *
 * <p>文本部分在 {@link MarkdownText}（纯字符串、可单测），这里只管取数据、收图片、写压缩包。
 * 摘抄没打标签时沿用所属书的标签，这样「按标签」的分组不会大面积落空。
 */
public final class MarkdownExport {

    /** 导出结果（用于提示文案） */
    public static final class Result {
        public int bookFiles;
        public int tagFiles;
        public int excerptCount;
        public int images;
        public int missingImages;
        public File zip;
    }

    private MarkdownExport() {
    }

    /**
     * 在 io 线程调用：把带摘抄的书导出成 md 文件并打包到 outZip。
     *
     * @param books    全库书（用于补书目信息）
     * @param excerpts 全库摘抄
     */
    public static Result export(List<Book> books, List<Excerpt> excerpts, File outZip)
            throws IOException {
        Result result = new Result();
        String exportedAt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                .format(new Date());
        Map<Long, Book> bookById = new HashMap<>();
        for (Book book : books) {
            bookById.put(book.id, book);
        }
        // 1) 按书分组（保持查询返回的倒序）
        Map<Long, List<Excerpt>> byBook = new LinkedHashMap<>();
        for (Excerpt excerpt : excerpts) {
            if (!bookById.containsKey(excerpt.bookId)) {
                continue; // 书已删除的孤儿摘抄不导出
            }
            List<Excerpt> list = byBook.get(excerpt.bookId);
            if (list == null) {
                list = new ArrayList<>();
                byBook.put(excerpt.bookId, list);
            }
            list.add(excerpt);
            result.excerptCount++;
        }
        // 2) 图片：起一个不重名的 assets 文件名，记下 zip 条目名与实际文件
        Map<String, String> imageRefs = new HashMap<>();
        List<String> imageNames = new ArrayList<>();
        List<File> imageFiles = new ArrayList<>();
        Map<String, Boolean> used = new HashMap<>();
        for (Excerpt excerpt : excerpts) {
            if (excerpt.imagePath == null || excerpt.imagePath.trim().isEmpty()) {
                continue;
            }
            File file = new File(excerpt.imagePath.trim());
            if (!file.isFile()) {
                result.missingImages++;
                continue;
            }
            Book book = bookById.get(excerpt.bookId);
            String name = uniqueImageName(book, file, used);
            imageRefs.put(excerpt.imagePath.trim(), name);
            imageNames.add(name);
            imageFiles.add(file);
            result.images++;
        }

        try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(outZip))) {
            // 3) 按书
            List<String> bookNames = new ArrayList<>();
            Map<Long, String> fileNameByBook = new HashMap<>();
            for (Map.Entry<Long, List<Excerpt>> entry : byBook.entrySet()) {
                Book book = bookById.get(entry.getKey());
                String name = uniqueBookFile(book, bookNames);
                fileNameByBook.put(book.id, name);
                writeText(zip, name + ".md",
                        MarkdownText.bookFile(book, entry.getValue(), imageRefs, exportedAt));
                result.bookFiles++;
            }
            // 4) 按标签（摘抄自己的标签优先，没有就用书的标签）
            Map<String, Map<Book, List<Excerpt>>> byTag = new LinkedHashMap<>();
            for (Map.Entry<Long, List<Excerpt>> entry : byBook.entrySet()) {
                Book book = bookById.get(entry.getKey());
                for (Excerpt excerpt : entry.getValue()) {
                    for (String tag : tagsOf(excerpt, book)) {
                        Map<Book, List<Excerpt>> group = byTag.get(tag);
                        if (group == null) {
                            group = new LinkedHashMap<>();
                            byTag.put(tag, group);
                        }
                        List<Excerpt> list = group.get(book);
                        if (list == null) {
                            list = new ArrayList<>();
                            group.put(book, list);
                        }
                        list.add(excerpt);
                    }
                }
            }
            List<String> tagNames = new ArrayList<>();
            for (Map.Entry<String, Map<Book, List<Excerpt>>> entry : byTag.entrySet()) {
                String name = MarkdownText.fileName(entry.getKey());
                if (tagNames.contains(name)) {
                    continue;
                }
                tagNames.add(name);
                writeText(zip, "tags/" + name + ".md", MarkdownText.tagFile(
                        entry.getKey(), entry.getValue(), imageRefs, exportedAt));
                result.tagFiles++;
            }
            // 5) 图片与目录
            for (int i = 0; i < imageFiles.size(); i++) {
                writeFile(zip, "assets/" + imageNames.get(i), imageFiles.get(i));
            }
            writeText(zip, "INDEX.md", MarkdownText.indexFile(result.bookFiles, result.tagFiles,
                    result.excerptCount, result.images, new ArrayList<>(bookNames), tagNames,
                    exportedAt));
        }
        result.zip = outZip;
        return result;
    }

    private static List<String> tagsOf(Excerpt excerpt, Book book) {
        List<String> out = new ArrayList<>();
        for (String part : (excerpt.tags == null ? "" : excerpt.tags).split("[,，]")) {
            String tag = part.trim();
            if (!tag.isEmpty() && !out.contains(tag)) {
                out.add(tag);
            }
        }
        if (!out.isEmpty()) {
            return out;
        }
        for (String part : (book.tags == null ? "" : book.tags).split("[,，]")) {
            String tag = part.trim();
            if (!tag.isEmpty() && !out.contains(tag)) {
                out.add(tag);
            }
        }
        return out;
    }

    /** 书名相同的书不能互相覆盖，重名时补上 id */
    private static String uniqueBookFile(Book book, List<String> taken) {
        String base = MarkdownText.fileName(BookText.bareTitle(book.title));
        String name = base;
        if (taken.contains(name)) {
            name = base + "_" + book.id;
        }
        taken.add(name);
        return name;
    }

    private static String uniqueImageName(Book book, File file, Map<String, Boolean> used) {
        String stem = book == null ? "img" : MarkdownText.fileName(BookText.bareTitle(book.title));
        String ext = file.getName().contains(".")
                ? file.getName().substring(file.getName().lastIndexOf('.') + 1).toLowerCase(
                Locale.ROOT) : "jpg";
        String candidate = stem + "_" + (Math.abs(file.getName().hashCode())) + "." + ext;
        while (Boolean.TRUE.equals(used.get(candidate))) {
            candidate = candidate.replace("." + ext, "_" + used.size() + "." + ext);
        }
        used.put(candidate, true);
        return candidate;
    }

    private static void writeText(ZipOutputStream zip, String entry, String text)
            throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        zip.write(text.getBytes("UTF-8"));
        zip.closeEntry();
    }

    private static void writeFile(ZipOutputStream zip, String entry, File file)
            throws IOException {
        zip.putNextEntry(new ZipEntry(entry));
        try (FileInputStream in = new FileInputStream(file)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) > 0) {
                zip.write(buffer, 0, read);
            }
        }
        zip.closeEntry();
    }
}
