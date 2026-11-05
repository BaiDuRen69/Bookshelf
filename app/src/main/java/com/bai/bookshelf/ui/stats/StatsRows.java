package com.bai.bookshelf.ui.stats;

import java.util.ArrayList;
import java.util.List;

/**
 * 统计页榜单行的文本模型（v5.9.0）：一行只有「名称 + 数值」两列，
 * 数值列内部用「 · 」串几个小段。拼接是纯字符串逻辑，放这里好单测，
 * StatsFragment 只负责把两列贴到 RowName / RowValue 两个 TextView 上。
 */
public final class StatsRows {

    private static final String SEP = " · ";

    private StatsRows() {
    }

    /** 用「 · 」串起来，null 与空段直接跳过（不留下「A ·  · B」这种洞） */
    public static String join(String... parts) {
        if (parts == null) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String p : parts) {
            if (p != null && !p.trim().isEmpty()) {
                kept.add(p.trim());
            }
        }
        StringBuilder sb = new StringBuilder();
        for (String p : kept) {
            if (sb.length() > 0) {
                sb.append(SEP);
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /** 名称列：空书名给个占位而不是空白行 */
    public static String name(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return "（未命名）";
        }
        return raw.trim();
    }

    /** 数值列：空就回空串，让调用侧决定隐藏还是写「未填」 */
    public static String value(String raw) {
        return raw == null ? "" : raw.trim();
    }
}
