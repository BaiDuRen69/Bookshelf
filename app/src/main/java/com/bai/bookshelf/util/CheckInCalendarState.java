package com.bai.bookshelf.util;

/**
 * 打卡日历三档高度（月 / 周 / 详情）的上拉、下拉迁移规则（纯 JVM）。
 * 手势层只查这张表，不把方向判断散成 if。
 */
public final class CheckInCalendarState {

    public enum Mode {
        MONTH,
        WEEK,
        DETAIL
    }

    private CheckInCalendarState() {
    }

    public static Mode afterUp(Mode mode) {
        if (mode == null) {
            return Mode.MONTH;
        }
        switch (mode) {
            case DETAIL:
                return Mode.MONTH;
            case MONTH:
                return Mode.WEEK;
            default:
                return Mode.WEEK;
        }
    }

    public static Mode afterDown(Mode mode) {
        if (mode == null) {
            return Mode.MONTH;
        }
        switch (mode) {
            case WEEK:
                return Mode.MONTH;
            case MONTH:
                return Mode.DETAIL;
            default:
                return Mode.DETAIL;
        }
    }

    /** 非整月视图时页头收紧一档 */
    public static boolean compactHeader(Mode mode) {
        return mode != null && mode != Mode.MONTH;
    }

    /**
     * 折叠进度 p（0 = 整月，1 = 单周）对应的高度：fullH - p * (fullH - weekH)。
     * p 先夹到 [0,1]，避免动画越界时高度反向。
     */
    public static int heightAt(int fullH, int weekH, float p) {
        float t = Math.max(0f, Math.min(1f, p));
        return Math.round(fullH - t * (fullH - weekH));
    }
}
