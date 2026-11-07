package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.CheckIn;

import java.util.List;

/** 年视图的年份边界与当前月判定（纯 JVM，可单测）。 */
public final class CheckInYearGrid {

    public static final int RUBBER_BAND_DP = 24;

    /**
     * 无历史打卡时仍允许往前翻的年数：否则新用户第一年左滑会被下界挡住，
     * 需求要的「2026→2025→2024」根本滑不动。
     */
    public static final int MIN_YEAR_SPAN = 5;

    private CheckInYearGrid() {
    }

    /** 下界 = min(最早一条打卡年份, 当前年 − 5)；异常未来数据不会把下界拉高 */
    public static int minYear(List<CheckIn> all, int currentYear) {
        int min = currentYear - MIN_YEAR_SPAN;
        if (all != null) {
            for (CheckIn c : all) {
                if (c != null && c.dayKey > 0) {
                    int y = c.dayKey / 10000;
                    if (y > 0 && y < min) {
                        min = y;
                    }
                }
            }
        }
        return min;
    }

    /** 上界 = 当前年 + 1 */
    public static int maxYear(int currentYear) {
        return currentYear + 1;
    }

    /** delta = -1 上一年 / +1 下一年；越界仍返回被夹住的年，调用方据此做回弹 */
    public static int shiftYear(int current, int delta, int min, int max) {
        int target = current + delta;
        return Math.max(min, Math.min(max, target));
    }

    public static boolean boundaryHit(int current, int delta, int min, int max) {
        int target = current + delta;
        return target < min || target > max;
    }

    /** 年视图按用户指定方向：左滑（dx<0）= 下一年，右滑 = 上一年 */
    public static int deltaForSwipe(float dx) {
        return dx < 0 ? 1 : -1;
    }

    public static boolean isCurrentMonth(int year, int month1, int todayKey) {
        return todayKey / 10000 == year && todayKey / 100 % 100 == month1;
    }
}
