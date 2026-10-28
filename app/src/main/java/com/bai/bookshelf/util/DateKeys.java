package com.bai.bookshelf.util;

import java.util.Calendar;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日期键工具（v4.55，从 ShelfFragment 抽出）：
 * 统一处理 yyyyMMdd 整数键、购入日期的精度展开与按天偏移。
 */
public final class DateKeys {

    /** 年 + 可选月 + 可选日，兼容 2026 / 2026-03 / 2026年3月5日 / 2026.3.5 */
    private static final Pattern DATE = Pattern.compile(
            "(\\d{4})(?:[-/年.](\\d{1,2}))?(?:[-/月.](\\d{1,2}))?");

    private DateKeys() {
    }

    /**
     * 购入日期文本 → {起始日键, 结束日键}。
     * 只填年 = 全年；只填年月 = 整月；填到日 = 当天。解析不出返回 null。
     */
    public static int[] purchaseSpan(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        Matcher m = DATE.matcher(raw.trim());
        if (!m.find()) {
            return null;
        }
        try {
            int year = Integer.parseInt(m.group(1));
            int month = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
            int day = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            if (month < 1 || month > 12) {
                month = 0;
            }
            if (day < 1 || day > 31) {
                day = 0;
            }
            int startMonth = month == 0 ? 1 : month;
            int endMonth = month == 0 ? 12 : month;
            int start = year * 10000 + startMonth * 100 + (day == 0 ? 1 : day);
            int end = year * 10000 + endMonth * 100
                    + (day == 0 ? daysInMonth(year, endMonth) : day);
            return new int[]{start, end};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static int daysInMonth(int year, int month) {
        Calendar c = Calendar.getInstance();
        c.set(year, Math.max(0, month - 1), 1);
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    /** 毫秒时间戳 → yyyyMMdd 整数键 */
    public static int dayKey(long millis) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(millis);
        return c.get(Calendar.YEAR) * 10000
                + (c.get(Calendar.MONTH) + 1) * 100
                + c.get(Calendar.DAY_OF_MONTH);
    }

    /** yyyyMMdd 整数键按天偏移（负数为往前） */
    public static int shiftDayKey(int key, int deltaDays) {
        Calendar c = Calendar.getInstance();
        c.set(key / 10000, Math.max(0, key / 100 % 100 - 1), Math.max(1, key % 100));
        c.add(Calendar.DAY_OF_YEAR, deltaDays);
        return c.get(Calendar.YEAR) * 10000
                + (c.get(Calendar.MONTH) + 1) * 100
                + c.get(Calendar.DAY_OF_MONTH);
    }

    /** 今天（yyyyMMdd） */
    public static int todayKey() {
        return dayKey(System.currentTimeMillis());
    }

    /** yyyy-MM-dd（也兼容 / 分隔）→ yyyyMMdd；非法返回 0 */
    public static int parseKey(String date) {
        if (date == null) {
            return 0;
        }
        String s = date.trim();
        if (s.length() < 8) {
            return 0;
        }
        String[] parts = s.replace('/', '-').split("-");
        if (parts.length != 3) {
            return 0;
        }
        try {
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int day = Integer.parseInt(parts[2]);
            if (month < 1 || month > 12 || day < 1 || day > 31) {
                return 0;
            }
            return year * 10000 + month * 100 + day;
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
