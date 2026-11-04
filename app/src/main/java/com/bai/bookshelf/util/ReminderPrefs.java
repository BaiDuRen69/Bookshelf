package com.bai.bookshelf.util;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 提醒与角标开关（v5.8.32）：全部落在 ui_prefs，默认值 = 本轮之前的行为（全开），
 * 所以老用户升级后什么都不会变。
 *
 * <p>「启动时检查更新」不在这里——它早就存在 update_check/start_check 里，
 * 由 UpdateChecker 自己读写，设置页直接复用它那两个方法，不再新增第二个键。
 */
public final class ReminderPrefs {

    public static final String PREFS = "ui_prefs";
    public static final String KEY_NOTIFY_BORROW_DUE = "notify_borrow_due";
    public static final String KEY_NOTIFY_BORROWED_IN = "notify_borrowed_in";
    public static final String KEY_BADGE_OVERDUE = "badge_overdue";
    public static final String KEY_BANNER_BACKUP_STALE = "banner_backup_stale";
    public static final String KEY_WIDGET_ENABLED = "widget_enabled";

    private ReminderPrefs() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 开关读取：缺键 = 开（保持历史行为） */
    public static boolean isOn(Context ctx, String key) {
        return prefs(ctx).getBoolean(key, true);
    }

    public static void setOn(Context ctx, String key, boolean value) {
        prefs(ctx).edit().putBoolean(key, value).apply();
    }

    public static boolean notifyBorrowDue(Context ctx) {
        return isOn(ctx, KEY_NOTIFY_BORROW_DUE);
    }

    public static boolean notifyBorrowedIn(Context ctx) {
        return isOn(ctx, KEY_NOTIFY_BORROWED_IN);
    }

    public static boolean badgeOverdue(Context ctx) {
        return isOn(ctx, KEY_BADGE_OVERDUE);
    }

    public static boolean bannerBackupStale(Context ctx) {
        return isOn(ctx, KEY_BANNER_BACKUP_STALE);
    }

    public static boolean widgetEnabled(Context ctx) {
        return isOn(ctx, KEY_WIDGET_ENABLED);
    }
}
