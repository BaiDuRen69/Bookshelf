package com.bai.bookshelf.util;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;
import java.util.HashSet;

@RunWith(AndroidJUnit4.class)
public class NavPrefsTest {

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private SharedPreferences prefs() {
        return ctx.getSharedPreferences(NavPrefs.PREFS, Context.MODE_PRIVATE);
    }

    @Before
    @After
    public void clean() {
        prefs().edit()
                .remove(NavPrefs.KEY_ORDER)
                .remove(NavPrefs.KEY_HIDDEN)
                .remove(NavPrefs.KEY_START)
                .apply();
    }

    @Test
    public void 写入后能读回且隐藏我的被拒绝() {
        NavPrefs.setOrder(ctx, new int[]{2, 0, 1, 3});
        assertArrayEquals(new int[]{2, 0, 1, 3}, NavPrefs.order(ctx));

        NavPrefs.setHidden(ctx, new HashSet<>(Arrays.asList(1, 3)));
        assertEquals(new HashSet<>(Arrays.asList(1)), NavPrefs.hidden(ctx));
    }

    @Test
    public void 启动页不能落在隐藏Tab() {
        NavPrefs.setHidden(ctx, new HashSet<>(Arrays.asList(1)));
        NavPrefs.setStartTab(ctx, 1);
        assertEquals(0, NavPrefs.startTab(ctx));

        NavPrefs.setHidden(ctx, new HashSet<>(Arrays.asList(0, 1, 2)));
        NavPrefs.setStartTab(ctx, 2);
        assertEquals(3, NavPrefs.startTab(ctx));
    }

    @Test
    public void 缺失与损坏自愈() {
        assertArrayEquals(NavOrder.DEFAULT, NavPrefs.order(ctx));
        assertTrue(NavPrefs.hidden(ctx).isEmpty());
        assertEquals(NavOrder.TAB_SHELF, NavPrefs.startTab(ctx));

        prefs().edit()
                .putString(NavPrefs.KEY_ORDER, "9,x")
                .putString(NavPrefs.KEY_HIDDEN, "3,8")
                .putInt(NavPrefs.KEY_START, 99)
                .apply();
        assertArrayEquals(NavOrder.DEFAULT, NavPrefs.order(ctx));
        assertTrue("隐藏集里的「我的」应被剔除", NavPrefs.hidden(ctx).isEmpty());
        assertEquals(NavOrder.TAB_SHELF, NavPrefs.startTab(ctx));
    }

    @Test
    public void 恢复默认清空三项() {
        NavPrefs.setOrder(ctx, new int[]{3, 2, 1, 0});
        NavPrefs.setHidden(ctx, new HashSet<>(Arrays.asList(0)));
        NavPrefs.setStartTab(ctx, 2);

        NavPrefs.reset(ctx);

        assertArrayEquals(NavOrder.DEFAULT, NavPrefs.order(ctx));
        assertTrue(NavPrefs.hidden(ctx).isEmpty());
        assertEquals(NavOrder.TAB_SHELF, NavPrefs.startTab(ctx));
    }
}
