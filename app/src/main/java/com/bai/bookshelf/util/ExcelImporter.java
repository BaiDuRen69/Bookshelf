package com.bai.bookshelf.util;

import android.content.Context;
import android.net.Uri;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.data.entity.Location;
import com.bai.bookshelf.data.entity.Tag;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Excel 导入器：读取「导出 Excel」生成的 xlsx（也兼容被 Excel/WPS 重新保存过的文件），
 * 把藏书写回数据库。与 ExcelExporter 同为零第三方依赖实现：
 * xlsx 本质是 zip，直接解析 sheet1.xml 与 sharedStrings.xml。
 *
 * 导入规则：
 * - 第一行视为表头，跳过；
 * - 书名为空的行跳过；ISBN 已存在的书跳过（不覆盖已有数据）；
 * - 存放地点按名字匹配，库里没有则自动创建；
 * - 标签/分类里的新名字自动收编进标签库/分类库。
 */
public final class ExcelImporter {

    /** 导入结果统计 */
    public static class Result {
        public int added;   // 成功新增的本数
        public int skipped; // 跳过的行数（ISBN 重复或书名为空）
        public int fixed;   // 被修正的字段数（无法识别的数字/枚举已按默认值处理）
    }

    /** 表头列名（与 ExcelExporter.HEADERS 对应），导入按列名定位，兼容列顺序不同的备份文件 */
    private static final String[] KNOWN_HEADERS = {
            "ISBN", "书名", "副标题", "作者", "译者", "国家/地区", "出版社",
            "版次", "印次", "页数", "字数", "定价", "豆瓣评分", "分类", "标签",
            "封面", "存放地点", "藏书状态", "阅读状态", "来源", "来源渠道",
            "购买日期", "购买价格", "评分", "备注", "录入时间", "拥有数量", "各本存放位置",
            "系列", "卷号", "阅读顺序"
    };

    private ExcelImporter() {
    }

    /** 从用户选择的 xlsx 文件导入藏书（需在后台线程调用） */
    public static Result importBooks(Context ctx, Uri uri, AppDatabase db) throws Exception {
        List<String[]> rows = readSheet(ctx, uri);
        Result result = new Result();
        if (rows.size() < 2) {
            return result; // 只有表头或空文件
        }

        // ---- 表头校验 + 列名 → 列下标 映射（列顺序不同也能正确导入） ----
        Map<String, Integer> colOf = new HashMap<>();
        String[] header = rows.get(0);
        for (int i = 0; i < header.length; i++) {
            if (header[i] != null && !header[i].trim().isEmpty()) {
                colOf.put(header[i].trim(), i);
            }
        }
        if (!colOf.containsKey("书名")) {
            throw new java.io.IOException("表头缺少「书名」列，这不是本应用导出的备份格式");
        }

        // 地点名 → id 缓存（缺失的自动创建）
        Map<String, Long> locIds = new HashMap<>();
        for (Location loc : db.locationDao().getAll()) {
            locIds.put(loc.name, loc.id);
        }
        // 已有标签/分类名缓存（新名字自动收编）
        Set<String> tagNames = new HashSet<>();
        for (Tag tag : db.tagDao().getAll()) {
            tagNames.add(tag.name);
        }
        Set<String> catNames = new HashSet<>();
        for (Category cat : db.categoryDao().getAll()) {
            catNames.add(cat.name);
        }

        for (int i = 1; i < rows.size(); i++) {
            String[] c = rows.get(i);
            String title = col(c, colOf, "书名");
            if (title.isEmpty()) {
                result.skipped++;
                continue;
            }
            String isbn = col(c, colOf, "ISBN");
            if (!isbn.isEmpty() && db.bookDao().getByIsbn(isbn) != null) {
                result.skipped++; // ISBN 已存在，不覆盖
                continue;
            }

            Book b = new Book();
            b.isbn = isbn;
            b.title = title;
            b.subtitle = col(c, colOf, "副标题");
            b.author = col(c, colOf, "作者");
            b.translator = col(c, colOf, "译者");
            b.country = col(c, colOf, "国家/地区");
            b.publisher = col(c, colOf, "出版社");
            b.publishDate = col(c, colOf, "版次");
            b.printing = col(c, colOf, "印次");
            b.pages = checkedInt(col(c, colOf, "页数"), result);
            b.wordCount = col(c, colOf, "字数");
            b.listPrice = checkedDouble(col(c, colOf, "定价"), result);
            b.doubanRating = col(c, colOf, "豆瓣评分");
            b.category = col(c, colOf, "分类");
            b.tags = col(c, colOf, "标签");
            b.coverUrl = col(c, colOf, "封面");

            // 存放地点：按名字匹配，没有则创建
            String locName = col(c, colOf, "存放地点");
            if (!locName.isEmpty()) {
                Long locId = locIds.get(locName);
                if (locId == null) {
                    Location loc = new Location();
                    loc.name = locName;
                    loc.sort = locIds.size();
                    locId = db.locationDao().insert(loc);
                    locIds.put(locName, locId);
                }
                b.locationId = locId;
            }

            b.stockStatus = checkedEnum(new String[]{"在库", "借出", "遗失"},
                    col(c, colOf, "藏书状态"), result);
            b.readStatus = checkedEnum(new String[]{"未读", "在读", "已读"},
                    col(c, colOf, "阅读状态"), result);
            b.ownType = checkedEnum(new String[]{"自购", "借阅", "赠书"},
                    col(c, colOf, "来源"), result);
            b.sourceChannel = col(c, colOf, "来源渠道");
            b.purchaseDate = col(c, colOf, "购买日期");
            b.purchasePrice = checkedDouble(col(c, colOf, "购买价格"), result);
            b.rating = checkedInt(col(c, colOf, "评分"), result);
            b.note = col(c, colOf, "备注");
            // 录入时间列不回放，以导入时间为准
            int qty = checkedInt(col(c, colOf, "拥有数量"), result);
            b.quantity = Math.max(1, qty);
            b.locationDetail = col(c, colOf, "各本存放位置");
            b.seriesName = col(c, colOf, "系列");
            b.seriesVolume = col(c, colOf, "卷号");
            b.seriesOrder = checkedInt(col(c, colOf, "阅读顺序"), result);

            long now = System.currentTimeMillis();
            b.createdAt = now;
            b.updatedAt = now;
            b.dirty = true;
            db.bookDao().insert(b);
            result.added++;

            // 收编新标签
            for (String t : b.tags.split(",")) {
                String name = t.trim();
                if (!name.isEmpty() && tagNames.add(name)) {
                    Tag tag = new Tag();
                    tag.name = name;
                    tag.sort = tagNames.size();
                    db.tagDao().insert(tag);
                }
            }
            // 收编新分类
            if (!b.category.isEmpty() && catNames.add(b.category)) {
                Category cat = new Category();
                cat.name = b.category;
                cat.sort = catNames.size();
                db.categoryDao().insert(cat);
            }
        }
        return result;
    }

