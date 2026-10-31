package com.bai.bookshelf.util;

/**
 * 量词统一出口（v5.8.1）。
 *
 * <p>以前界面上混着「本 / 种 / 册」三种说法：统计页写「19 种 / 20 册」，
 * 书架写「共 19 本」，详情写「3 本」，同一个数在三个地方三种单位，
 * 用户没法判断哪句算的是书、哪句算的是实物。
 *
 * <p>现在只有两个口径：<b>种</b> = books 表一行（一款书），<b>册</b> = 实物本数
 * （{@code quantity} 与副本表取大）。凡是要报数给用户看的地方都从这里取，
 * 不再手写「本」。
 */
public final class Units {

    private Units() {
    }

    /** 12 种：按「款」计数（藏书总数、购买、读完、想读…） */
    public static String titles(long n) {
        return n + " 种";
    }

    /** 12 册：按实物计数（借出手上、剩余副本、借阅数量…） */
    public static String copies(long n) {
        return n + " 册";
    }

    /** 12 笔：借阅、摘抄这类记录条数 */
    public static String records(long n) {
        return n + " 笔";
    }

    /** 「19 种 / 20 册」；种册相同时只写「19 种」，避免重复 */
    public static String holdings(long titles, long copies) {
        return titles == copies ? titles(titles) : titles(titles) + " / " + copies(copies);
    }
}
