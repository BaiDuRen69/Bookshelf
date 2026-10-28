package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;

import java.util.List;

/**
 * 藏书数据清洗与解析（v3.0）。
 *
 * <p>分两部分：
 * <ol>
 *   <li><b>空安全清洗</b>：为什么需要 —— Room 里 Java String 字段对应的是<b>可空 TEXT 列</b>，
 *   迁移历史里曾多次把列建成 NOT NULL 又修回可空，因此老设备上的存量数据可能存在 null；
 *   而 UI 层普遍直接调用 {@code book.title.toLowerCase(java.util.Locale.ROOT)}、{@code book.author.isEmpty()} 这类方法，
 *   一旦命中 null 就会 NPE 崩溃。做法是在「数据进入 UI / 工具层」的入口处统一把 null 归一化为 ""，
 *   下游代码即可放心直接调用字符串方法，不必到处写判空。
 *   注意：只在读取路径调用，不要在保存路径调用（保存前应保持用户原始输入）。</li>
 *   <li><b>字符串字段解析</b>：字数、印次这些字段在用户侧是自由文本（"28万字" / "320千字" /
 *   "2020-05|3"），排序与统计都需要数值化。此前书架页与统计页各写了一份完全相同的实现，
 *   现统一收敛到这里，避免两处逻辑走偏。</li>
 * </ol>
 */
public final class BookSanitizer {

    private BookSanitizer() {
    }

    /** 清洗单本；null 入参返回 null */
    public static Book normalize(Book b) {
        if (b == null) {
            return null;
        }
        b.isbn = nz(b.isbn);
        b.title = nz(b.title);
        b.subtitle = nz(b.subtitle);
        b.author = nz(b.author);
        b.translator = nz(b.translator);
        b.publisher = nz(b.publisher);
        b.publishDate = sanitizeEdition(nz(b.publishDate));
        b.coverUrl = nz(b.coverUrl);
        b.summary = nz(b.summary);
        b.printing = sanitizeEdition(nz(b.printing));
        b.country = nz(b.country);
        b.doubanRating = nz(b.doubanRating);
        b.wordCount = nz(b.wordCount);
        b.category = nz(b.category);
        b.tags = nz(b.tags);
        b.sourceChannel = nz(b.sourceChannel);
        b.purchaseDate = nz(b.purchaseDate);
        b.locationDetail = nz(b.locationDetail);
        b.note = nz(b.note);
        b.readingStartDate = nz(b.readingStartDate);
        b.readingFinishDate = nz(b.readingFinishDate);
        b.review = nz(b.review);
        b.seriesName = nz(b.seriesName).trim();
        b.seriesVolume = nz(b.seriesVolume).trim();
        return b;
    }

