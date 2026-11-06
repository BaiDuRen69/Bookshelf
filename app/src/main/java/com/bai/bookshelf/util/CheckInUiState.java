package com.bai.bookshelf.util;

import java.util.Locale;

/**
 * 打卡页的展示口径（纯 JVM，可单测）：按钮文案、副标题、小结行、撤销/拒绝提示。
 * 不碰 Android View / Context，方便把所有交互文案一次测全。
 */
public final class CheckInUiState {

    private CheckInUiState() {
    }

    /** 选中日 == 今天 → 打卡；早于今天 → 补卡；已打 → 已打卡 ✓ */
    public static String actionLabel(boolean checked, int dayKey, int todayKey) {
        if (checked) {
            return "已打卡 ✓";
        }
        return dayKey < todayKey ? "补卡" : "打卡";
    }

    /** 「读到 43%」；有借出记录时追加「 · 借给 张三」 */
    public static String subtitle(int progress, String borrower) {
        StringBuilder sb = new StringBuilder();
        sb.append(progress > 0 ? "读到 " + progress + "%" : "在读");
        if (borrower != null && !borrower.trim().isEmpty()) {
            sb.append(" · 借给 ").append(borrower.trim());
        }
        return sb.toString();
    }

    /** 「11月已打卡 12 天 · 连续 5 天 · 补卡 2 次」 */
    public static String summaryLine(int yearMonth, int[] summary, int[] streak) {
        return String.format(Locale.ROOT,
                "%d月已打卡 %d 天 · 连续 %d 天 · 补卡 %d 次",
                CheckInMonthGrid.monthOf(yearMonth),
                summary == null ? 0 : summary[0],
                streak == null ? 0 : streak[0],
                summary == null ? 0 : summary[2]);
    }

    /** 摘要行的一个单元：上标签、下「数字 + 单位」，数字为 0 也照实给 */
    public static final class Unit {
        public final String label;
        public final String value;
        public final String unit;
        /** true = 主色；false = 次级色（连续为 0 时不该抢眼） */
        public final boolean accent;
        /** 只挂在「本月打卡」下面：今天还没打卡时的提示，没有就 null */
        public final String note;

        Unit(String label, String value, String unit, boolean accent, String note) {
            this.label = label;
            this.value = value;
            this.unit = unit;
            this.accent = accent;
            this.note = note;
        }
    }

    /**
     * 摘要行的三单元（{@link #summaryLine} 的结构化版本，数字与量词一字不差）：
     * 「本月打卡 N 天」「连续 N 天」「补卡 N 次」。
     */
    public static Unit[] summaryUnits(int[] summary, int[] streak, String note) {
        int days = summary == null ? 0 : summary[0];
        int run = streak == null ? 0 : streak[0];
        int backfills = summary == null ? 0 : summary[2];
        return new Unit[]{
                new Unit("本月打卡", String.valueOf(days), "天", true, note),
                new Unit("连续", String.valueOf(run), "天", run > 0, null),
                new Unit("补卡", String.valueOf(backfills), "次", true, null),
        };
    }

    /** 只有正在看今天所在的那个月、且今天还没打卡，才提示「今天未打」 */
    public static String todayNote(int yearMonth, int todayKey, boolean todayChecked) {
        return !todayChecked && yearMonth == CheckInMonthGrid.yearMonthOf(todayKey)
                ? "今天未打" : null;
    }

    /** 统计页阅读栏的一行；今年 0 次时调用方整行隐藏 */
    public static String statsLine(int year, int[] yearSummary, int[] streak) {
        return String.format(Locale.ROOT,
                "打卡：今年 %d 天 · 连续 %d 天 · 补卡 %d 次",
                yearSummary == null ? 0 : yearSummary[0],
                streak == null ? 0 : streak[0],
                yearSummary == null ? 0 : yearSummary[2]);
    }

    /** 年视图顶部小结：「今年打卡 87 天 · 累计 214 次」 */
    public static String yearLine(int[] yearSummary) {
        return String.format(Locale.ROOT, "今年打卡 %d 天 · 累计 %d 次",
                yearSummary == null ? 0 : yearSummary[0],
                yearSummary == null ? 0 : yearSummary[1]);
    }

    /** 打卡成功的撤销条文案；撤销动作单独用「已撤销打卡」 */
    public static String undoMessage(boolean added, boolean backfill,
                                     String title, int dayKey) {
        if (!added) {
            return "已撤销打卡";
        }
        if (backfill) {
            return "已补卡 " + dayText(dayKey);
        }
        return "已为《" + (title == null ? "" : title) + "》打卡";
    }

    /** 被拒绝时只提示原因，不写库 */
    public static String rejectText(CheckInStore.Result reason) {
        if (reason == null) {
            return "操作未完成";
        }
        switch (reason) {
            case REJECTED_FUTURE:
                return "未来的日期还不能打卡";
            case REJECTED_BEFORE_START:
                return "这一天早于开始阅读日";
            case REJECTED_NOT_READING:
                return "只有「在读」的书能打卡";
            case REJECTED_BACKFILL:
                return "这一天不能补卡";
            default:
                return "操作未完成";
        }
    }

    /** 长按补卡按钮的说明 */
    public static String backfillHint(int dayKey) {
        return "补卡会记录在 " + dayText(dayKey) + "，不影响读完日期";
    }

    /** 「10月28日」 */
    public static String dayText(int dayKey) {
        return (dayKey / 100 % 100) + "月" + (dayKey % 100) + "日";
    }

    /** 未来日期一律不可打卡（按钮禁用） */
    public static boolean actionable(int dayKey, int todayKey) {
        return dayKey <= todayKey;
    }

    /** 读屏朗读：「为《三体》打卡」/「为《三体》补卡」/「《三体》已打卡，点击撤销」 */
    public static String actionContentDescription(String title, boolean checked,
                                                  int dayKey, int todayKey) {
        String safe = title == null ? "" : title;
        if (checked) {
            return "《" + safe + "》已打卡，点击撤销";
        }
        return "为《" + safe + "》" + (dayKey < todayKey ? "补卡" : "打卡");
    }

    /** 每日 21:00 提醒文案：「今天还没打卡，3 本书在读」 */
    public static String dailyReminderText(int readingCount) {
        return "今天还没打卡，" + readingCount + " 本书在读";
    }

    /** 只有开关打开、今天还没打卡、且确实有在读书时才发通知 */
    public static boolean needDailyReminder(boolean enabled, boolean todayChecked,
                                            int readingCount) {
        return enabled && !todayChecked && readingCount > 0;
    }
}
