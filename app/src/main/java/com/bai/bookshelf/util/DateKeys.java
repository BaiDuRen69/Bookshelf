package com.bai.bookshelf.util;

import java.util.Calendar;
import java.util.Locale;
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

    /** 部分日期（yyyy / yyyy-MM / yyyy-MM-dd）→ 区间起始日键；非法返回 0 */
    public static int parseStartKey(String date) {
        int[] span = purchaseSpan(date);
        return span == null ? 0 : span[0];
    }

    /** Calendar → yyyyMMdd 整数键 */
    public static int keyOf(Calendar c) {
        return keyOf(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH));
    }

    /** 年月日 → yyyyMMdd 整数键（month1 = 1..12） */
    public static int keyOf(int year, int month1, int day) {
        return year * 10000 + month1 * 100 + day;
    }

    /** yyyyMMdd 整数键 → yyyy-MM-dd */
    public static String formatKey(int key) {
        return String.format(java.util.Locale.ROOT, "%04d-%02d-%02d",
                key / 10000, key / 100 % 100, key % 100);
    }

    /** 两个日键之间的天数（含首尾） */
    public static int daysBetweenInclusive(int startKey, int endKey) {
        Calendar a = Calendar.getInstance();
        a.set(startKey / 10000, startKey / 100 % 100 - 1, startKey % 100, 0, 0, 0);
        Calendar b = Calendar.getInstance();
        b.set(endKey / 10000, endKey / 100 % 100 - 1, endKey % 100, 0, 0, 0);
        long diff = b.getTimeInMillis() - a.getTimeInMillis();
        return (int) Math.round(diff / 86400000.0) + 1;
    }

    /** 两个日键之间的天数（不含结束日）；与含首尾口径差 1 天 */
    public static int daysBetween(int fromKey, int toKey) {
        return daysBetweenInclusive(fromKey, toKey) - 1;
    }

    /**
     * ISO-8601 周号（打卡月视图左侧周数列用）：周一为一周第一天，
     * 第一周是含 1 月 4 日的那一周。跨年的几天按 ISO 口径可能归上一年末周或下一年第 1 周。
     *
     * <p>用 Locale.ROOT + 中午 12 点，避开个别时区在零点附近的夏令时跳变。
     */
    public static int isoWeekOfYear(int dayKey) {
        Calendar c = Calendar.getInstance(Locale.ROOT);
        c.clear();
        c.setFirstDayOfWeek(Calendar.MONDAY);
        c.setMinimalDaysInFirstWeek(4);
        c.set(dayKey / 10000, dayKey / 100 % 100 - 1, dayKey % 100, 12, 0, 0);
        return c.get(Calendar.WEEK_OF_YEAR);
    }

    /**
     * 日期区间预设（日历面板）：0=本月，1=近 3 个月（含本月），2=今年，其他=去年。
     * 返回 {起始日键, 结束日键}。
     */
    public static int[] presetRange(int which, Calendar now) {
        int today = keyOf(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1,
                now.get(Calendar.DAY_OF_MONTH));
        // 必须从传进来的 now 派生：以前这里是 Calendar.getInstance()，
        // 于是「近 3 个月 / 去年」跟着设备时钟走，与 now 无关（跨月时测试结果会跳一天）
        Calendar c = (Calendar) now.clone();
        switch (which) {
            case 0:
                return new int[]{keyOf(now.get(Calendar.YEAR), now.get(Calendar.MONTH) + 1, 1), today};
            case 1:
                c.add(Calendar.MONTH, -2);
                return new int[]{keyOf(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, 1), today};
            case 2:
                return new int[]{keyOf(now.get(Calendar.YEAR), 1, 1), today};
            default:
                c.add(Calendar.YEAR, -1);
                return new int[]{keyOf(c.get(Calendar.YEAR), 1, 1),
                        keyOf(c.get(Calendar.YEAR), 12, 31)};
        }
    }
}
