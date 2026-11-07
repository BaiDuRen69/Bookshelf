package com.bai.bookshelf.ui.edit;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.util.ReadingRecords;

import org.junit.Test;

/** 编辑页双模式的纯逻辑（v5.9.1 修订第四步）：可见性矩阵与保存顺序离屏钉住。 */
public class EditReadingModeTest {

    @Test
    public void 字段可见性矩阵两档互斥() {
        assertTrue(EditReadingMode.showsDateInputs(ReadingRecords.MODE_FUZZY));
        assertFalse(EditReadingMode.showsPreciseRow(ReadingRecords.MODE_FUZZY));
        assertFalse(EditReadingMode.showsDateInputs(ReadingRecords.MODE_PRECISE));
        assertTrue(EditReadingMode.showsPreciseRow(ReadingRecords.MODE_PRECISE));
    }

    @Test
    public void 保存顺序精确档三步模糊档两步() {
        assertArrayEquals(new String[]{"mode", "applyDayKeys", "syncTo"},
                EditReadingMode.saveSteps(ReadingRecords.MODE_PRECISE));
        assertArrayEquals(new String[]{"mode", "syncTo"},
                EditReadingMode.saveSteps(ReadingRecords.MODE_FUZZY));
    }

    @Test
    public void 精确档零天不许保存() {
        assertFalse(EditReadingMode.canSavePrecise(0));
        assertTrue(EditReadingMode.canSavePrecise(1));
    }

    @Test
    public void 只读行零天不说零天而说补记() {
        assertEquals("还没记具体日子 · 点这里补记",
                EditReadingMode.readonlyLine(0, "", ""));
        assertEquals("已记 23 天 · 起止 2026-05-01–2026-06-12（自动）",
                EditReadingMode.readonlyLine(23, "2026-05-01", "2026-06-12"));
        assertEquals("在读没有结束日就不带后半段", "已记 5 天 · 起止 2026-05-01（自动）",
                EditReadingMode.readonlyLine(5, "2026-05-01", ""));
    }

    @Test
    public void 切回模糊的提示带上已记天数() {
        assertEquals("改为模糊后按起止区间统计天数，已记的 23 天会保留在打卡记录里",
                EditReadingMode.fuzzySwitchNote(23));
    }
}
