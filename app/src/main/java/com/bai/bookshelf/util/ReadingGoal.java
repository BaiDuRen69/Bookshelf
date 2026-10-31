package com.bai.bookshelf.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.DimenRes;

import java.util.Calendar;

/**
 * 年度阅读目标的存取（v5.8.4）。
 *
 * <p>以前跨年时目标会被重置成默认的 24 种，等于每年 1 月 1 日把用户的计划悄悄清掉。
 * 现在改成<b>继承</b>：跨年时沿用去年的目标，并在 1 月第一次打开统计页时问一句
 * 「今年还按 N 种吗」——只问一次，问过就不再打扰。
 */
public final class ReadingGoal {

    private static final String PREFS = "ui_prefs";
    private static final String KEY_YEAR = "reading_goal_year";
    private static final String KEY_GOAL = "reading_goal";
    private static final String KEY_SET = "reading_goal_set";
    private static final String KEY_PROMPT_YEAR = "reading_goal_prompt_year";
    /** 从没设过目标时给的默认值 */
    public static final int DEFAULT = 24;

    private ReadingGoal() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static int year() {
        return Calendar.getInstance().get(Calendar.YEAR);
    }

    private static boolean isJanuary() {
        return Calendar.getInstance().get(Calendar.MONTH) == Calendar.JANUARY;
    }

    /**
     * 本年度生效的目标：跨年时把去年的目标带过来（不再重置成默认值）。
     */
    public static int current(Context ctx) {
        SharedPreferences p = prefs(ctx);
        int thisYear = year();
        int goal = p.getInt(KEY_GOAL, DEFAULT);
        if (goal < 1) {
            goal = DEFAULT;
        }
        if (p.getInt(KEY_YEAR, 0) != thisYear) {
            p.edit().putInt(KEY_YEAR, thisYear).putInt(KEY_GOAL, goal).apply();
        }
        return goal;
    }

    /** 用户自己设过目标（含往年设的，跨年继承算数） */
    public static boolean isSet(Context ctx) {
        return prefs(ctx).getBoolean(KEY_SET, false);
    }

    public static void save(Context ctx, int goal) {
        int value = Math.max(1, goal);
        prefs(ctx).edit()
                .putInt(KEY_YEAR, year())
                .putInt(KEY_GOAL, value)
                .putBoolean(KEY_SET, true)
                .apply();
    }

    /**
     * 1 月要不要问「今年还按 N 种吗」：只在 1 月、且今年还没问过。
     * 去年没设过目标（等于默认值）的用户不问——没有可继承的东西。
     */
    public static boolean needsJanuaryPrompt(Context ctx) {
        if (!isJanuary() || !isSet(ctx)) {
            return false;
        }
        return prefs(ctx).getInt(KEY_PROMPT_YEAR, 0) != year();
    }

    /** 记下今年已经问过（保持与修改都要记，避免反复弹） */
    public static void markPrompted(Context ctx) {
        prefs(ctx).edit().putInt(KEY_PROMPT_YEAR, year()).apply();
    }
}
