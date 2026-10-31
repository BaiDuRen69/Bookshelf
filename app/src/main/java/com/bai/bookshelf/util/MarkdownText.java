package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Excerpt;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 书摘导出 Markdown 的文本生成（v5.8.19）：纯字符串，不碰 Android 与数据库，便于单测。
 *
 * <p>面向 Obsidian / Notion：每本书一个文件，顶部 YAML front matter（title/author/tags 数组
 * 都能被 Obsidian 直接认成属性与标签），正文用标题 + 列表，图片走相对路径 assets/，
 * 整个目录（或同名 zip）拖进库就能用。
 */
public final class MarkdownText {

    private MarkdownText() {
    }

    /** 文件名净化：文件系统不吃的字符换成下划线，空白折叠，限长 */
    public static String fileName(String raw) {
        String base = raw == null ? "" : raw.trim();
        base = base.replaceAll("[\\\\/:*?\"<>|\\[\\]#]", "_")
                .replaceAll("\\s+", "_")
                .replaceAll("_+", "_");
        if (base.isEmpty() || base.equals("_")) {
            base = "未命名";
        }
        return base.length() > 60 ? base.substring(0, 60) : base;
    }

    /** 一本书的 md：front matter + 书目信息 + 摘抄列表 + 短评 */
    public static String bookFile(Book book, List<Excerpt> excerpts,
                                  Map<String, String> imageRefs, String exportedAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("---\n");
        sb.append("title: \"").append(plain(yamlSafe(BookText.bareTitle(book.title)))).append("\"\n");
        if (!empty(book.author)) {
            sb.append("author: \"").append(yamlSafe(book.author)).append("\"\n");
        }
        if (!empty(book.publisher)) {
            sb.append("publisher: \"").append(yamlSafe(book.publisher)).append("\"\n");
        }
        if (!empty(book.isbn)) {
            sb.append("isbn: \"").append(book.isbn.trim()).append("\"\n");
        }
        double rating = book.ratingAvg > 0 ? book.ratingAvg : book.rating;
        if (rating > 0) {
            sb.append("rating: ").append(ReadingRecords.formatRating(rating)).append('\n');
        }
        if (!empty(book.doubanRating)) {
            sb.append("douban: \"").append(book.doubanRating.trim()).append("\"\n");
        }
        List<String> tags = tagList(book.tags);
        sb.append("tags: [").append(joinQuoted(tags)).append("]\n");
        sb.append("bookshelf_exported: \"").append(exportedAt).append("\"\n");
        sb.append("---\n\n");

        sb.append("# ").append(BookText.bareTitle(book.title)).append("\n\n");
        StringBuilder meta = new StringBuilder();
        appendIf(meta, book.author, " · ");
        appendIf(meta, book.publisher, " · ");
        appendIf(meta, book.pages > 0 ? book.pages + " 页" : "", " · ");
        if (!empty(book.isbn)) {
            appendIf(meta, "ISBN " + book.isbn.trim(), " · ");
        }
        if (meta.length() > 0) {
            sb.append("> ").append(meta).append("\n\n");
        }

        sb.append("## 摘抄（").append(excerpts == null ? 0 : excerpts.size()).append(" 条）\n\n");
        if (excerpts == null || excerpts.isEmpty()) {
            sb.append("_还没有摘抄。_\n\n");
        } else {
            for (Excerpt e : excerpts) {
                sb.append("- ").append(bulletBody(e.content));
                List<String> tail = new ArrayList<>();
                if (e.page > 0) {
                    tail.add("第 " + e.page + " 页");
                }
                if (e.createdAt > 0) {
                    tail.add(new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                            .format(new Date(e.createdAt)));
                }
                List<String> etags = tagList(e.tags);
                if (!etags.isEmpty()) {
                    tail.add(join(hashTags(etags), " "));
                }
                if (!tail.isEmpty()) {
                    sb.append(" — ").append(join(tail, " · "));
                }
                sb.append('\n');
                if (!empty(e.note)) {
                    sb.append("  - 批注：").append(bulletBody(e.note)).append('\n');
                }
                String ref = imageRefs == null || e.imagePath == null
                        ? null : imageRefs.get(e.imagePath);
                if (ref != null) {
                    sb.append("  - ![").append(BookText.bareTitle(book.title))
                            .append("](assets/").append(ref).append(")\n");
                }
            }
            sb.append('\n');
        }

        if (!empty(book.review) || !empty(book.note)) {
            sb.append("## 短评\n\n");
            if (!empty(book.review)) {
                sb.append(escapeLines(book.review.trim())).append("\n\n");
            }
            if (!empty(book.note)) {
                sb.append("备注：").append(escapeLines(book.note.trim())).append("\n\n");
            }
        }
        return sb.toString();
    }

