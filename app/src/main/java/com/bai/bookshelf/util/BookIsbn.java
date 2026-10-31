package com.bai.bookshelf.util;

/**
 * ISBN 展示格式化（v5.6，从 BookDetailActivity 抽出）：
 * 无连字符时按 13/10 位规则插入分隔符；已有连字符或无法识别时原样返回。
 */
public final class BookIsbn {

    private BookIsbn() {
    }

    public static String format(String isbn) {
        if (isbn == null) {
            return "";
        }
        if (isbn.contains("-")) {
            return isbn;
        }
        String digits = isbn.replaceAll("[^0-9]", "");
        if (digits.length() == 13 && (digits.startsWith("978") || digits.startsWith("979"))) {
            return digits.substring(0, 3) + "-" + digits.charAt(3) + "-"
                    + digits.substring(4, 7) + "-" + digits.substring(7, 12) + "-" + digits.charAt(12);
        }
        if (digits.length() == 10) {
            return digits.charAt(0) + "-" + digits.substring(1, 4) + "-"
                    + digits.substring(4, 9) + "-" + digits.charAt(9);
        }
        return isbn;
    }
}