    // ======================= xlsx 解析 =======================

    /** 打开 xlsx（zip），取出 sheet1.xml 并解析为行数组（sharedStrings 一并处理） */
    private static List<String[]> readSheet(Context ctx, Uri uri) throws Exception {
        byte[] sheet = null;
        byte[] shared = null;
        try (InputStream in = ctx.getContentResolver().openInputStream(uri);
             ZipInputStream zis = new ZipInputStream(in)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if ("xl/worksheets/sheet1.xml".equals(entry.getName())) {
                    sheet = readAll(zis);
                } else if ("xl/sharedStrings.xml".equals(entry.getName())) {
                    shared = readAll(zis);
                }
            }
        }
        if (sheet == null) {
            throw new java.io.IOException("文件里没有找到表格数据（sheet1.xml），请确认是本应用导出的 xlsx");
        }
        List<String> sharedStrings = shared == null ? new ArrayList<>() : parseSharedStrings(shared);
        return parseSheet(sheet, sharedStrings);
    }

    private static byte[] readAll(ZipInputStream zis) throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int len;
        while ((len = zis.read(buf)) > 0) {
            bos.write(buf, 0, len);
        }
        return bos.toByteArray();
    }

    /** 解析 sharedStrings.xml：每个 <si> 的文本（可能由多个 <t> 拼接）按序收集 */
    private static List<String> parseSharedStrings(byte[] xml) throws Exception {
        List<String> list = new ArrayList<>();
        XmlPullParser parser = android.util.Xml.newPullParser();
        parser.setInput(new ByteArrayInputStream(xml), "UTF-8");
        StringBuilder cur = null;
        boolean inT = false;
        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            String tag = parser.getName();
            if (event == XmlPullParser.START_TAG) {
                if ("si".equals(tag)) {
                    cur = new StringBuilder();
                } else if ("t".equals(tag) && cur != null) {
                    inT = true;
                }
            } else if (event == XmlPullParser.TEXT) {
                if (inT && cur != null) {
                    cur.append(parser.getText());
                }
            } else if (event == XmlPullParser.END_TAG) {
                if ("t".equals(tag)) {
                    inT = false;
                } else if ("si".equals(tag) && cur != null) {
                    list.add(cur.toString());
                    cur = null;
                }
            }
            event = parser.next();
        }
        return list;
    }

    /** 解析 sheet1.xml：按 <row>/<c> 逐格取值，列位置以单元格引用（A1/B2…）为准 */
    private static List<String[]> parseSheet(byte[] xml, List<String> sharedStrings) throws Exception {
        List<String[]> rows = new ArrayList<>();
        XmlPullParser parser = android.util.Xml.newPullParser();
        parser.setInput(new ByteArrayInputStream(xml), "UTF-8");

        List<String> curCells = null;   // 当前行的单元格值
        String cellType = "";           // 当前单元格类型（s=共享字符串 inlineStr=内联）
        int curCol = -1;                // 当前单元格列下标
        StringBuilder buf = null;       // 当前单元格文本缓冲
        boolean capture = false;        // 是否处于 <v> 或 <t> 内

        int event = parser.getEventType();
        while (event != XmlPullParser.END_DOCUMENT) {
            String tag = parser.getName();
            if (event == XmlPullParser.START_TAG) {
                if ("row".equals(tag)) {
                    curCells = new ArrayList<>();
                } else if ("c".equals(tag) && curCells != null) {
                    cellType = parser.getAttributeValue(null, "t");
                    if (cellType == null) {
                        cellType = "";
                    }
                    String ref = parser.getAttributeValue(null, "r");
                    curCol = ref == null ? curCells.size() : colIndex(ref);
                    buf = new StringBuilder();
                } else if (("v".equals(tag) || "t".equals(tag)) && buf != null) {
                    capture = true;
                }
            } else if (event == XmlPullParser.TEXT) {
                if (capture && buf != null) {
                    buf.append(parser.getText());
                }
            } else if (event == XmlPullParser.END_TAG) {
                if (("v".equals(tag) || "t".equals(tag))) {
                    capture = false;
                } else if ("c".equals(tag) && curCells != null && buf != null) {
                    String raw = buf.toString();
                    String value;
                    if ("s".equals(cellType)) {
                        // 共享字符串：内容是下标
                        int idx;
                        try {
                            idx = (int) Double.parseDouble(raw.trim());
                        } catch (NumberFormatException e) {
                            idx = -1;
                        }
                        value = (idx >= 0 && idx < sharedStrings.size()) ? sharedStrings.get(idx) : "";
                    } else {
                        value = raw; // 内联字符串与数值都是原文
                    }
                    // 补齐中间可能跳过的空列
                    while (curCells.size() <= curCol) {
                        curCells.add("");
                    }
                    curCells.set(curCol, value);
                    buf = null;
                } else if ("row".equals(tag) && curCells != null) {
                    rows.add(curCells.toArray(new String[0]));
                    curCells = null;
                }
            }
            event = parser.next();
        }
        return rows;
    }

    /** 单元格引用转列下标：A→0, B→1, …, AA→26（只看开头的字母部分） */
    private static int colIndex(String ref) {
        int index = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                index = index * 26 + (c - 'A' + 1);
            } else {
                break;
            }
        }
        return index - 1;
    }

    /** 安全取单元格：越界按空串 */
    private static String cell(String[] row, int index) {
        return (index >= 0 && index < row.length && row[index] != null) ? row[index].trim() : "";
    }

    /** 按列名取值：该列不存在（老版本备份缺列）按空串 */
    private static String col(String[] row, Map<String, Integer> colOf, String name) {
        Integer index = colOf.get(name);
        return index == null ? "" : cell(row, index);
    }

    /** 校验数字字段：非空但不是数字 → 按 0 处理并计入修正数 */
    private static int checkedInt(String s, Result result) {
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(s);
        } catch (NumberFormatException e) {
            result.fixed++;
            return 0;
        }
    }

    /** 校验数字字段（double）：非空但不是数字 → 按 0 处理并计入修正数 */
    private static double checkedDouble(String s, Result result) {
        if (s.isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            result.fixed++;
            return 0;
        }
    }

    /** 校验枚举字段：文案不在合法选项里 → 按第一项处理并计入修正数 */
    private static int checkedEnum(String[] options, String text, Result result) {
        if (text.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < options.length; i++) {
            if (options[i].equals(text)) {
                return i;
            }
        }
        result.fixed++;
        return 0;
    }
}
