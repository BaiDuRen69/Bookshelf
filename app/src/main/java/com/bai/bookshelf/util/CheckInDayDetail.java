package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.CheckIn;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 某月每一天的打卡明细（纯 JVM）：总览卡与点日详情共用。
 *
 * <p>固定输出整月每一天（含 0 条的），调用方不用自己补空格；书名取不到时
 * 说明书已删除，显示「（已删除）」而不是留下一行空白。
 */
public final class CheckInDayDetail {

    public static final String DELETED_TITLE = "（已删除）";

    public static final class Day {
        public int dayKey;
        public int total;
        public int backfill;
        public boolean future;
        public List<String> titles = new ArrayList<>();
    }

    private CheckInDayDetail() {
    }

    public static List<Day> ofMonth(List<CheckIn> rows, Map<Long, String> titles,
                                    int yearMonth, int todayKey) {
        int year = yearMonth / 100;
        int month = yearMonth % 100;
        List<Day> out = new ArrayList<>();
        if (year <= 0 || month < 1 || month > 12) {
            return out;
        }
        int days = DateKeys.daysInMonth(year, month);
        Map<Integer, List<CheckIn>> byDay = new HashMap<>();
        if (rows != null) {
            for (CheckIn c : rows) {
                if (c == null || c.dayKey / 100 != yearMonth) {
                    continue;
                }
                List<CheckIn> list = byDay.get(c.dayKey);
                if (list == null) {
                    list = new ArrayList<>();
                    byDay.put(c.dayKey, list);
                }
                list.add(c);
            }
        }
        for (int day = 1; day <= days; day++) {
            int key = DateKeys.keyOf(year, month, day);
            Day d = new Day();
            d.dayKey = key;
            d.future = key > todayKey;
            List<CheckIn> list = byDay.get(key);
            if (list != null) {
                Collections.sort(list, new Comparator<CheckIn>() {
                    @Override
                    public int compare(CheckIn a, CheckIn b) {
                        return Long.compare(a.bookId, b.bookId);
                    }
                });
                for (CheckIn c : list) {
                    d.total++;
                    if (c.backfill) {
                        d.backfill++;
                    }
                    String name = titles == null ? null : titles.get(c.bookId);
                    d.titles.add(name == null || name.isEmpty() ? DELETED_TITLE : name);
                }
            }
            out.add(d);
        }
        return out;
    }
}
