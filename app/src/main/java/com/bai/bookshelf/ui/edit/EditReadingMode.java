package com.bai.bookshelf.ui.edit;

import com.bai.bookshelf.util.ReadingRecords;

/**
 * 编辑页双模式的纯逻辑（v5.9.1 修订第四步）：字段可见性矩阵、只读行文案、保存顺序。
 *
 * <p>不碰 View，离屏单测能把「哪档显示什么、保存先做什么」钉死，
 * UI 只负责照着摆，避免两处各写一套规则漂移。
 */
public final class EditReadingMode {

    private EditReadingMode() {
    }

    /** 模糊档才显示起止两个输入框；精确档它们是派生值，不给手填 */
    public static boolean showsDateInputs(int mode) {
        return mode != ReadingRecords.MODE_PRECISE;
    }

    /** 精确档才显示「已记 N 天 · 起止（自动）」只读行（点开选择器面板） */
    public static boolean showsPreciseRow(int mode) {
        return mode == ReadingRecords.MODE_PRECISE;
    }

    /** 只读行：「已记 23 天 · 起止 5月1日–6月12日（自动）」；没记日子时不说 0 天 */
    public static String readonlyLine(int days, String startDate, String endDate) {
        if (days <= 0) {
            return "还没记具体日子 · 点这里补记";
        }
        StringBuilder sb = new StringBuilder("已记 ").append(days).append(" 天");
        if (startDate != null && !startDate.isEmpty()) {
            sb.append(" · 起止 ").append(startDate);
            if (endDate != null && !endDate.isEmpty()) {
                sb.append("–").append(endDate);
            }
            sb.append("（自动）");
        }
        return sb.toString();
    }

    /**
     * 保存顺序：精确档固定「写 mode → applyDayKeys → syncTo」，
     * 模糊档没有日集合可对齐，只「写 mode → syncTo」。失败则整体不落库。
     */
    public static String[] saveSteps(int mode) {
        return mode == ReadingRecords.MODE_PRECISE
                ? new String[]{"mode", "applyDayKeys", "syncTo"}
                : new String[]{"mode", "syncTo"};
    }

    /** 精确档至少要有 1 天：不选任何天就保存 → 保持模糊，不静默降级 */
    public static boolean canSavePrecise(int dayCount) {
        return dayCount >= 1;
    }

    /** 精确→模糊的即时反馈：已记的日子留在打卡记录里，只是天数改按区间算 */
    public static String fuzzySwitchNote(int days) {
        return "改为模糊后按起止区间统计天数，已记的 " + days + " 天会保留在打卡记录里";
    }
}
