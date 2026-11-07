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

import com.bai.bookshelf.ui.TabRefresh;
import com.bai.bookshelf.ui.checkin.CheckInFragment;
import com.bai.bookshelf.ui.checkin.CheckInViewSwitcher;
import com.bai.bookshelf.ui.checkin.CheckInYearView;
import com.bai.bookshelf.ui.checkin.MonthGridView;
import com.bai.bookshelf.util.CheckInYearGrid;
import com.bai.bookshelf.util.LunarCalendar;
import com.bai.bookshelf.util.LunarCalendarIcu;
import com.bai.bookshelf.util.ThemeManager;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 打卡日历改造的真机验收：动画容器边界、按钮固定宽度、选中格缩放中心、
 * 年视图当前月蓝色描边、平台 ICU 农历、无历史记录也能左滑到更小年份。
 * 不引入 Espresso：沿用项目的 ContextThemeWrapper inflate + 主线程写法。
 */
@RunWith(AndroidJUnit4.class)
public class CheckInViewSwitchInstrumentedTest {

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
    public void 动画容器只包住月年两层标题栏与今按钮在外() {
        View root = inflate(R.layout.fragment_checkin);
        ViewGroup host = root.findViewById(R.id.view_switch_host);
        assertNotNull(host);
        assertEquals("host 里只有月、年两层", 2, host.getChildCount());
        assertNotNull(root.findViewById(R.id.month_content));
        assertNotNull(root.findViewById(R.id.year_content));
        assertNull("Toolbar 不参与动画", host.findViewById(R.id.toolbar));
        assertNull("悬浮今不参与动画", host.findViewById(R.id.fab_today));
        assertEquals(View.GONE, root.findViewById(R.id.year_content).getVisibility());
        CheckInViewSwitcher switcher = new CheckInViewSwitcher(host,
                root.findViewById(R.id.month_content),
                root.findViewById(R.id.year_content),
                root.findViewById(R.id.year_view),
                root.findViewById(R.id.month_grid));
        assertFalse(switcher.isYearVisible());
        assertFalse(switcher.isAnimating());
    }

    @Test
    public void 三态按钮共用固定宽度() {
        View row = inflate(R.layout.item_checkin_book);
        View btn = row.findViewById(R.id.btn_checkin);
        assertEquals("按钮宽度写死，三态切换不跳",
                row.getResources().getDimensionPixelSize(R.dimen.checkin_button_w),
                btn.getLayoutParams().width);
    }

    @Test
    public void 选中格能算出相对容器的缩放中心() {
        View root = inflate(R.layout.fragment_checkin);
        ViewGroup host = root.findViewById(R.id.view_switch_host);
        MonthGridView grid = root.findViewById(R.id.month_grid);
        int[] pivot = grid.selectedCellCenterIn(host);
        assertEquals(2, pivot.length);
        assertTrue(pivot[0] >= 0);
        assertTrue(pivot[1] >= 0);
    }

    @Test
    public void 年视图当前月蓝色描边其它月无底() {
        View root = inflate(R.layout.fragment_checkin);
        CheckInYearView yearView = root.findViewById(R.id.year_view);
        yearView.bind(2026, 2021, 2027, java.util.Collections.emptyList(), 20261107);
        View current = yearView.monthCardAt(11);
        View other = yearView.monthCardAt(10);
        assertNotNull(current);
        assertNotNull(other);
        assertNotNull("当前月要有蓝色描边", current.getBackground());
        assertNull("其它月不画底", other.getBackground());
    }

    @Test
    public void 平台ICU农历换算合法且不崩() {
        LunarCalendarIcu provider = new LunarCalendarIcu();
        int[] value = provider.lunar(20260217);
        assertNotNull(value);
        assertTrue(value[0] >= 1 && value[0] <= 12);
        assertTrue(value[1] >= 1 && value[1] <= 30);
        assertFalse(new LunarCalendar(provider).label(20260217).isEmpty());
        assertNull("范围外的日期退成不显示", provider.lunar(18000101));
    }

    @Test
    public void 无历史记录也能左滑到更小年份() {
        assertEquals(2021, CheckInYearGrid.minYear(null, 2026));
        assertEquals(-1, CheckInYearGrid.deltaForSwipe(-120f));
        assertEquals(1, CheckInYearGrid.deltaForSwipe(120f));
    }

    @Test
    public void 打卡页接入统一可见性刷新钩子且空视图调用安全() {
        CheckInFragment fragment = new CheckInFragment();
        assertTrue(fragment instanceof TabRefresh);
        // onViewCreated 之前 b == null，钩子必须直接返回，不依赖 Activity/Context
        ((TabRefresh) fragment).onTabShown();
    }
}
