package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.CheckIn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel 导出里的「打卡」sheet 行数据（纯 JVM，可单测）：书名 / 日期 / 是否补卡 / 轮次。
 * 无打卡数据时调用方不生成该 sheet，这里只负责把行排好。
 */
public final class CheckInSheet {

    public static final String NAME = "打卡";
    public static final String[] HEADERS = {"书名", "日期", "是否补卡", "轮次"};

    private CheckInSheet() {
    }

    public static boolean needed(List<CheckIn> all) {
        return all != null && !all.isEmpty();
    }

    /** 按日期升序；书名取不到的（书已删但打卡仍在）标「（已删除）」 */
    public static List<String[]> rows(List<CheckIn> all, Map<Long, String> titles) {
        List<String[]> out = new ArrayList<>();
        out.add(HEADERS);
        if (!needed(all)) {
            return out;
        }
        List<CheckIn> sorted = new ArrayList<>(all);
        sorted.sort((a, b) -> Integer.compare(a.dayKey, b.dayKey));
        Map<Long, String> safe = titles == null ? new HashMap<>() : titles;
        for (CheckIn c : sorted) {
            if (c == null) {
                continue;
            }
            String title = safe.get(c.bookId);
            out.add(new String[]{
                    title == null || title.isEmpty() ? "（已删除）" : title,
                    DateKeys.formatKey(c.dayKey),
                    c.backfill ? "是" : "否",
                    String.valueOf(c.seq)
            });
        }
        return out;
    }
}
