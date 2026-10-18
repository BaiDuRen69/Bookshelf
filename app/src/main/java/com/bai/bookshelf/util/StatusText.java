package com.bai.bookshelf.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 枚举值与展示文案的转换工具（与 arrays.xml 中顺序保持一致）。
 */
public final class StatusText {

    private static final String[] STOCK = {"在库", "借出", "遗失"};
    private static final String[] READ = {"未读", "在读", "已读"};
    private static final String[] OWN = {"自购", "借阅", "赠书"};

    private StatusText() {
    }

    public static String stock(int v) {
        return pick(STOCK, v);
    }

    public static String read(int v) {
        return pick(READ, v);
    }

    public static String own(int v) {
        return pick(OWN, v);
    }

    private static String pick(String[] arr, int v) {
        return (v >= 0 && v < arr.length) ? arr[v] : arr[0];
    }

    /** 今天日期 yyyy-MM-dd */
    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
    }

    /** 时间戳转 yyyy-MM-dd HH:mm */
    public static String formatTime(long ts) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(new Date(ts));
    }
}
