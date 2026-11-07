package com.bai.bookshelf.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 离线兜底的中国大陆节日名（纯 JVM）：公历固定日 + 农历传统节日。
 *
 * <p>只认法定节日，不把周末算节日，也不含元宵——年度 API 拿不到时才用它，
 * 颜色与文案仍可正常显示。清明需要节气算法，兜底不含，交给 API。
 * 农历由调用方传入 {@code lunar = {月, 日, 是否闰月}}，闰月不算节日。
 */
public final class HolidayCn {

    private HolidayCn() {
    }

    /** 不是节日返回 null；dayKey <= 0 或农历非法都按「不是」处理 */
    public static String nameOf(int dayKey, int[] lunar) {
        if (dayKey <= 0) {
            return null;
        }
        int md = dayKey % 10000;
        if (md == 101) {
            return "元旦";
        }
        if (md >= 501 && md <= 503) {
            return "劳动节";
        }
        if (md >= 1001 && md <= 1003) {
            return "国庆节";
        }
        if (lunar == null || lunar.length < 2) {
            return null;
        }
        int m = lunar[0];
        int d = lunar[1];
        if (m < 1 || m > 12 || d < 1 || d > 30) {
            return null;
        }
        if (lunar.length > 2 && lunar[2] == 1) {
            return null; // 闰月不算
        }
        if (m == 1 && d <= 3) {
            return "春节";
        }
        if (m == 5 && d == 5) {
            return "端午节";
        }
        if (m == 8 && d == 15) {
            return "中秋节";
        }
        if (m == 12 && (d == 29 || d == 30)) {
            return "除夕";
        }
        return null;
    }

    public static boolean isHoliday(int dayKey, int[] lunar) {
        return nameOf(dayKey, lunar) != null;
    }

    /** 遍历一年 365 天生成兜底表，供 {@link HolidayCalendar#ofFallback} 使用 */
    public static Map<Integer, String> yearNames(int year, LunarCalendar lunar) {
        Map<Integer, String> out = new HashMap<>();
        if (year <= 0) {
            return out;
        }
        for (int m = 1; m <= 12; m++) {
            int days = DateKeys.daysInMonth(year, m);
            for (int d = 1; d <= days; d++) {
                int key = DateKeys.keyOf(year, m, d);
                String name = nameOf(key, lunar == null ? null : lunar.lunar(key));
                if (name != null) {
                    out.put(key, name);
                }
            }
        }
        return out;
    }
}
