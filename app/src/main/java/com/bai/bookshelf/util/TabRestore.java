package com.bai.bookshelf.util;

/**
 * 底部 Tab 存档恢复（v5.10）：纯 JVM，便于单测。
 *
 * <p>四 Tab 之前只存 main_current_tab，其中 2 = 我的；打卡插到第 3 位后
 * 「我的」变成 3。新存档一律带 {@link #KEY_MIGRATED}，因此只有真正来自
 * 旧版本的 Bundle 才做 2 → 3 的一次性转换，新存档里的 2（打卡）不会被误转。
 */
public final class TabRestore {

    public static final int TAB_SHELF = 0;
    public static final int TAB_STATS = 1;
    public static final int TAB_CHECKIN = 2;
    public static final int TAB_MINE = 3;
    public static final int TAB_COUNT = 4;

    public static final String KEY_MIGRATED = "main_tab_migrated_v4";

    private TabRestore() {
    }

    /** @param migrated 存档里是否带新格式标记 */
    public static int restore(int saved, boolean migrated) {
        if (!migrated && saved == TAB_CHECKIN) {
            return TAB_MINE;
        }
        return saved >= 0 && saved < TAB_COUNT ? saved : TAB_SHELF;
    }
}
