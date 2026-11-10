package com.bai.bookshelf.ui.checkin;

import com.bai.bookshelf.util.LunarCalendar;

/**
 * 一轮绑定要读的全部输入：门面每次重绑组一个，页面只读不写。
 *
 * <p>放一起是为了让 {@link MonthPage#bind} 变成纯函数——它不再回头改门面的
 * 「已播过入场动画」那类状态，需要回报的只用返回值说。
 */
final class MonthRenderState {

    final MonthPalette palette;
    final LunarCalendar lunar;
    final int role;
    final int todayKey;
    /** 已按目标页夹好的选中日（邻页夹到该月同一天） */
    final int selectedKey;
    final DayPickerState picker;
    /** 转场期间新建的格子直接跳过打卡点入场 */
    final boolean suspendDots;
    final boolean todayChecked;
    /** 今天入场：0 = 不播。判定（播过没有、是不是邻月格）全在门面 */
    final int todayEntranceKey;

    MonthRenderState(MonthPalette palette, LunarCalendar lunar, int role, int todayKey,
                     int selectedKey, DayPickerState picker, boolean suspendDots,
                     boolean todayChecked, int todayEntranceKey) {
        this.palette = palette;
        this.lunar = lunar;
        this.role = role;
        this.todayKey = todayKey;
        this.selectedKey = selectedKey;
        this.picker = picker;
        this.suspendDots = suspendDots;
        this.todayChecked = todayChecked;
        this.todayEntranceKey = todayEntranceKey;
    }
}
