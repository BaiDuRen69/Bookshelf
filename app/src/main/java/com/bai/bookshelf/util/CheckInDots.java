package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.CheckIn;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 打卡点的排布（纯计算，JVM 可测）：点数按当天打卡的本数走，
 * 补卡的那几本画空心点且实心在左，一天最多三个点。
 *
 * <p>只做「数一数、怎么排」，不判定能不能打卡——判定全在 {@link CheckInStore}。
 */
public final class CheckInDots {

    /** 一天最多三个点：再多也数不清，不如让图例说话 */
    public static final int MAX_DOTS = 3;

    private CheckInDots() {
    }

    /** 点数：1 本一个、2 本两个、≥3 本固定三个，0 本不画 */
    public static int dotCount(int total) {
        return total <= 0 ? 0 : Math.min(total, MAX_DOTS);
    }

    /** 只有一个点时用大点（4dp），两个及以上统一小点（3dp），一排才挤不开 */
    public static boolean useLargeDot(int total) {
        return dotCount(total) == 1;
    }

    /** @return {实心数, 空心数}，两者之和 = {@link #dotCount}；实心排在左边 */
    public static int[] filledHollow(int total, int backfill) {
        int count = dotCount(total);
        int hollow = Math.min(Math.max(backfill, 0), count);
        return new int[]{count - hollow, hollow};
    }

    /**
     * dayKey → {总本数, 其中补卡本数}。只数调用方手里已有的当月结果，
     * 不查库、不新增 DAO 查询。
     */
    public static Map<Integer, int[]> marks(List<CheckIn> monthList) {
        Map<Integer, int[]> out = new HashMap<>();
        if (monthList == null) {
            return out;
        }
        for (CheckIn c : monthList) {
            if (c == null) {
                continue;
            }
            int[] mark = out.get(c.dayKey);
            if (mark == null) {
                mark = new int[2];
                out.put(c.dayKey, mark);
            }
            mark[0]++;
            if (c.backfill) {
                mark[1]++;
            }
        }
        return out;
    }

    /** marks 里取总本数；没有记录给 0 */
    public static int total(Map<Integer, int[]> marks, int dayKey) {
        int[] mark = marks == null ? null : marks.get(dayKey);
        return mark == null ? 0 : mark[0];
    }

    /** marks 里取补卡本数；没有记录给 0 */
    public static int backfill(Map<Integer, int[]> marks, int dayKey) {
        int[] mark = marks == null ? null : marks.get(dayKey);
        return mark == null ? 0 : mark[1];
    }
}
