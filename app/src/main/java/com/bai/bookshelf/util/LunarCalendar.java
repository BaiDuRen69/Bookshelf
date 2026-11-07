package com.bai.bookshelf.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 农历标签（打卡日历用）：换算交给可注入的 {@link Provider}，这里只做中文格式化 + 按日缓存。
 *
 * <p>真机走 {@code LunarCalendarIcu}（android.icu 平台 API，零新依赖）；JVM 单测注入假
 * Provider 即可覆盖初一/月名/闰月/边界，不会因 android.jar 的 stub 崩掉。
 * 本期只显示农历月名与日名，不做节气与节假日「休/班」。
 */
public final class LunarCalendar {

    /** @return {农历月 1..12, 农历日 1..30, 是否闰月 0/1}；不支持的日期返回 null */
    public interface Provider {
        int[] lunar(int dayKey);
    }

    private static final String[] MONTHS = {
            "正月", "二月", "三月", "四月", "五月", "六月",
            "七月", "八月", "九月", "十月", "冬月", "腊月"};

    private static final String[] DAYS = {
            "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
            "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
            "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十"};

    private final Provider provider;
    private final Map<Integer, String> cache = new HashMap<>();

    public LunarCalendar(Provider provider) {
        this.provider = provider;
    }

    /** 初一显示月名（闰月加「闰」），其余显示日名；取不到换算返回空串 */
    public String label(int dayKey) {
        if (dayKey <= 0) {
            return "";
        }
        String hit = cache.get(dayKey);
        if (hit != null) {
            return hit;
        }
        String text = format(provider == null ? null : provider.lunar(dayKey));
        cache.put(dayKey, text);
        return text;
    }

    /** 原始 {农历月, 日, 是否闰月}：节日兜底判定要月/日，不走中文格式化 */
    public int[] lunar(int dayKey) {
        return provider == null ? null : provider.lunar(dayKey);
    }

    /** 切月时把当月 42 格一次算完，onDraw 只读缓存，不逐格触发换算 */
    public void precompute(int[] dayKeys) {
        if (dayKeys == null) {
            return;
        }
        for (int key : dayKeys) {
            label(key);
        }
    }

    /** 切月/切年时清一次，避免长期持有无用字符串 */
    public void clearCache() {
        cache.clear();
    }

    public static String format(int[] v) {
        if (v == null || v.length < 2) {
            return "";
        }
        int month1 = v[0];
        int day = v[1];
        if (month1 < 1 || month1 > 12 || day < 1 || day > 30) {
            return "";
        }
        if (day == 1) {
            String name = MONTHS[month1 - 1];
            return v.length > 2 && v[2] == 1 ? "闰" + name : name;
        }
        return DAYS[day - 1];
    }
}
