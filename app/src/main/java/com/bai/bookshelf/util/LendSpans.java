package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.BorrowRecord;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 借出区间（v5.8.33）：阅读时长要把「书在别人手上」的那段日子扣掉。
 *
 * <p>一本书借出去之后人没法读它，但阅读会话的开始/结束日期并不会因此停下——
 * 于是「在读 30 天」里可能有 10 天这本书根本不在本地。这里只做<b>读取时计算</b>：
 * 不落库、不写回 book 字段，也不新建/结束任何会话（借出与收回不碰阅读状态）。
 *
 * <p>日期一律用 yyyyMMdd 整数键（同 {@link DateKeys} / {@link BorrowReminder}），
 * 区间含首尾。
 */
public final class LendSpans {

    private LendSpans() {
    }

    /** 某本书的全部借出区间：已归还取 [lendDate, returnDate]，未归还结束日取今天 */
    public static List<int[]> of(List<BorrowRecord> records, long bookId) {
        List<int[]> out = new ArrayList<>();
        if (records == null) {
            return out;
        }
        int today = DateKeys.todayKey();
        for (BorrowRecord r : records) {
            if (r == null || r.bookId != bookId) {
                continue;
            }
            int[] span = spanOf(r, today);
            if (span != null) {
                out.add(span);
            }
        }
        return out;
    }

    /** 一次遍历把全库借出记录按书分组（调用方建一次，别每本书查库） */
    public static Map<Long, List<int[]>> byBook(List<BorrowRecord> records) {
        Map<Long, List<int[]>> map = new HashMap<>();
        if (records == null) {
            return map;
        }
        int today = DateKeys.todayKey();
        for (BorrowRecord r : records) {
            if (r == null) {
                continue;
            }
            int[] span = spanOf(r, today);
            if (span == null) {
                continue;
            }
            List<int[]> list = map.get(r.bookId);
            if (list == null) {
                list = new ArrayList<>();
                map.put(r.bookId, list);
            }
            list.add(span);
        }
        return map;
    }

    /**
     * 单条记录的区间；lendDate 或 returnDate 解析失败（<=0）、结束早于开始的返回 null，
     * 由 {@link #countUnparsable} 计数供数据体检报数。
     */
    private static int[] spanOf(BorrowRecord r, int today) {
        int start = DateKeys.parseKey(r.lendDate);
        if (start <= 0) {
            return null;
        }
        boolean open = r.status == BorrowRecord.STATUS_OUT;
        int end = open ? today : DateKeys.parseKey(r.returnDate);
        if (end <= 0 || end < start) {
            return null;
        }
        return new int[]{start, end};
    }

    /** 日期残缺/倒序的借出记录条数（这些区间不扣减，但体检要能发现） */
    public static int countUnparsable(List<BorrowRecord> records) {
        if (records == null) {
            return 0;
        }
        int today = DateKeys.todayKey();
        int bad = 0;
        for (BorrowRecord r : records) {
            if (r != null && spanOf(r, today) == null) {
                bad++;
            }
        }
        return bad;
    }

    /**
     * 跨度 [startKey, endKey]（含首尾）扣掉与 spans 的交集后还剩多少天，下限 0。
     * spans 为 null / 空时等价于原天数，老调用点可以直接传空。
     */
    public static int minusDays(int startKey, int endKey, List<int[]> spans) {
        if (startKey <= 0 || endKey <= 0 || endKey < startKey) {
            return 0;
        }
        int total = BorrowReminder.daysBetween(startKey, endKey) + 1;
        if (spans == null || spans.isEmpty()) {
            return Math.max(0, total);
        }
        int cut = 0;
        for (int[] span : spans) {
            if (span == null || span.length < 2 || span[0] <= 0 || span[1] <= 0) {
                continue;
            }
            int from = Math.max(startKey, span[0]);
            int to = Math.min(endKey, span[1]);
            if (to < from) {
                continue;
            }
            cut += BorrowReminder.daysBetween(from, to) + 1;
        }
        return Math.max(0, total - cut);
    }

    /** 某本书的借出区间（没有则返回 null，省掉一堆空 list） */
    public static List<int[]> get(Map<Long, List<int[]>> byBook, long bookId) {
        return byBook == null ? null : byBook.get(bookId);
    }
}
