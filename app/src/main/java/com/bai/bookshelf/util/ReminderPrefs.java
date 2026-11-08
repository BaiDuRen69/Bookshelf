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
    /** v5.8.33：详情页到期/逾期横条的开关（本轮唯一新增键） */
    public static final String KEY_BANNER_BORROW_DUE = "banner_borrow_due";
    /** 第 4.4 步：每日打卡提醒（本轮唯一新增 SharedPreferences 键，默认关） */
    public static final String KEY_NOTIFY_CHECKIN_DAILY = "notify_checkin_daily";
    /** v5.9.2：每日打卡提醒的触发时刻（24 小时制，默认 21:00） */
    public static final String KEY_CHECKIN_REMINDER_HOUR = "checkin_reminder_hour";
    public static final String KEY_CHECKIN_REMINDER_MINUTE = "checkin_reminder_minute";
    public static final int DEFAULT_CHECKIN_REMINDER_HOUR = 21;
    public static final int DEFAULT_CHECKIN_REMINDER_MINUTE = 0;

    private ReminderPrefs() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 开关读取：缺键 = 开（保持历史行为）；新加的每日打卡提醒默认关 */
    public static boolean isOn(Context ctx, String key) {
        boolean def = !KEY_NOTIFY_CHECKIN_DAILY.equals(key);
        return prefs(ctx).getBoolean(key, def);
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

    public static boolean bannerBorrowDue(Context ctx) {
        return isOn(ctx, KEY_BANNER_BORROW_DUE);
    }

    public static boolean checkinDaily(Context ctx) {
        return isOn(ctx, KEY_NOTIFY_CHECKIN_DAILY);
    }

    /** 打卡提醒的整点（0–23）；坏数据按默认 21 点处理，不抛异常 */
    public static int checkinReminderHour(Context ctx) {
        int v = prefs(ctx).getInt(KEY_CHECKIN_REMINDER_HOUR, DEFAULT_CHECKIN_REMINDER_HOUR);
        return v < 0 || v > 23 ? DEFAULT_CHECKIN_REMINDER_HOUR : v;
    }

    /** 打卡提醒的分钟（0–59） */
    public static int checkinReminderMinute(Context ctx) {
        int v = prefs(ctx).getInt(KEY_CHECKIN_REMINDER_MINUTE, DEFAULT_CHECKIN_REMINDER_MINUTE);
        return v < 0 || v > 59 ? DEFAULT_CHECKIN_REMINDER_MINUTE : v;
    }

    public static void setCheckinReminderTime(Context ctx, int hour, int minute) {
        prefs(ctx).edit()
                .putInt(KEY_CHECKIN_REMINDER_HOUR, hour)
                .putInt(KEY_CHECKIN_REMINDER_MINUTE, minute)
                .apply();
    }

    /** 「21:00」/「7:05」 */
    public static String checkinReminderTimeText(Context ctx) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d",
                checkinReminderHour(ctx), checkinReminderMinute(ctx));
    }
}
