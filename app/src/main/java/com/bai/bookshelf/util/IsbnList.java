package com.bai.bookshelf.util;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 粘贴的 ISBN 清单解析（v5.8.16）：一列书号（换行 / 逗号 / 空格 / 顿号 / 分号分隔都行）
 * 拆成规范 ISBN 列表，保序去重，长度不对的直接丢掉。
 *
 * <p>只做「能不能当书号用」的判断（10 位或 13 位），不校验校验位——
 * 现实里存在校验位写错但确实能查到的书号，拦下来只会让用户一头雾水。
 */
public final class IsbnList {

    /** 一次最多处理多少条，防止误粘一大篇文本把接口打爆 */
    public static final int MAX = 200;

    private IsbnList() {
    }

    /** 解析成规范书号列表（去连字符/空格，10 或 13 位，保序去重） */
    public static List<String> parse(String raw) {
        return parse(raw, MAX);
    }

    public static List<String> parse(String raw, int max) {
        List<String> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        StringBuilder token = new StringBuilder();
        for (int i = 0; i <= raw.length(); i++) {
            char c = i < raw.length() ? raw.charAt(i) : '\n';
            boolean separator = !Character.isLetterOrDigit(c) && c != '-';
            if (!separator) {
                token.append(c);
                continue;
            }
            String isbn = normalize(token.toString());
            if (isValid(isbn) && seen.add(isbn)) {
                out.add(isbn);
                if (out.size() >= max) {
                    return out;
                }
            }
            token.setLength(0);
        }
        return out;
    }

    /** 去掉连字符与空格，末位 X 统一大写 */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c >= '0' && c <= '9') {
                sb.append(c);
            } else if (c == 'x' || c == 'X') {
                sb.append('X');
            }
        }
        return sb.toString();
    }

    /** 13 位纯数字，或 10 位（末位可为 X） */
    public static boolean isValid(String isbn) {
        if (isbn == null) {
            return false;
        }
        if (isbn.length() == 13) {
            return allDigits(isbn);
        }
        if (isbn.length() == 10) {
            return allDigits(isbn.substring(0, 9))
                    && (isbn.charAt(9) == 'X' || allDigits(isbn.substring(9)));
        }
        return false;
    }

    private static boolean allDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
