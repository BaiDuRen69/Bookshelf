package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;

import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阅读日历（v5.7）：把阅读记录的起止区间铺成「每天是否阅读」，
 * 供详情页单本日历和统计页全库日历共用。
 */
public final class ReadingCalendar {

    private ReadingCalendar() {
    }

    /** 单本书的阅读日（键 yyyy-MM-dd，值固定 1）；不传日集合时精确轮不铺 */
    public static Map<String, Integer> dailyDays(Book book) {
        return dailyDays(book, null);
    }

    /** @param preciseBySeq 该书各轮的日集合（seq → 升序日键），精确轮只铺这些日子 */
    public static Map<String, Integer> dailyDays(Book book,
                                                 Map<Integer, List<Integer>> preciseBySeq) {
        Map<String, Integer> days = new LinkedHashMap<>();
        addBook(days, book, preciseBySeq);
        return days;
    }

    /** 单本书每天的书目（同一天只有本书一条） */
    public static Map<String, List<String>> titlesByDay(Book book) {
        return titlesByDay(book, null);
    }

    public static Map<String, List<String>> titlesByDay(Book book,
                                                        Map<Integer, List<Integer>> preciseBySeq) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (book == null) {
            return out;
        }
        String label = BookText.calendarLabel(book);
        for (String day : dailyDays(book, preciseBySeq).keySet()) {
            List<String> titles = new java.util.ArrayList<>(1);
            titles.add(label);
            out.put(day, titles);
        }
        return out;
    }

    /** 每天读过的书（书名 + 作者），用于点击某天后的书目列表 */
    public static Map<String, List<String>> titlesByDay(List<Book> books) {
        return titlesByDay(books, null);
    }

    /** @param preciseByBook 书 → 轮次 → 日集合（{@link ReadDays#byBook} 组装），精确轮只铺真实日子 */
    public static Map<String, List<String>> titlesByDay(List<Book> books,
                                                        Map<Long, Map<Integer, List<Integer>>> preciseByBook) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        if (books == null) {
            return out;
        }
        for (Book book : books) {
            Map<Integer, List<Integer>> bySeq =
                    preciseByBook == null ? null : preciseByBook.get(book.id);
            for (Map.Entry<String, List<String>> e : titlesByDay(book, bySeq).entrySet()) {
                List<String> titles = out.get(e.getKey());
                if (titles == null) {
                    titles = new java.util.ArrayList<>();
                    out.put(e.getKey(), titles);
                }
                titles.addAll(e.getValue());
            }
        }
        return out;
    }

    /** 最近一个有阅读记录的 {年, 月(0-based)}；没有记录返回当前年月 */
    public static int[] latestYmd(Map<String, Integer> days) {
        int latest = 0;
        if (days != null) {
            for (String key : days.keySet()) {
                latest = Math.max(latest, DateKeys.parseKey(key));
            }
        }
        if (latest > 0) {
            return new int[]{latest / 10000, latest / 100 % 100 - 1};
        }
        Calendar c = Calendar.getInstance();
        return new int[]{c.get(Calendar.YEAR), c.get(Calendar.MONTH)};
    }

    private static void addBook(Map<String, Integer> out, Book book,
                                Map<Integer, List<Integer>> preciseBySeq) {
        if (book == null) {
            return;
        }
        for (ReadingRecords.Record r : ReadingRecords.load(book)) {
            addRecord(out, r, preciseBySeq == null ? null : preciseBySeq.get(r.seq));
        }
    }

    /**
     * 精确轮只铺真实打过卡的日子（v5.9.1）；模糊轮照旧铺起止区间。
     *
     * <p>精确轮但调用方没给日集合（{@code keys == null}）时一天都不铺：
     * 没有真相就不画，宁可少画也不把区间当成「每天都读了」。
     */
    private static void addRecord(Map<String, Integer> out, ReadingRecords.Record r,
                                  List<Integer> keys) {
        if (r.mode == ReadingRecords.MODE_PRECISE) {
            if (keys == null) {
                return;
            }
            int today = DateKeys.todayKey();
            for (int key : keys) {
                if (key > 0 && key <= today) {
                    out.put(DateKeys.formatKey(key), 1);
                }
            }
            return;
        }
        int start = DateKeys.parseKey(r.startDate);
        if (start == 0) {
            return;
        }
        int end = DateKeys.parseKey(r.endDate);
        if (end == 0) {
            if (r.status != Book.READING) {
                return; // 没有结束日期又不是在读，无法确定阅读区间
            }
            end = DateKeys.todayKey();
        }
        int today = DateKeys.todayKey();
        if (end > today) {
            end = today;
        }
        if (end < start) {
            return;
        }
        // 防御异常日期造成的超长循环（最多铺 20 年）
        int guard = 0;
        for (int day = start; day <= end && guard < 8000;
             day = DateKeys.shiftDayKey(day, 1)) {
            out.put(DateKeys.formatKey(day), 1);
            guard++;
        }
    }
}