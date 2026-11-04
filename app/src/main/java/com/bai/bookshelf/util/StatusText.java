package com.bai.bookshelf.util;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 枚举值与展示文案的转换工具（与 arrays.xml 中顺序保持一致）。
 */
public final class StatusText {

    private static final String[] STOCK = {"在库", "借出", "遗失", "电子书签"};
    private static final String[] READ = {"未读", "在读", "已读", "弃读"};
    private static final String[] OWN = {"自购", "借阅", "赠书"};

    private StatusText() {
    }

    public static String stock(int v) {
        return pick(STOCK, v);
    }

    public static String read(int v) {
        return pick(READ, v);
    }

    /**
     * 「借给 张三」/「借给 张三 等 2 人」（v5.8.33）：一本书同时在外又没借出去过就不显示。
     * lenders 为该书的在外借阅人名（去重、按记录顺序），空或 null 返回 null。
     */
    public static String lentTo(java.util.List<String> lenders) {
        if (lenders == null || lenders.isEmpty()) {
            return null;
        }
        String first = lenders.get(0);
        if (first == null || first.trim().isEmpty()) {
            return null;
        }
        if (lenders.size() == 1) {
            return "借给 " + first.trim();
        }
        return "借给 " + first.trim() + " 等 " + lenders.size() + " 人";
    }

    /**
     * 书架/详情的组合状态行（v5.8.33）：在读且书在外面时补「借给 谁」，
     * 阅读进度不受影响，两者并存。
     */
    public static String stockReadLine(int stock, int read, java.util.List<String> lenders) {
        StringBuilder sb = new StringBuilder(stock(stock)).append(" · ").append(read(read));
        String lent = read == com.bai.bookshelf.data.entity.Book.READING ? lentTo(lenders) : null;
        if (lent != null) {
            sb.append(" · ").append(lent);
        }
        return sb.toString();
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
