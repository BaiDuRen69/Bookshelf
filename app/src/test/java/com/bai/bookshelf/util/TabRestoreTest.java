package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 四 Tab 之后旧存档的 2 → 3 一次性转换，纯 JVM。 */
public class TabRestoreTest {

    @Test
    public void 旧存档里二代表我的要映射到三() {
        assertEquals(TabRestore.TAB_MINE,
                TabRestore.restore(2, false));
        assertEquals("旧版 0/1 不变", TabRestore.TAB_SHELF,
                TabRestore.restore(0, false));
        assertEquals("旧版 1 不变", TabRestore.TAB_STATS,
                TabRestore.restore(1, false));
    }

    @Test
    public void 新存档里二就是打卡不能再转() {
        assertEquals(TabRestore.TAB_CHECKIN,
                TabRestore.restore(2, true));
        assertEquals(TabRestore.TAB_MINE,
                TabRestore.restore(3, true));
        assertEquals(TabRestore.TAB_SHELF,
                TabRestore.restore(0, true));
        assertEquals(TabRestore.TAB_STATS,
                TabRestore.restore(1, true));
    }

    @Test
    public void 越界值一律回到书架() {
        assertEquals(TabRestore.TAB_SHELF, TabRestore.restore(-1, true));
        assertEquals(TabRestore.TAB_SHELF, TabRestore.restore(4, true));
        assertEquals(TabRestore.TAB_SHELF, TabRestore.restore(99, false));
    }

    @Test
    public void 标记常量与顺序固定() {
        assertEquals(0, TabRestore.TAB_SHELF);
        assertEquals(1, TabRestore.TAB_STATS);
        assertEquals(2, TabRestore.TAB_CHECKIN);
        assertEquals(3, TabRestore.TAB_MINE);
        assertEquals(4, TabRestore.TAB_COUNT);
        assertEquals("main_tab_migrated_v4", TabRestore.KEY_MIGRATED);
    }
}
