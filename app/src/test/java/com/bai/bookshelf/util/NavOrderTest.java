package com.bai.bookshelf.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

public class NavOrderTest {

    @Test
    public void 缺失与损坏都退化为默认顺序() {
        assertArrayEquals(NavOrder.DEFAULT, NavOrder.parse(null));
        assertArrayEquals(NavOrder.DEFAULT, NavOrder.parse(""));
        assertArrayEquals(NavOrder.DEFAULT, NavOrder.parse("  "));
        assertArrayEquals(NavOrder.DEFAULT, NavOrder.parse("9,x,-1"));
    }

    @Test
    public void 解析去重去越界并按默认顺序补齐() {
        assertArrayEquals(new int[]{2, 0, 1, 3}, NavOrder.parse("2,0,1,3"));
        assertArrayEquals(new int[]{2, 0, 3, 1}, NavOrder.parse("2,0,0,9,3"));
        assertArrayEquals(new int[]{1, 0, 2, 3}, NavOrder.parse("1"));
    }

    @Test
    public void CSV往返稳定() {
        int[] order = {2, 0, 1, 3};
        assertEquals("2,0,1,3", NavOrder.join(order));
        assertArrayEquals(order, NavOrder.parse(NavOrder.join(order)));
        assertEquals("3,1,0,2", NavOrder.join(NavOrder.parse("3,1,1,0,2")));
    }

    @Test
    public void 规范化处理重复与越界() {
        assertArrayEquals(new int[]{3, 0, 1, 2}, NavOrder.normalize(new int[]{3, 3, 7, 0}));
        assertArrayEquals(NavOrder.DEFAULT, NavOrder.normalize(null));
    }

    @Test
    public void 我的永远不能被隐藏() {
        Set<Integer> raw = new HashSet<>(Arrays.asList(0, 2, 3));
        Set<Integer> hidden = NavOrder.normalizeHidden(raw);
        assertEquals(new HashSet<>(Arrays.asList(0, 2)), hidden);
        assertTrue(NavOrder.normalizeHidden(null).isEmpty());
        assertEquals(new HashSet<>(Arrays.asList(1, 2)),
                NavOrder.normalizeHidden(new HashSet<>(Arrays.asList(1, 2, 99, -1))));
    }

    @Test
    public void CSV隐藏集稳定_空集与坏值() {
        assertEquals("", NavOrder.joinSet(new HashSet<>()));
        assertEquals("0,2", NavOrder.joinSet(new HashSet<>(Arrays.asList(2, 0))));
        assertTrue(NavOrder.parseSet(null).isEmpty());
        Set<Integer> parsed = NavOrder.parseSet("2,a,0");
        assertEquals(new LinkedHashSet<>(Arrays.asList(2, 0)), parsed);
    }

    @Test
    public void 可见性与首个可见页() {
        Set<Integer> hidden = new HashSet<>(Arrays.asList(0, 2));
        assertFalse(NavOrder.visible(0, hidden));
        assertTrue(NavOrder.visible(1, hidden));
        assertTrue(NavOrder.visible(3, hidden));
        assertFalse(NavOrder.visible(9, hidden));
        assertEquals(1, NavOrder.firstVisible(NavOrder.DEFAULT, hidden));
        assertEquals(1, NavOrder.firstVisible(new int[]{2, 1, 0, 3}, hidden));
    }

    @Test
    public void 启动页隐藏后退到第一个可见页() {
        Set<Integer> hidden = new HashSet<>(Arrays.asList(2));
        assertEquals(2, NavOrder.ensureVisible(2, new HashSet<>()));
        assertEquals(0, NavOrder.ensureVisible(2, hidden));
        assertEquals(3, NavOrder.ensureVisible(1,
                new HashSet<>(Arrays.asList(0, 1, 2))));
        assertEquals(0, NavOrder.ensureVisible(9, new HashSet<>()));
    }

    @Test
    public void 文案与默认顺序一致() {
        assertEquals("书架", NavOrder.label(NavOrder.TAB_SHELF));
        assertEquals("统计", NavOrder.label(NavOrder.TAB_STATS));
        assertEquals("打卡", NavOrder.label(NavOrder.TAB_CHECKIN));
        assertEquals("我的", NavOrder.label(NavOrder.TAB_MINE));
        assertEquals("", NavOrder.label(42));
    }
}
