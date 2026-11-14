package com.bai.bookshelf.ui.checkin;

/**
 * 月历的两种角色，{@code MonthGridView.setRole(int)} 的取值。
 *
 * <p>从 MonthGridView 的常量搬出来：{@link MonthPage} 绑定格子时按角色分支，
 * 这个判定值不该由页面反向引用门面。
 */
public final class MonthRole {

    /** 打卡页角色（默认）：点一天看那天读了哪几本 */
    public static final int CHECKIN = 0;

    /** 精确日期选择器角色：勾/框选「哪几天读过」 */
    public static final int PICKER = 1;

    private MonthRole() {
    }
}
