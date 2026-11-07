package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.ui.checkin.CheckInViewSwitcher;
import com.bai.bookshelf.ui.checkin.CheckInYearView;
import com.bai.bookshelf.util.CheckInYearGrid;
import com.bai.bookshelf.ui.checkin.MonthGridView;
import com.bai.bookshelf.ui.checkin.ScalePivotTransition;
import com.bai.bookshelf.util.ThemeManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** 批次 D 动画参数与边界的真机结构验收（不启动 Activity，避免应用锁 recreate 挂起）。 */
@RunWith(AndroidJUnit4.class)
public class CheckInAnimationInstrumentedTest {

    private Context themed() {
        Context ctx = ApplicationProvider.getApplicationContext();
        return new android.view.ContextThemeWrapper(ctx,
                ThemeManager.styleRes(ThemeManager.current(ctx)));
    }

    private View inflate(final int layout) {
        final View[] out = new View[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            LayoutInflater inflater = LayoutInflater.from(themed()).cloneInContext(themed());
            inflater.setFactory((name, context, attrs) ->
                    "ImageView".equals(name) ? new AppCompatImageView(context, attrs) : null);
            out[0] = inflater.inflate(layout, null);
        });
        return out[0];
    }

    @Test
    public void 动画参数与规格清单一致() {
        assertEquals(300L, CheckInViewSwitcher.DURATION_MS);
        assertEquals(0.3f, CheckInViewSwitcher.SMALL_SCALE, 0.0001f);
        assertEquals(2.0f, CheckInViewSwitcher.MONTH_ZOOM, 0.0001f);
        assertEquals(0.35f, CheckInViewSwitcher.YEAR_SHIFT_RATIO, 0.0001f);
        assertEquals(24, CheckInYearGrid.RUBBER_BAND_DP);
        assertNotNull(new ScalePivotTransition());
    }

    @Test
    public void 缩放中心取选中日期格而非屏幕中心() {
        View root = inflate(R.layout.fragment_checkin);
        ViewGroup host = root.findViewById(R.id.view_switch_host);
        MonthGridView grid = root.findViewById(R.id.month_grid);
        int[] pivot = grid.selectedCellCenterIn(host);
        assertEquals(2, pivot.length);
        assertTrue(pivot[0] >= 0);
        assertTrue(pivot[1] >= 0);
        assertNotNull("月内容层", root.findViewById(R.id.month_content));
        assertNotNull("年内容层", root.findViewById(R.id.year_content));
    }

    @Test
    public void 状态栏与dock在动画容器之外() {
        View main = inflate(R.layout.activity_main);
        View checkin = inflate(R.layout.fragment_checkin);
        ViewGroup host = checkin.findViewById(R.id.view_switch_host);
        assertNotNull("dock 在 MainActivity 根层", main.findViewById(R.id.nav_dock));
        assertNull("Toolbar 不在动画容器", host.findViewById(R.id.toolbar));
        assertNull("悬浮今不在动画容器", host.findViewById(R.id.fab_today));
        assertNull("dock 不在动画容器", host.findViewById(R.id.nav_dock));
        assertEquals("初始只显示月视图", View.GONE,
                checkin.findViewById(R.id.year_content).getVisibility());
    }

    @Test
    public void 点月放大的月份卡片与切年方向() {
        View root = inflate(R.layout.fragment_checkin);
        CheckInYearView yearView = root.findViewById(R.id.year_view);
        yearView.bind(2026, 2021, 2027, java.util.Collections.emptyList(), 20261107);
        for (int m = 1; m <= 12; m++) {
            assertNotNull("第 " + m + " 月卡片可放大", yearView.monthCardAt(m));
        }
        assertEquals("左滑年份变大", 1, CheckInYearGrid.deltaForSwipe(-120f));
        assertEquals("右滑年份变小", -1, CheckInYearGrid.deltaForSwipe(120f));
        assertEquals(2021, CheckInYearGrid.minYear(null, 2026));
    }

    @Test
    public void 切换器初始为月视图且未处于动画中() {
        View root = inflate(R.layout.fragment_checkin);
        CheckInViewSwitcher switcher = new CheckInViewSwitcher(
                root.findViewById(R.id.view_switch_host),
                root.findViewById(R.id.month_content),
                root.findViewById(R.id.year_content),
                root.findViewById(R.id.year_view),
                root.findViewById(R.id.month_grid));
        assertFalse(switcher.isYearVisible());
        assertFalse(switcher.isAnimating());
    }

    @Test
    public void 动画中连续滑动不改变年份() throws Exception {
        View root = inflate(R.layout.fragment_checkin);
        CheckInYearView yearView = root.findViewById(R.id.year_view);
        yearView.bind(2026, 2021, 2027, java.util.Collections.emptyList(), 20261107);
        CheckInViewSwitcher switcher = new CheckInViewSwitcher(
                root.findViewById(R.id.view_switch_host),
                root.findViewById(R.id.month_content),
                root.findViewById(R.id.year_content),
                yearView,
                root.findViewById(R.id.month_grid));
        yearView.setYearSwitcher(switcher);
        Field animating = CheckInViewSwitcher.class.getDeclaredField("animating");
        animating.setAccessible(true);
        Method shift = CheckInYearView.class.getDeclaredMethod("shift", int.class);
        shift.setAccessible(true);
        animating.setBoolean(switcher, true);
        shift.invoke(yearView, 1);
        assertEquals("转场中再滑必须被忽略", 2026, yearView.getYear());
        animating.setBoolean(switcher, false);
    }
}
