package com.bai.bookshelf.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.TypedValue;

import com.bai.bookshelf.R;

/**
 * 主题管理（v2.5）：6 套预设配色，用户选择后写 ui_prefs.theme_id，
 * 由 BaseActivity 在每个 Activity 的 super.onCreate 之前 setTheme 挂载，
 * 切换主题后 recreate 当前 Activity 即全页生效。
 *
 * 布局里统一用 ?attr/colorPrimary / colorPrimaryVariant / colorSecondary 取色；
 * 代码里用 {@link #resolveColor} 解析，避免写死 R.color.primary。
 */
public final class ThemeManager {

    public static final int THEME_GREEN = 0;     // 墨绿（默认）
    public static final int THEME_BLUE = 1;      // 黛蓝
    public static final int THEME_RED = 2;       // 赭红
    public static final int THEME_ORANGE = 3;    // 暖橙
    public static final int THEME_PURPLE = 4;    // 紫罗兰
    public static final int THEME_GRAPHITE = 5;  // 石墨

    private static final String PREF = "ui_prefs";
    private static final String KEY_THEME = "theme_id";

    /**
     * colorPrimary 属性 id：appcompat 库定义的主题属性。
     * AGP 默认 nonTransitiveRClass=true，app 的 R.attr 不含库 attr，必须走库的 R 引用。
     */
    public static final int ATTR_COLOR_PRIMARY = androidx.appcompat.R.attr.colorPrimary;

    /** colorSecondary 属性 id（material 库定义，同上需走库 R 引用） */
    public static final int ATTR_COLOR_SECONDARY = com.google.android.material.R.attr.colorSecondary;

    private ThemeManager() {
    }

    /** 当前主题 id（默认墨绿） */
    public static int current(Context ctx) {
        return prefs(ctx).getInt(KEY_THEME, THEME_GREEN);
    }

    /** 保存主题 id */
    public static void set(Context ctx, int themeId) {
        prefs(ctx).edit().putInt(KEY_THEME, themeId).apply();
    }

    /** 在 super.onCreate 之前调用：给 Activity 挂上用户选定的主题 */
    public static void applyTheme(Activity activity) {
        activity.setTheme(styleRes(current(activity)));
    }

    /** 主题 id → style 资源 */
    public static int styleRes(int themeId) {
        switch (themeId) {
            case THEME_BLUE:
                return R.style.Theme_Bookshelf_Blue;
            case THEME_RED:
                return R.style.Theme_Bookshelf_Red;
            case THEME_ORANGE:
                return R.style.Theme_Bookshelf_Orange;
            case THEME_PURPLE:
                return R.style.Theme_Bookshelf_Purple;
            case THEME_GRAPHITE:
                return R.style.Theme_Bookshelf_Graphite;
            case THEME_GREEN:
            default:
                return R.style.Theme_Bookshelf;
        }
    }

    /** 选择器用的主题名称（与 id 顺序一致） */
    public static String[] names() {
        return new String[]{"墨绿（默认）", "黛蓝", "赭红", "暖橙", "紫罗兰", "石墨"};
    }

    /** 选择器用的主题预览色（与 id 顺序一致，与 colors.xml 中各主题 primary 一致） */
    public static int[] previewColors() {
        return new int[]{
                0xFF2E6E5C, // 墨绿
                0xFF3A5BA0, // 黛蓝
                0xFFA34A3A, // 赭红
                0xFFC2701E, // 暖橙
                0xFF6B4E9E, // 紫罗兰
                0xFF4A6B70, // 石墨
        };
    }

    /** 从上下文主题解析属性颜色（替代代码里写死的 R.color.primary 等） */
    public static int resolveColor(Context ctx, int attr) {
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(attr, tv, true);
        if (tv.type >= TypedValue.TYPE_FIRST_COLOR_INT && tv.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return tv.data;
        }
        // 属性指向 color 资源但未内联时（少数 ROM/属性组合），退回资源 id 取色
        if (tv.resourceId != 0) {
            return androidx.core.content.ContextCompat.getColor(ctx, tv.resourceId);
        }
        return 0xFF2E6E5C;
    }

    /** 主色带透明度的版本（选中高亮等场景） */
    public static int primaryAlpha(Context ctx, int alpha) {
        int c = resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
        return (alpha << 24) | (c & 0x00FFFFFF);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }
}
