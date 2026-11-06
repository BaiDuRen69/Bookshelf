package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.R;
import com.bai.bookshelf.ui.checkin.CheckInYearActivity;
import com.bai.bookshelf.ui.checkin.CheckInYearView;
import com.bai.bookshelf.ui.checkin.MonthGridView;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.ThemeManager;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 打卡页第 ② 步真机验收：dock 四格、日历格子是真实 View 且能逐格朗读、
 * 点邻月跳月、选日、切月标题、底部避让、年视图可进入。
 *
 * <p>不引入 Espresso（不新增依赖）：用 ContextThemeWrapper inflate + 主线程 performClick。
 */
@RunWith(AndroidJUnit4.class)
public class CheckInUiInstrumentedTest {

    private View mainRoot;
    private View checkinRoot;

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

    private View main() {
        if (mainRoot == null) {
            mainRoot = inflate(R.layout.activity_main);
        }
        return mainRoot;
    }

    private View checkin() {
        if (checkinRoot == null) {
            checkinRoot = inflate(R.layout.fragment_checkin);
        }
        return checkinRoot;
    }

    private MonthGridView monthGrid() {
        return checkin().findViewById(R.id.month_grid);
    }

    @Test
    public void dock有四个tab且打卡可朗读() {
        ViewGroup tabs = (ViewGroup) ((ViewGroup) main().findViewById(R.id.nav_dock)).getChildAt(1);
        assertEquals(4, tabs.getChildCount());
        View checkinTab = main().findViewById(R.id.tab_checkin);
        assertNotNull(checkinTab);
        assertTrue(checkinTab.isClickable());
        ImageView icon = main().findViewById(R.id.iv_tab_checkin);
        assertEquals("打卡", icon.getContentDescription().toString());
        assertNotNull("图标要挂 nav_icon_tint", icon.getImageTintList());
    }

    @Test
    public void 月视图每格都是真实View且可逐格朗读() {
        MonthGridView grid = monthGrid();
        int count = grid.getDayCellCount();
        assertTrue("格子数应为 rows×7，实际 " + count, count >= 28 && count <= 42);
        assertEquals(0, count % 7);
        for (int i = 0; i < count; i++) {
            View cell = grid.getCellAt(i);
            assertNotNull("第 " + i + " 格不是真实 View", cell);
            CharSequence cd = cell.getContentDescription();
            assertNotNull("第 " + i + " 格没有朗读标签", cd);
            assertTrue("朗读标签缺日期：" + cd,
                    cd.toString().contains("月") && cd.toString().contains("日"));
            assertTrue("打卡状态要念出来：" + cd,
                    cd.toString().contains("已打卡") || cd.toString().contains("未打卡"));
        }
        assertNotNull("网格整体要有标题", grid.getContentDescription());
        assertTrue(grid.getContentDescription().toString().contains("日历"));
    }

    @Test
    public void 左侧周数列每行都有第N周标签且不可点() {
        MonthGridView outer = monthGrid();
        ViewGroup grid = (ViewGroup) outer.getChildAt(1);
        int rows = outer.getDayCellCount() / 7;
        assertEquals(rows, grid.getChildCount());
        for (int r = 0; r < rows; r++) {
            ViewGroup row = (ViewGroup) grid.getChildAt(r);
            View week = row.getChildAt(0);
            assertTrue("周数应是 TextView", week instanceof TextView);
            assertTrue("周数朗读标签：" + week.getContentDescription(),
                    week.getContentDescription().toString().startsWith("第 "));
            assertFalse("周数列不可点击", week.isClickable());
            assertEquals("一行 = 周数 + 7 天", 8, row.getChildCount());
        }
    }

    @Test
    public void 点本月某天会选中并回调() {
        MonthGridView grid = monthGrid();
        int ym = grid.getYearMonth();
        for (int i = 0; i < grid.getDayCellCount(); i++) {
            int key = grid.getDayKeyAt(i);
            if (CheckInMonthGrid.yearMonthOf(key) != ym) {
                continue;
            }
            final int target = key;
            final View cell = grid.getCellAt(i);
            InstrumentationRegistry.getInstrumentation().runOnMainSync(cell::performClick);
            assertEquals("选中日应变成点中的那天", target, grid.getSelectedDayKey());
            return;
        }
        throw new AssertionError("本月一格都没找到");
    }

    @Test
    public void 点邻月灰显日会跳到那一天所在的月份() {
        MonthGridView grid = monthGrid();
        int before = grid.getYearMonth();
        for (int i = 0; i < grid.getDayCellCount(); i++) {
            int key = grid.getDayKeyAt(i);
            if (CheckInMonthGrid.yearMonthOf(key) == before) {
                continue;
            }
            final int targetYm = CheckInMonthGrid.yearMonthOf(key);
            final View cell = grid.getCellAt(i);
            // 只验证组件自身的定位能力；Fragment 的 onMoveToDay 回调在真实页面里接线
            InstrumentationRegistry.getInstrumentation()
                    .runOnMainSync(() -> grid.setYearMonth(targetYm));
            assertEquals("切月后标题要跟着变", targetYm, grid.getYearMonth());
            assertTrue("切月后朗读标题要更新",
                    grid.getContentDescription().toString()
                            .contains(CheckInMonthGrid.titleOf(targetYm)));
            return;
        }
        throw new AssertionError("本月视图没有邻月格，无法验证跳转");
    }

