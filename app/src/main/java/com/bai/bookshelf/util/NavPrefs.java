package com.bai.bookshelf.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Set;

/** 底栏顺序 / 隐藏 / 启动页的持久化（都放在既有的 ui_prefs 里）。 */
public final class NavPrefs {

    public static final String PREFS = "ui_prefs";
    public static final String KEY_ORDER = "nav_order";
    public static final String KEY_HIDDEN = "nav_hidden";
    public static final String KEY_START = "nav_start_tab";

    private NavPrefs() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** 缺失或损坏都自愈成 0,1,2,3 */
    public static int[] order(Context ctx) {
        return NavOrder.parse(prefs(ctx).getString(KEY_ORDER, null));
    }

    public static void setOrder(Context ctx, int[] order) {
        prefs(ctx).edit().putString(KEY_ORDER, NavOrder.join(order)).apply();
    }

    /** 缺失或损坏都自愈成空集；「我的」会在读取时被剔除 */
    public static Set<Integer> hidden(Context ctx) {
        return NavOrder.normalizeHidden(
                NavOrder.parseSet(prefs(ctx).getString(KEY_HIDDEN, null)));
    }

    public static void setHidden(Context ctx, Set<Integer> hidden) {
        prefs(ctx).edit().putString(KEY_HIDDEN,
                NavOrder.joinSet(NavOrder.normalizeHidden(hidden))).apply();
    }

    /** 启动页若正好被隐藏，自动退到第一个可见 Tab */
    public static int startTab(Context ctx) {
        return NavOrder.ensureVisible(
                prefs(ctx).getInt(KEY_START, NavOrder.TAB_SHELF), hidden(ctx));
    }

    public static void setStartTab(Context ctx, int tab) {
        prefs(ctx).edit().putInt(KEY_START,
                NavOrder.ensureVisible(tab, hidden(ctx))).apply();
    }

    public static void reset(Context ctx) {
        prefs(ctx).edit()
                .remove(KEY_ORDER)
                .remove(KEY_HIDDEN)
                .remove(KEY_START)
                .apply();
    }
}
