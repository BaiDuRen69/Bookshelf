package com.bai.bookshelf.util;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * timor.tech 年度节假日响应解析（纯 JVM，可单测）。
 *
 * <p>只收 {@code holiday=true} 的放假日；{@code holiday=false} 是调休上班，直接忽略，
 * 因此界面上不会出现「休/班」。周末不在年度表里，也不在这里补。
 */
public final class HolidayJson {

    private HolidayJson() {
    }

    /** 解析失败 / 非交易日数据返回空表，由调用方视为失败，不覆盖旧缓存 */
    public static Map<Integer, String> parseYear(String body, int year) {
        if (body == null || body.trim().isEmpty() || year <= 0) {
            return Collections.emptyMap();
        }
        JsonObject root;
        try {
            root = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            return Collections.emptyMap();
        }
        if (root.has("code") && root.get("code").getAsInt() != 0) {
            return Collections.emptyMap();
        }
        JsonObject holiday = root.getAsJsonObject("holiday");
        if (holiday == null) {
            return Collections.emptyMap();
        }
        Map<Integer, String> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : holiday.entrySet()) {
            JsonObject o;
            try {
                o = e.getValue().getAsJsonObject();
            } catch (Exception ignored) {
                continue;
            }
            // holiday=false 是调休上班，只在 API 里有，界面不需要
            if (!o.has("holiday") || !o.get("holiday").getAsBoolean()) {
                continue;
            }
            String name = shortName(o.has("name") ? o.get("name").getAsString() : "");
            if (name.isEmpty()) {
                continue;
            }
            String[] md = e.getKey().split("-");
            if (md.length != 2) {
                continue;
            }
            int m;
            int d;
            try {
                m = Integer.parseInt(md[0]);
                d = Integer.parseInt(md[1]);
            } catch (NumberFormatException ignored) {
                continue;
            }
            if (m < 1 || m > 12 || d < 1 || d > DateKeys.daysInMonth(year, m)) {
                continue;
            }
            out.put(year * 10000 + m * 100 + d, name);
        }
        return out;
    }

    /** "国庆节、中秋节" → "国庆节"；超过 3 个字截到 3 个字，保证塞得进日格 */
    private static String shortName(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.trim();
        for (String sep : new String[]{"、", ",", "，", "/"}) {
            int i = s.indexOf(sep);
            if (i > 0) {
                s = s.substring(0, i);
            }
        }
        s = s.trim();
        return s.length() > 3 ? s.substring(0, 3) : s;
    }
}
