package com.bai.bookshelf.ui.checkin;

import androidx.annotation.Nullable;

import com.bai.bookshelf.util.HolidayCalendar;

/** 月历格子的读屏文案：纯字符串拼装，行为与原内联实现逐字一致。 */
final class MonthCellText {

    private MonthCellText() {
    }

    /** 「11月5日，已打卡（3 本 · 含 1 笔补卡）」/「11月20日，未来日期，未打卡」 */
    static String describe(@Nullable HolidayCalendar hols, int dayKey, boolean today,
                           int total, int backfill, boolean adjacent, boolean selected,
                           boolean future, boolean todayChecked) {
        StringBuilder sb = new StringBuilder();
        sb.append(dayKey / 100 % 100).append("月").append(dayKey % 100).append("日");
        if (adjacent) {
            sb.append("，邻月");
        }
        if (selected) {
            sb.append("，已选中");
        }
        if (today) {
            sb.append("，今天");
        }
        if (future) {
            sb.append("，未来日期");
        }
        // 邻月也念节日名（「邻月」已先念过），农历仍不朗读，避免 42 格全变长句
        String holiday = hols == null ? null : hols.nameOf(dayKey);
        if (holiday != null) {
            sb.append("，").append(holiday);
        }
        if (total > 0) {
            sb.append("，已打卡").append("（").append(total).append(" 本");
            if (backfill > 0) {
                sb.append(" · 含 ").append(backfill).append(" 笔补卡");
            }
            sb.append("）");
        } else if (today && todayChecked) {
            // 邻月格的 total 恒为 0，今天的真实状态要另看（第 9 项）
            sb.append("，已打卡");
        } else {
            sb.append("，未打卡");
        }
        return sb.toString();
    }

    /** 选择器朗读：「11月5日，已选 / 参考区间 / 不可选」，今天与框选起点一起念 */
    static String describePick(int dayKey, boolean today, boolean blocked,
                               int anchorKey, boolean picked, boolean reference) {
        StringBuilder sb = new StringBuilder();
        sb.append(dayKey / 100 % 100).append("月").append(dayKey % 100).append("日");
        if (today) {
            sb.append("，今天");
        }
        if (dayKey == anchorKey) {
            sb.append("，框选起点");
        }
        if (blocked) {
            sb.append("，不可选");
        } else if (picked) {
            sb.append("，已选");
        } else if (reference) {
            sb.append("，参考区间");
        } else {
            sb.append("，未选");
        }
        return sb.toString();
    }
}
