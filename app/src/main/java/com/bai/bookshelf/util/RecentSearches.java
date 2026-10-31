package com.bai.bookshelf.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

/**
 * 最近搜索（v5.8.1）：最多记住 8 条，重复的提到最前。
 *
 * <p>只在「用户明确表示这次搜索有用」时写入——按下键盘搜索键，或者点开了一条结果——
 * 而不是每敲一个字就记，否则记录里全是「活」「活着」这种半截词。
 */
public final class RecentSearches {

    private static final String PREFS = "ui_prefs";
    private static final String KEY = "recent_searches";
    /** 分隔符：普通搜索词里不会出现 U+0001 */
    private static final String SEP = "\u0001";
    private static final int MAX = 8;

    private RecentSearches() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static List<String> list(Context ctx) {
        List<String> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY, "");
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String s : raw.split(SEP)) {
            if (!s.trim().isEmpty()) {
                out.add(s);
            }
        }
        return out;
    }

    public static void add(Context ctx, String query) {
        String q = query == null ? "" : query.trim();
        if (q.isEmpty()) {
            return;
        }
        List<String> all = list(ctx);
        for (int i = all.size() - 1; i >= 0; i--) {
            if (all.get(i).equalsIgnoreCase(q)) {
                all.remove(i);
            }
        }
        all.add(0, q);
        while (all.size() > MAX) {
            all.remove(all.size() - 1);
        }
        prefs(ctx).edit().putString(KEY, android.text.TextUtils.join(SEP, all)).apply();
    }

    public static void clear(Context ctx) {
        prefs(ctx).edit().remove(KEY).apply();
    }
}
