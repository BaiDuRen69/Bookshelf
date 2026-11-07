package com.bai.bookshelf.ui.checkin;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/** 选择器纯逻辑（v5.9.1 修订第三步）：填充/取消/越界/清空都离屏钉住。 */
public class DayPickerStateTest {

    private static final int TODAY = 20261105;

    @Test
    public void 两点填充含端点() {
        DayPickerState s = new DayPickerState();
        s.setRangeMode(true);
        assertEquals(DayPickerState.ACTION_NONE, s.tap(20261101, TODAY)); // 落锚
        assertEquals(20261101, s.anchor());
        assertEquals(DayPickerState.ACTION_FILL, s.tap(20261103, TODAY));
        assertEquals(Arrays.asList(20261101, 20261102, 20261103), s.pickedKeys());
        assertEquals(20261101, s.lastFrom());
        assertEquals(20261103, s.lastTo());
        assertEquals("填完锚点要清掉", 0, s.anchor());
    }

    @Test
    public void 重复点同样两端整段取消() {
        DayPickerState s = new DayPickerState();
        s.setRangeMode(true);
        s.tap(20261101, TODAY);
        s.tap(20261103, TODAY);
        assertEquals("填完锚点已清，再点第一端只是重新落锚",
                DayPickerState.ACTION_NONE, s.tap(20261101, TODAY));
        assertEquals("再点同样终点 = 整段取消",
                DayPickerState.ACTION_CANCEL, s.tap(20261103, TODAY));
        assertTrue(s.pickedKeys().isEmpty());
    }

    @Test
    public void 填充后单点只删那一天() {
        DayPickerState s = new DayPickerState();
        s.setRangeMode(true);
        s.tap(20261101, TODAY);
        s.tap(20261103, TODAY);
        s.setRangeMode(false);
        assertEquals(DayPickerState.ACTION_TOGGLE, s.tap(20261102, TODAY));
        assertEquals(Arrays.asList(20261101, 20261103), s.pickedKeys());
    }

    @Test
    public void 越界日不参与填充也不落锚() {
        DayPickerState s = new DayPickerState();
        s.setRangeMode(true);
        s.tap(20261101, TODAY);
        assertEquals(DayPickerState.ACTION_NONE, s.tap(20261109, TODAY));
        assertEquals("未来日不该把锚点顶掉", 20261101, s.anchor());
        assertTrue(s.pickedKeys().isEmpty());
        assertEquals(DayPickerState.ACTION_FILL, s.tap(20261103, TODAY));
        assertEquals(3, s.pickedKeys().size());
    }

    @Test
    public void 清空只清已选保留参考() {
        DayPickerState s = new DayPickerState();
        s.setReference(Arrays.asList(20261101, 20261102, 20261103));
        s.setPicked(Arrays.asList(20261101, 20261102));
        assertTrue(s.isReference(20261103));
        assertFalse("已选的日子不再算参考", s.isReference(20261101));
        s.clear();
        assertTrue(s.pickedKeys().isEmpty());
        assertTrue("参考底纹留着对照", s.isReference(20261103));
    }

    @Test
    public void 普通模式点参考日变已选() {
        DayPickerState s = new DayPickerState();
        s.setReference(Arrays.asList(20261102));
        assertEquals(DayPickerState.ACTION_TOGGLE, s.tap(20261102, TODAY));
        assertTrue(s.isPicked(20261102));
        assertFalse(s.isReference(20261102));
    }

    @Test
    public void 预览区间不含已选日() {
        DayPickerState s = new DayPickerState();
        s.setPicked(Arrays.asList(20261102));
        s.setPreview(20261101, 20261103);
        assertTrue(s.inPreview(20261101));
        assertFalse("已选的那天不用再画预览", s.inPreview(20261102));
        assertTrue(s.inPreview(20261103));
        s.clearPreview();
        assertFalse(s.inPreview(20261101));
    }
}
