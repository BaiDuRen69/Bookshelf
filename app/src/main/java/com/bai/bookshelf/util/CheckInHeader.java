package com.bai.bookshelf.util;

/**
 * 打卡页页头文案（纯 JVM）：显示月标题与「距今多少天」。
 *
 * <p>跨年才带年份，同一年只说「11月」；距今取显示月的今天同号日，
 * 该月没有这一天时夹到月末，避免 2 月 31 日这类不存在的锚点。
 */
public final class CheckInHeader {

    private CheckInHeader() {
    }

    /** 202611 + 20261107 → 「11月」；202507 + 20261107 → 「2025 / 7」 */
    public static String title(int yearMonth, int todayKey) {
        int year = yearMonth / 100;
        int month = yearMonth % 100;
        if (year <= 0 || month < 1 || month > 12) {
            return "";
        }
        return year == todayKey / 10000 ? month + "月" : year + " / " + month;
    }

    /** 当前月返回 null；过去「N天前」，未来「N天后」 */
    public static String relative(int yearMonth, int todayKey) {
        int year = yearMonth / 100;
        int month = yearMonth % 100;
        if (year <= 0 || month < 1 || month > 12 || todayKey <= 0) {
            return null;
        }
        if (yearMonth == todayKey / 100) {
            return null;
        }
        int day = Math.min(todayKey % 100, DateKeys.daysInMonth(year, month));
        int anchor = DateKeys.keyOf(year, month, day);
        int days = DateKeys.daysBetween(anchor, todayKey);
        if (days > 0) {
            return days + "天前";
        }
        if (days < 0) {
            return (-days) + "天后";
        }
        return null;
    }
}
