package com.bai.bookshelf.util;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 一年的只读节日表：dayKey(yyyyMMdd) → 节日名。
 *
 * <p>{@link #complete()} 为 true 表示来自年度 API（含调休排除后的官方放假日），
 * false 表示离线兜底的近似表。两者对外接口一致，页面不需要分叉。
 */
public final class HolidayCalendar {

    private final Map<Integer, String> names;
    private final boolean complete;

    private HolidayCalendar(Map<Integer, String> names, boolean complete) {
        this.names = Collections.unmodifiableMap(
                names == null ? new HashMap<>() : new HashMap<>(names));
        this.complete = complete;
    }

    public static HolidayCalendar ofApi(Map<Integer, String> names) {
        return new HolidayCalendar(names, true);
    }

    public static HolidayCalendar ofFallback(Map<Integer, String> names) {
        return new HolidayCalendar(names, false);
    }

    /** 无来源时的空表：不画任何节日 */
    public static HolidayCalendar empty() {
        return new HolidayCalendar(null, false);
    }

    /** 不是节日返回 null */
    public String nameOf(int dayKey) {
        return names.get(dayKey);
    }

    public boolean isHoliday(int dayKey) {
        return names.containsKey(dayKey);
    }

    public boolean complete() {
        return complete;
    }

    public int size() {
        return names.size();
    }
}