    /** 一个标签的 md：按书分组 */
    public static String tagFile(String tag, Map<Book, List<Excerpt>> byBook,
                                 Map<String, String> imageRefs, String exportedAt) {
        StringBuilder sb = new StringBuilder();
        int total = 0;
        for (List<Excerpt> list : byBook.values()) {
            total += list.size();
        }
        sb.append("---\n");
        sb.append("bookshelf_tag: \"").append(yamlSafe(tag)).append("\"\n");
        sb.append("bookshelf_exported: \"").append(exportedAt).append("\"\n");
        sb.append("---\n\n");
        sb.append("# 标签：").append(tag).append("（")
                .append(total).append(" 条 · ").append(byBook.size()).append(" 本）\n\n");
        for (Map.Entry<Book, List<Excerpt>> entry : byBook.entrySet()) {
            Book book = entry.getKey();
            sb.append("## ").append(BookText.bareTitle(book.title));
            if (!empty(book.author)) {
                sb.append(" · ").append(book.author.trim());
            }
            sb.append("\n\n");
            for (Excerpt e : entry.getValue()) {
                sb.append("- ").append(bulletBody(e.content));
                if (e.page > 0) {
                    sb.append(" — 第 ").append(e.page).append(" 页");
                }
                sb.append('\n');
                if (!empty(e.note)) {
                    sb.append("  - 批注：").append(bulletBody(e.note)).append('\n');
                }
                String ref = imageRefs == null || e.imagePath == null
                        ? null : imageRefs.get(e.imagePath);
                if (ref != null) {
                    sb.append("  - ![](").append("assets/").append(ref).append(")\n");
                }
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    /** 目录页：说明怎么导入 + 文件清单 */
    public static String indexFile(int bookFiles, int tagFiles, int excerptCount, int imageCount,
                                   List<String> bookNames, List<String> tagNames,
                                   String exportedAt) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 小书房 · 书摘导出\n\n");
        sb.append("导出时间：").append(exportedAt).append("\n\n");
        sb.append("- 书摘条数：").append(excerptCount).append('\n');
        sb.append("- 按书文件：").append(bookFiles).append(" 个\n");
        sb.append("- 按标签文件：").append(tagFiles).append(" 个\n");
        sb.append("- 图片：").append(imageCount).append(" 张（在 `assets/` 里）\n\n");
        sb.append("## 怎么导入\n\n");
        sb.append("- **Obsidian**：把整个文件夹（或解压后的 zip 内容）拖进 vault 目录即可，")
                .append("front matter 的 `tags` 会自动变成标签。\n");
        sb.append("- **Notion**：新建页面后用 Import → Text & markdown，")
                .append("逐个 .md 文件导入；图片会随 zip 一起在里面，若没显示就手动传 `assets/`。\n\n");
        sb.append("## 按书\n\n");
        for (String name : bookNames) {
            sb.append("- [").append(name).append("](").append(name).append(".md)\n");
        }
        if (!tagNames.isEmpty()) {
            sb.append("\n## 按标签\n\n");
            for (String name : tagNames) {
                sb.append("- [").append(name).append("](tags/")
                        .append(fileName(name)).append(".md)\n");
            }
        }
        return sb.toString();
    }

    /** 列表项正文：换行缩进两格，保持在同一条目里 */
    private static String bulletBody(String text) {
        String trimmed = text == null ? "" : text.trim().replaceAll("\r", "");
        return trimmed.replace("\n", "\n  ");
    }

    private static String escapeLines(String text) {
        return text.replaceAll("\r", "");
    }

    /** String.join 是 API 26+，minSdk 24 用不了 */
    private static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) {
                sb.append(sep);
            }
            sb.append(item);
        }
        return sb.toString();
    }

    private static String yamlSafe(String text) {
        return text == null ? "" : text.replace("\"", "'").replaceAll("[\r\n]+", " ");
    }

    private static String plain(String text) {
        return text == null ? "" : text;
    }

    private static boolean empty(String text) {
        return text == null || text.trim().isEmpty();
    }

    private static void appendIf(StringBuilder sb, String value, String sep) {
        if (empty(value)) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(sep);
        }
        sb.append(value.trim());
    }

    private static List<String> tagList(String raw) {
        List<String> out = new ArrayList<>();
        if (empty(raw)) {
            return out;
        }
        for (String part : raw.split("[,，]")) {
            String tag = part.trim();
            if (!tag.isEmpty() && !out.contains(tag)) {
                out.add(tag);
            }
        }
        return out;
    }

    private static List<String> hashTags(List<String> tags) {
        List<String> out = new ArrayList<>();
        for (String tag : tags) {
            out.add("#" + tag.replaceAll("\\s+", ""));
        }
        return out;
    }

    private static String joinQuoted(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (String item : items) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append('"').append(yamlSafe(item)).append('"');
        }
        return sb.toString();
    }
}
