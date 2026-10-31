package com.bai.bookshelf.ui.stats;

import java.util.Calendar;

/**
 * 购买热力图的取值逻辑（v5.6 从 PurchaseHeatmapView 抽出）：
 * 购买本数 → 5 级色阶；年月 1 号在全年中的下标。
 */
public final class HeatmapMath {

    /** 0 本、1 本、2 本、3 本、≥4 本的透明度 */
    private static final int[] LEVEL_ALPHA = {0, 70, 120, 175, 255};

    private HeatmapMath() {
    }

    /** 按购买本数取色阶：0 本返回空格底色，≥4 本为最深 */
    public static int colorFor(int count, int primary, int surfaceDim) {
        if (count <= 0) {
            return surfaceDim;
        }
        int level = count >= 4 ? 4 : count;
        int alpha = LEVEL_ALPHA[level];
        return (alpha << 24) | (primary & 0x00FFFFFF);
    }

    /** 指定年月的 1 号是全年第几天（0-based），非法返回 -1 */
    public static int dayIndexOfFirstOfMonth(int year, int month0) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, 0, 1);
        int len = c.getActualMaximum(Calendar.DAY_OF_YEAR);
        Calendar m = Calendar.getInstance();
        m.clear();
        m.set(year, month0, 1);
        int idx = m.get(Calendar.DAY_OF_YEAR) - 1;
        return (idx >= 0 && idx < len) ? idx : -1;
    }
}
