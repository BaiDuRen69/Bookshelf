package com.bai.bookshelf.ui.shelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

/**
 * 卡 H 的纯函数部分：下标维/文案维的存取、「不限」删键、脏值自愈。
 * SharedPreferences 的读写由 {@link ShelfViewPrefs} 的实例方法负责，不在 JVM 单测范围内。
 */
public class ShelfViewPrefsTest {

    private Map<String, Object> empty() {
        return new HashMap<>();
    }

    @Test
    public void keysUseShelfFPrefix() {
        assertEquals("shelf_f_stock", ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_STOCK));
        assertEquals("shelf_f_location", ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_LOCATION));
        assertEquals("shelf_f_sort_index", ShelfViewPrefs.KEY_SORT_INDEX);
        assertEquals("shelf_f_sort_reverse", ShelfViewPrefs.KEY_SORT_REVERSE);
    }

    @Test
    public void onlyStockAndReadAreIndexDims() {
        assertTrue(ShelfViewPrefs.isIndexDim(ShelfViewPrefs.DIM_STOCK));
        assertTrue(ShelfViewPrefs.isIndexDim(ShelfViewPrefs.DIM_READ));
        assertFalse(ShelfViewPrefs.isIndexDim(ShelfViewPrefs.DIM_LOCATION));
        assertFalse(ShelfViewPrefs.isIndexDim(ShelfViewPrefs.DIM_COUNTRY));
        // 来源、二级 chips 不在卡 H 的六维里
        assertFalse(ShelfViewPrefs.isSupportedDim("channel"));
        assertFalse(ShelfViewPrefs.isSupportedDim(null));
    }

    @Test
    public void indexDimStoresPositionAndIgnoresText() {
        Map<String, Object> mem = empty();
        ShelfViewPrefs.applySelection(mem, ShelfViewPrefs.DIM_STOCK, 3, "借出中");
        assertEquals(3, ShelfViewPrefs.positionOf(mem, ShelfViewPrefs.DIM_STOCK));
        // 存的是位置本身（Integer），文案参数不参与：arrays.xml 改字不影响记忆
        assertEquals(3, mem.get(ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_STOCK)));
    }

    @Test
    public void labelDimStoresTextSoRenamedEntriesCannotDrift() {
        Map<String, Object> mem = empty();
        ShelfViewPrefs.applySelection(mem, ShelfViewPrefs.DIM_LOCATION, 2, "客厅");
        assertEquals("客厅", ShelfViewPrefs.labelOf(mem, ShelfViewPrefs.DIM_LOCATION));
        // 文案维读下标只会是 0：回填必须走文案查找，不能按位置恢复
        assertEquals(0, ShelfViewPrefs.positionOf(mem, ShelfViewPrefs.DIM_LOCATION));
    }

    @Test
    public void choosingUnlimitedRemovesTheKey() {
        Map<String, Object> mem = empty();
        ShelfViewPrefs.applySelection(mem, ShelfViewPrefs.DIM_TAG, 4, "科幻");
        ShelfViewPrefs.applySelection(mem, ShelfViewPrefs.DIM_TAG, 0, "标签");
        assertNull(mem.get(ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_TAG)));
        assertEquals(0, ShelfViewPrefs.positionOf(mem, ShelfViewPrefs.DIM_TAG));
        assertEquals("", ShelfViewPrefs.labelOf(mem, ShelfViewPrefs.DIM_TAG));
    }

    @Test
    public void emptyLabelDoesNotPersistButPositionDoesNotCrash() {
        Map<String, Object> mem = empty();
        ShelfViewPrefs.applySelection(mem, ShelfViewPrefs.DIM_COUNTRY, 5, null);
        assertFalse(mem.containsKey(ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_COUNTRY)));
        ShelfViewPrefs.applySelection(mem, ShelfViewPrefs.DIM_COUNTRY, 5, "中国");
        ShelfViewPrefs.applySelection(mem, ShelfViewPrefs.DIM_COUNTRY, 6, "");
        assertFalse(mem.containsKey(ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_COUNTRY)));
    }

    @Test
    public void unsupportedDimWritesNothing() {
        Map<String, Object> mem = empty();
        ShelfViewPrefs.applySelection(mem, "channel", 2, "自购");
        ShelfViewPrefs.applySelection(mem, null, 2, "自购");
        assertTrue(mem.isEmpty());
    }

    @Test
    public void sortRoundTrip() {
        Map<String, Object> mem = empty();
        assertEquals(0, ShelfViewPrefs.sortIndexOf(mem));
        assertFalse(ShelfViewPrefs.sortReversedOf(mem));
        mem.put(ShelfViewPrefs.KEY_SORT_INDEX, 4);
        mem.put(ShelfViewPrefs.KEY_SORT_REVERSE, true);
        assertEquals(4, ShelfViewPrefs.sortIndexOf(mem));
        assertTrue(ShelfViewPrefs.sortReversedOf(mem));
    }

    @Test
    public void normalizeDropsUnknownAndNonPositiveValues() {
        Map<String, Object> raw = new HashMap<>();
        raw.put(ShelfViewPrefs.KEY_SORT_INDEX, 2);
        raw.put(ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_STOCK), 0);
        raw.put(ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_READ), -3);
        raw.put(ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_TAG), "绘本");
        raw.put(ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_COUNTRY), "");
        raw.put(ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_LOCATION), null);
        raw.put(ShelfViewPrefs.keyFor("channel"), "不受支持的维度");
        raw.put("shelf_view_mode", 1);

        Map<String, Object> mem = ShelfViewPrefs.normalize(raw);

        assertEquals(2, ShelfViewPrefs.sortIndexOf(mem));
        assertFalse("下标 0 = 不限，不该占键",
                mem.containsKey(ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_STOCK)));
        assertFalse("负数下标自愈成不限",
                mem.containsKey(ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_READ)));
        assertEquals("绘本", ShelfViewPrefs.labelOf(mem, ShelfViewPrefs.DIM_TAG));
        assertFalse(mem.containsKey(ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_COUNTRY)));
        assertFalse("非卡 H 的键不进来", mem.containsKey(ShelfViewPrefs.keyFor("channel")));
        assertFalse("视图模式不归这里管", mem.containsKey("shelf_view_mode"));
    }

    @Test
    public void normalizeToleratesNullAndWrongTypes() {
        assertTrue(ShelfViewPrefs.normalize(null).isEmpty());
        Map<String, Object> raw = new HashMap<>();
        raw.put(ShelfViewPrefs.KEY_SORT_REVERSE, "true");
        raw.put(ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_STOCK), "不是数字");
        raw.put(ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_TAG), 7);
        Map<String, Object> mem = ShelfViewPrefs.normalize(raw);
        assertFalse(ShelfViewPrefs.sortReversedOf(mem));
        assertEquals(0, ShelfViewPrefs.positionOf(mem, ShelfViewPrefs.DIM_STOCK));
        assertEquals(0, ShelfViewPrefs.positionOf(mem, ShelfViewPrefs.DIM_TAG));
    }
}
