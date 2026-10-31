package com.bai.bookshelf.util;

/**
 * 编辑页表单文本（v5.2，从 BookEditActivity 抽出）：
 * 字数的「万字 / 万」后缀剥离与统一存储格式。
 */
public final class BookFormText {

    private BookFormText() {
    }

    /** 剥掉字数的「万字 / 万」后缀，用于编辑回显；null 返回空串 */
    public static String stripWanSuffix(String wordCount) {
        if (wordCount == null) {
            return "";
        }
        String s = wordCount.trim();
        if (s.endsWith("万字")) {
            return s.substring(0, s.length() - 2);
        }
        if (s.endsWith("万")) {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** 字数输入 → 统一存储格式「X万字」；已带「万/千」单位的不重复加，空串返回空 */
    public static String wordCountToStore(String input) {
        if (input == null) {
            return "";
        }
        String s = input.trim();
        if (s.isEmpty()) {
            return "";
        }
        if (s.contains("万") || s.contains("千")) {
            return s;
        }
        return s + "万字";
    }
}