    /** 清洗列表（原地修改并返回同一列表，便于链式使用） */
    public static List<Book> normalizeAll(List<Book> list) {
        if (list == null) {
            return null;
        }
        for (Book b : list) {
            normalize(b);
        }
        return list;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    // ==================== 版次三段式（v3.7） ====================

    /**
     * 版次/印次三段（年/月/第几版）拼成存储串。
     *
     * <p>v3.7 修复：旧实现无条件返回 {@code y + "-" + m + "|" + n}，只填年时会存成
     * 「2022-|」这种带悬空分隔符的脏串，下次打开编辑页时无法按三段拆回，整串被塞进年框。
     * 现在只在对应段有值时才拼分隔符：
     * <ul>
     *   <li>只填年 → 「2022」</li>
     *   <li>年+月 → 「2022-5」</li>
     *   <li>年+第几版 → 「2022|2」</li>
     *   <li>年+月+第几版 → 「2022-5|2」（与旧标准格式完全一致）</li>
     * </ul>
     * 三段全空返回空串。
     */
    public static String joinEdition(String y, String m, String n) {
        y = nz(y).trim();
        m = nz(m).trim();
        n = nz(n).trim();
        if (y.isEmpty() && m.isEmpty() && n.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(y);
        if (!m.isEmpty()) {
            sb.append('-').append(m);
        }
        if (!n.isEmpty()) {
            sb.append('|').append(n);
        }
        return sb.toString();
    }

    /**
     * 拆版次/印次存储串为 {@code {年, 月, 第几版}} 三段。
     *
     * <p>可拆的标准（含旧脏数据「2022-|」这类空段写法，v3.7）返回长度 3 的数组，
     * 每段均为纯数字或空串；含非数字文本的自由格式（如「2022年5月第2版」）返回 null，
     * 由调用方决定兜底行为（编辑页原文塞年框保留）。
     */
    public static String[] splitEdition(String stored) {
        String s = nz(stored).trim();
        if (s.isEmpty()) {
            return null;
        }
        String no = "";
        int pipe = s.indexOf('|');
        if (pipe >= 0) {
            no = s.substring(pipe + 1).trim();
            s = s.substring(0, pipe).trim();
        }
        String y;
        String m;
        int dash = s.indexOf('-');
        if (dash >= 0) {
            y = s.substring(0, dash).trim();
            m = s.substring(dash + 1).trim();
        } else {
            y = s;
            m = "";
        }
        if (!isDigitsOrEmpty(y) || !isDigitsOrEmpty(m) || !isDigitsOrEmpty(no)) {
            return null;
        }
        if (y.isEmpty() && m.isEmpty() && no.isEmpty()) {
            return null;
        }
        return new String[]{y, m, no};
    }

    /**
     * 版次/印次存储串规整（自愈）：可拆则按 {@link #joinEdition} 重拼（消除
     * 「2022-|」这类悬空分隔符，存量脏数据在读取入口自动变干净）；
     * 自由文本原样返回。
     */
    public static String sanitizeEdition(String stored) {
        String[] p = splitEdition(stored);
        return p == null ? nz(stored) : joinEdition(p[0], p[1], p[2]);
    }

    /** 是否为空串或纯数字 */
    private static boolean isDigitsOrEmpty(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    // ==================== 日期排序（v4.0，评审 #12） ====================

    /**
     * 把自由格式的日期文本解析成「年.月」加权数值，用于排序比较。
     *
     * <p>藏书里的日期是自由文本，常见写法有 {@code 2021-03}、{@code 2021-3-5}、
     * {@code 2021/3/5}、{@code 2021年3月}、{@code 2021.3}、{@code 2021} 等。
     * 旧实现直接字符串字典序比较，「2021年3月」与「2021-03」会排出错误顺序。
     * 这里提取前两个数字段作为年、月，返回 年*12+月 的加权值——月粒度足够排序，
     * 同年同月内部顺序无意义；解析不出返回 -1（调用方用于排到最后）。
     */
    public static double parseDateOrder(String date) {
        if (date == null) {
            return -1;
        }
        String s = date.trim();
        // 提取首个数字段为年（1~4 位）
        java.util.regex.Matcher ym = java.util.regex.Pattern
                .compile("(\\d{1,4})").matcher(s);
        if (!ym.find()) {
            return -1;
        }
        double year;
        try {
            year = Double.parseDouble(ym.group(1));
        } catch (NumberFormatException e) {
            return -1;
        }
        if (year < 1000 || year > 3000) {
            return -1; // 不像年份的数字（如页数、价格串进来的）不参与
        }
        // 年份之后的下一段数字为月（可选）
        double month = 0;
        java.util.regex.Matcher mm = java.util.regex.Pattern
                .compile("(\\d{1,2})").matcher(s.substring(ym.end()));
        if (mm.find()) {
            try {
                double v = Double.parseDouble(mm.group(1));
                if (v >= 1 && v <= 12) {
                    month = v;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return year * 12 + month;
    }

    // ==================== 字符串字段 → 数值 ====================

    /**
     * 解析字数字符串：支持「320千字」「28万字」「320000」「约 12 万字」等写法，返回字数值；
     * 无法解析时返回 0。
     */
    public static double parseWords(String s) {
        if (s == null) {
            return 0;
        }
        String t = s.trim();
        if (t.isEmpty()) {
            return 0;
        }
        double multiplier = 1;
        if (t.contains("万")) {
            multiplier = 10000;
        } else if (t.contains("千")) {
            multiplier = 1000;
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (Character.isDigit(c) || c == '.') {
                digits.append(c);
            }
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Double.parseDouble(digits.toString()) * multiplier;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * 解析印次字符串：格式为 {@code yyyy-mm|N}，取 | 后的 N；
     * 无 |、无数字或非法数字返回 0。
     */
    public static int parsePrintingCount(String printing) {
        if (printing == null || printing.isEmpty()) {
            return 0;
        }
        int pipe = printing.indexOf('|');
        if (pipe < 0) {
            return 0;
        }
        try {
            return Integer.parseInt(printing.substring(pipe + 1).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}