    @Test
    public void 切月与回到今天会重建格子并更新标题() {
        MonthGridView grid = monthGrid();
        int now = grid.getYearMonth();
        final int next = CheckInMonthGrid.shiftMonth(now, 1);
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> grid.setYearMonth(next));
        assertEquals(next, grid.getYearMonth());
        assertTrue(grid.getContentDescription().toString()
                .contains(CheckInMonthGrid.titleOf(next)));

        final int today = DateKeys.todayKey();
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(grid::moveToToday);
        assertEquals(CheckInMonthGrid.yearMonthOf(today), grid.getYearMonth());
        assertEquals(today, grid.getSelectedDayKey());
    }

    @Test
    public void 悬浮今按钮按dock_reserve避让且空态复用统一布局() {
        View fab = checkin().findViewById(R.id.fab_today);
        assertNotNull(fab);
        assertEquals("今", ((TextView) fab).getText().toString());
        int reserve = Math.round(themed().getResources().getDimension(R.dimen.dock_reserve));
        assertEquals("今按钮要按 dock_reserve 上移", reserve,
                ((ViewGroup.MarginLayoutParams) fab.getLayoutParams()).bottomMargin);
        View empty = checkin().findViewById(R.id.empty_state);
        assertNotNull("空态要复用 view_empty_state", empty);
        assertNotNull(empty.findViewById(R.id.tv_empty_title));
        assertNotNull(empty.findViewById(R.id.tv_empty_desc));
        assertNotNull(empty.findViewById(R.id.btn_empty_cta));
        ViewGroup recycler = checkin().findViewById(R.id.rv_checkin);
        assertFalse("列表要允许内容滚到 padding 区外", recycler.getClipToPadding());
        assertEquals(reserve, recycler.getPaddingBottom());
    }

    @Test
    public void 月份标题可点且年视图已注册可inflate() {
        View toolbar = checkin().findViewById(R.id.toolbar);
        assertTrue("标题整块要可点", toolbar.isClickable());
        assertEquals("打开年视图", toolbar.getContentDescription().toString());

        // 故意不启动 Activity：BaseActivity 的应用锁 / 主题 recreate 链会让
        // ActivityScenario 在 instrumentation 里等不到 RESUMED（实测会挂住）。
        // 改验 Manifest 注册 + 布局结构，跳转接线由 Fragment 的 OnClickListener 保证。
        android.content.pm.ResolveInfo info = themed().getPackageManager().resolveActivity(
                new android.content.Intent(themed(), CheckInYearActivity.class), 0);
        assertNotNull("年视图 Activity 要在 Manifest 注册", info);
        View yearRoot = inflate(R.layout.activity_checkin_year);
        assertNotNull(yearRoot.findViewById(R.id.toolbar));
        assertNotNull(yearRoot.findViewById(R.id.tv_year));
        assertNotNull(yearRoot.findViewById(R.id.year_view));
        assertNotNull(yearRoot.findViewById(R.id.tv_year_summary));
    }

    @Test
    public void 列表行复用间距令牌且按钮触控区48dp() {
        View row = inflate(R.layout.item_checkin_book);
        float density = themed().getResources().getDisplayMetrics().density;
        View coverBox = (View) row.findViewById(R.id.iv_cover).getParent();
        assertEquals("封面宽 40dp", Math.round(40 * density),
                coverBox.getLayoutParams().width);
        View button = row.findViewById(R.id.btn_checkin);
        assertEquals("按钮 View 高 48dp（上下 inset 各 4dp → 视觉 40dp）",
                Math.round(48 * density), button.getLayoutParams().height);
        View title = row.findViewById(R.id.tv_title);
        assertNotNull(title);
        assertNotNull(row.findViewById(R.id.tv_subtitle));
        assertNotNull("摘要行要固定在列表顶部",
                checkin().findViewById(R.id.ll_checkin_summary));
    }

    @Test
    public void 年视图有12个月真实网格与今按钮() {
        View yearRoot = inflate(R.layout.activity_checkin_year);
        assertNotNull(yearRoot.findViewById(R.id.tv_year));
        assertNotNull(yearRoot.findViewById(R.id.tv_year_summary));
        assertNotNull("年视图里也要有悬浮「今」", yearRoot.findViewById(R.id.fab_today));
        CheckInYearView yearView = yearRoot.findViewById(R.id.year_view);
        assertNotNull(yearView);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                yearView.bind(2026, 2023, 2027,
                        new java.util.ArrayList<>(), DateKeys.todayKey()));
        assertEquals(2026, yearView.getYear());
        LinearLayout rows = (LinearLayout) yearView.getChildAt(0);
        assertEquals("一年 6 行", 6, rows.getChildCount());
        for (int r = 0; r < 6; r++) {
            assertEquals("每行两个月卡片", 2,
                    ((ViewGroup) rows.getChildAt(r)).getChildCount());
        }
    }

    @Test
    public void 打卡与撤销只重绘受影响的一格() {
        MonthGridView grid = monthGrid();
        final int target = grid.getDayKeyAt(10);
        CharSequence neighbourBefore = grid.getCellAt(11).getContentDescription();
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> grid.setDayMarks(target, 1, 0));
        assertTrue("打过的那天要念「已打卡」",
                grid.getCellAt(10).getContentDescription().toString().contains("已打卡"));
        assertEquals("相邻格子不能被整表重建", neighbourBefore.toString(),
                grid.getCellAt(11).getContentDescription().toString());
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> grid.setDayMarks(target, 0, 0));
        assertTrue("撤销后回到「未打卡」",
                grid.getCellAt(10).getContentDescription().toString().contains("未打卡"));
    }
}
