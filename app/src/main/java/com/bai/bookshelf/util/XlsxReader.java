package com.bai.bookshelf.util;

import android.content.Context;
import android.net.Uri;
import android.util.Xml;

import org.xmlpull.v1.XmlPullParser;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 最小 XLSX 读取（v5.6 从 ExcelImporter 抽出）：
 * 从 zip 里取 sheet1.xml 与 sharedStrings.xml，按行/单元格还原为字符串数组。
 */
public final class XlsxReader {

    private XlsxReader() {
    }

    /** 打开 xlsx（zip），取出 sheet1.xml 并解析为行数组（sharedStrings 一并处理） */
    public static List<String[]> readSheet(Context ctx, Uri uri) throws Exception {
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
        List<String> sharedStrings = shared == null
                ? new ArrayList<>() : parseSharedStrings(shared);
        return parseSheet(sheet, sharedStrings);
    }

    /** 解析 sharedStrings.xml：每个 <si> 的文本（可能由多个 <t> 拼接）按序收集 */
    static List<String> parseSharedStrings(byte[] xml) throws Exception {
        List<String> list = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
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
    static List<String[]> parseSheet(byte[] xml, List<String> sharedStrings) throws Exception {
        List<String[]> rows = new ArrayList<>();
        XmlPullParser parser = Xml.newPullParser();
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
    public static int colIndex(String ref) {
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
    public static String cell(String[] row, int index) {
        return (index >= 0 && index < row.length && row[index] != null) ? row[index].trim() : "";
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
}
