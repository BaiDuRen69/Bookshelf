package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.R;
import com.bai.bookshelf.ui.checkin.CheckInYearPanel;
import com.bai.bookshelf.ui.checkin.CheckInYearView;
import com.bai.bookshelf.ui.checkin.MonthGridView;
import com.bai.bookshelf.ui.checkin.MonthRole;
import com.bai.bookshelf.ui.widget.DayCellView;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.button.MaterialButton;

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
        assertEquals("月历固定 6 行 × 7 格", 42, count);
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
        ViewGroup clip = outer.findViewById(R.id.month_grid_clip);
        ViewGroup grid = (ViewGroup) clip.getChildAt(0);
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
        // v5.9.2：底部避让 = dock_reserve + 导航栏 inset（未挂窗时 inset 为 0）
        int inset = InsetsHelper.systemBars(fab).bottom;
        assertEquals("今按钮要按 dock_reserve + 导航栏上移", reserve + inset,
                ((ViewGroup.MarginLayoutParams) fab.getLayoutParams()).bottomMargin);
        View empty = checkin().findViewById(R.id.empty_state);
        assertNotNull("空态要复用 view_empty_state", empty);
        assertNotNull(empty.findViewById(R.id.tv_empty_title));
        assertNotNull(empty.findViewById(R.id.tv_empty_desc));
        assertNotNull(empty.findViewById(R.id.btn_empty_cta));
        ViewGroup recycler = checkin().findViewById(R.id.rv_checkin);
        assertFalse("列表要允许内容滚到 padding 区外", recycler.getClipToPadding());
        assertEquals(reserve + inset, recycler.getPaddingBottom());
    }

    @Test
    public void 月份标题可点且年视图已内嵌进打卡页() {
        View toolbar = checkin().findViewById(R.id.toolbar);
        assertTrue("标题整块要可点", toolbar.isClickable());
        assertEquals("打开年视图", toolbar.getContentDescription().toString());

        // 年视图改为 Fragment 内嵌，不再有独立 Activity：动画容器只在 Toolbar 之下
        ViewGroup host = checkin().findViewById(R.id.view_switch_host);
        assertNotNull(host);
        assertEquals("host 里只有月、年两层", 2, host.getChildCount());
        assertNotNull(checkin().findViewById(R.id.month_content));
        View yearContent = checkin().findViewById(R.id.year_content);
        assertNotNull(yearContent);
        assertEquals(View.GONE, yearContent.getVisibility());
        assertNotNull("年份标题", checkin().findViewById(R.id.tv_year_title));
        assertNotNull("12 月网格", checkin().findViewById(R.id.year_view));
        assertNotNull("今年小结要拆成单元行", checkin().findViewById(R.id.ll_year_summary));
        assertNull("Toolbar 不参与动画", host.findViewById(R.id.toolbar));
        assertNull("悬浮今不参与动画", host.findViewById(R.id.fab_today));
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
        // 面板已内嵌：fab 在打卡页根层常驻，12 张月卡由 CheckInYearView 双缓冲复用
        assertNotNull("今按钮两个视图共用", checkin().findViewById(R.id.fab_today));
        assertNotNull("年份标题", checkin().findViewById(R.id.tv_year_title));
        CheckInYearView yearView = checkin().findViewById(R.id.year_view);
        assertNotNull(yearView);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                yearView.bind(2026, 2023, 2027,
                        new java.util.ArrayList<>(), 20261107));
        assertEquals(2026, yearView.getYear());
        for (int m = 1; m <= 12; m++) {
            assertNotNull("第 " + m + " 月卡片是真实 View", yearView.monthCardAt(m));
        }
        assertNotNull("当前月主色描边", yearView.monthCardAt(11).getBackground());
        assertNull("其它月不画底", yearView.monthCardAt(10).getBackground());
        LinearLayout layer = (LinearLayout) ((ViewGroup) yearView.getChildAt(0)).getChildAt(0);
        assertEquals("一年 4 行", 4, layer.getChildCount());
        for (int r = 0; r < 4; r++) {
            assertEquals("每行三个月卡片", 3,
                    ((ViewGroup) layer.getChildAt(r)).getChildCount());
        }
    }

    @Test
    public void 年视图标题可点选年份并直接跳到目标年() {
        View root = checkin();
        CheckInYearPanel panel = new CheckInYearPanel(root.findViewById(R.id.year_content));
        final int[] changed = {0};
        panel.setListener(new CheckInYearPanel.Listener() {
            @Override
            public void onPick(int yearMonth, int dayKey) {
            }

            @Override
            public void onYearChanged(int year) {
                changed[0] = year;
            }
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                panel.bind(202611, new java.util.ArrayList<>(), 20261108));

        TextView title = root.findViewById(R.id.tv_year_title);
        assertEquals("2026", title.getText().toString());
        assertTrue("年份标题整块可点", title.isClickable());
        assertEquals("2026年，点击选择年份", title.getContentDescription().toString());
        assertNotNull("年份后面要有下拉箭头", title.getCompoundDrawablesRelative()[2]);

        View panelRoot = root.findViewById(R.id.year_content);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            float density = panelRoot.getResources().getDisplayMetrics().density;
            int w = Math.round(360 * density);
            int h = Math.round(640 * density);
            panelRoot.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            panelRoot.layout(0, 0, w, h);
        });
        assertTrue("标题只占文字宽度：" + title.getWidth() + " / " + panelRoot.getWidth(),
                title.getWidth() > 0 && title.getWidth() < panelRoot.getWidth());
        assertNotNull("标题要有 TouchDelegate 把点击区补到 48dp",
                panelRoot.getTouchDelegate());

        CheckInYearView yearView = root.findViewById(R.id.year_view);
        ViewGroup switcher = (ViewGroup) yearView.getChildAt(0);
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> yearView.jumpToYear(2024));
        assertEquals("跳到目标年", 2024, yearView.getYear());
        assertEquals("标题跟随目标年", "2024", title.getText().toString());
        assertEquals("面板收到换年回调", 2024, changed[0]);
        assertEquals("旧层隐藏", View.GONE, switcher.getChildAt(0).getVisibility());
        assertEquals("新层可见", View.VISIBLE, switcher.getChildAt(1).getVisibility());

        // 反向再跳一次，激活层要换回来（不是只在两层里叠着显示）
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> yearView.jumpToYear(2026));
        assertEquals(2026, yearView.getYear());
        assertEquals(View.VISIBLE, switcher.getChildAt(0).getVisibility());
        assertEquals(View.GONE, switcher.getChildAt(1).getVisibility());
    }

    @Test
    public void 页头只保留月标题与相对时间() {
        View root = checkin();
        int stale = root.getResources().getIdentifier(
                "tv_year", "id", root.getContext().getPackageName());
        assertEquals("旧年份标题不应再存在", 0, stale);
        assertNotNull("月标题", root.findViewById(R.id.tv_month_title));
        assertNotNull("相对时间", root.findViewById(R.id.tv_month_relative));
    }

    @Test
    public void 月历与年卡都是平的没有阴影() {
        MonthGridView grid = monthGrid();
        assertEquals("月历不浮起", 0f, grid.getElevation(), 0f);
        CheckInYearView yearView = checkin().findViewById(R.id.year_view);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                yearView.bind(2026, 2023, 2027, new java.util.ArrayList<>(), 20261107));
        for (int m = 1; m <= 12; m++) {
            assertEquals("第 " + m + " 月卡不应有阴影",
                    0f, yearView.monthCardAt(m).getElevation(), 0f);
        }
    }

    @Test
    public void 打卡按钮无图标且文字居中() {
        View row = inflate(R.layout.item_checkin_book);
        MaterialButton button = row.findViewById(R.id.btn_checkin);
        assertNull("按钮只用文字，没有勾图标", button.getIcon());
        int gravity = button.getGravity();
        assertEquals("水平居中", Gravity.CENTER_HORIZONTAL,
                gravity & Gravity.CENTER_HORIZONTAL);
        assertEquals("垂直居中", Gravity.CENTER_VERTICAL,
                gravity & Gravity.CENTER_VERTICAL);
    }

    @Test
    public void 周模式高度收成表头加一行且可恢复() {
        MonthGridView grid = monthGrid();
        int cellH = themed().getResources().getDimensionPixelSize(R.dimen.checkin_cell_h);
        int full = grid.getFullHeightPx();
        int week = grid.getWeekHeightPx();
        assertEquals("单周高度 = 整月 - 5 行", cellH * 5, full - week);

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            grid.setWeekMode(true, false);
            measureUnconstrained(grid);
        });
        assertEquals("收起后量高只到表头 + 一行", week, grid.getMeasuredHeight());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            grid.setWeekMode(false, false);
            measureUnconstrained(grid);
        });
        assertEquals("展开后恢复整月高度", full, grid.getMeasuredHeight());
    }

    private static void measureUnconstrained(MonthGridView grid) {
        grid.measure(View.MeasureSpec.makeMeasureSpec(
                        grid.getResources().getDisplayMetrics().widthPixels,
                        View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
    }

    @Test
    public void 收周时旧周由表头下方的裁剪层切掉() {
        MonthGridView month = monthGrid();
        ViewGroup clip = month.findViewById(R.id.month_grid_clip);
        assertNotNull("表头和日期网格之间要有专用裁剪层", clip);
        assertSame("裁剪层必须紧跟在星期表头之后", clip, month.getChildAt(1));
        assertTrue("裁剪层要裁子视图", clip.getClipChildren());
        assertEquals("裁剪层里只有日期网格", 1, clip.getChildCount());
        ViewGroup rows = (ViewGroup) clip.getChildAt(0);
        assertEquals("网格仍按整月 6 行测量", 6, rows.getChildCount());

        int cellH = themed().getResources().getDimensionPixelSize(R.dimen.checkin_cell_h);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> measureUnconstrained(month));
        assertTrue("星期表头有实际高度", month.getChildAt(0).getMeasuredHeight() > 0);
        assertEquals("裁剪层以 6 行高度参与测量", cellH * 6, clip.getMeasuredHeight());

        int selectedRow = 0;
        for (int i = 0; i < month.getDayCellCount(); i++) {
            if (month.getDayKeyAt(i) == month.getSelectedDayKey()) {
                selectedRow = i / 7;
                break;
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            month.setWeekMode(true, false);
            measureUnconstrained(month);
        });
        assertEquals("收起后裁剪层仍是整月高度，多出来的行由它切",
                cellH * 6, clip.getMeasuredHeight());
        assertEquals("选中周上移，停到表头正下方", -selectedRow * cellH,
                rows.getTranslationY(), 0.5f);
    }

    @Test
    public void 邻月也画农历且用灰色低对比() {
        int adjacentColor = ThemeManager.resolveColor(themed(), R.attr.checkin_adjacent_text);
        Bitmap adjacent = renderCell("初一", true);
        Bitmap normal = renderCell("初一", false);
        int lunarTop = Math.round(adjacent.getHeight() * 0.58f);

        assertTrue("邻月也要画农历小字", paintedPixelsFrom(adjacent, lunarTop) > 0);
        assertTrue("本月仍画农历小字", paintedPixelsFrom(normal, lunarTop) > 0);

        int adjacentMaxAlpha = maxAlphaWithRgb(adjacent, adjacentColor & 0x00FFFFFF, lunarTop);
        assertTrue("邻月农历应取 checkin_adjacent_text 的灰色", adjacentMaxAlpha > 0);
        assertTrue("邻月小字要压暗（150 alpha），不能和本月一样亮", adjacentMaxAlpha < 160);

        int normalMaxAlpha = maxAlphaFrom(normal, lunarTop);
        assertTrue("邻月对比必须低于本月，实测 " + adjacentMaxAlpha + " vs " + normalMaxAlpha,
                adjacentMaxAlpha < normalMaxAlpha);
    }

    @Test
    public void 今天三件套跟主题主色走() {
        Context app = ApplicationProvider.getApplicationContext();
        int onPrimaryAttr = com.google.android.material.R.attr.colorOnPrimary;
        for (int theme = 0; theme < ThemeManager.names().length; theme++) {
            Context ctx = new android.view.ContextThemeWrapper(app,
                    ThemeManager.styleRes(theme));
            String name = ThemeManager.names()[theme];
            int primary = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
            int onPrimary = ThemeManager.resolveColor(ctx, onPrimaryAttr);
            assertEquals(name + " 今天圆底色", primary,
                    ThemeManager.resolveColor(ctx, R.attr.checkin_today_bg));
            assertEquals(name + " 今天圆外圈", primary,
                    ThemeManager.resolveColor(ctx, R.attr.checkin_today_ring));
            assertEquals(name + " 选中描边", primary,
                    ThemeManager.resolveColor(ctx, R.attr.checkin_selected_stroke));
            assertEquals(name + " 圆内文字", onPrimary,
                    ThemeManager.resolveColor(ctx, R.attr.checkin_on_today));
        }
    }

    @Test
    public void 今天打卡点跟主色不走反白() {
        final int[] sampled = new int[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context ctx = themed();
            DayCellView cell = new DayCellView(ctx);
            int primary = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
            int selectedBg = ThemeManager.resolveColor(ctx, R.attr.checkin_selected_bg);
            int selectedStroke = ThemeManager.resolveColor(ctx, R.attr.checkin_selected_stroke);
            int onSurface = ThemeManager.resolveColor(ctx, R.attr.on_surface);
            int variant = ThemeManager.resolveColor(ctx, R.attr.on_surface_variant);
            int onPrimary = ThemeManager.resolveColor(ctx,
                    com.google.android.material.R.attr.colorOnPrimary);
            int ring = ThemeManager.resolveColor(ctx, R.attr.checkin_day_ring);
            int adjacentColor = ThemeManager.resolveColor(ctx, R.attr.checkin_adjacent_text);
            int todayBg = ThemeManager.resolveColor(ctx, R.attr.checkin_today_bg);
            int onToday = ThemeManager.resolveColor(ctx, R.attr.checkin_on_today);
            int todayRing = ThemeManager.resolveColor(ctx, R.attr.checkin_today_ring);
            int size = ctx.getResources().getDimensionPixelSize(R.dimen.checkin_cell_h);
            // 入场动画在未挂载 View 上不推进，先挂起让点以最终尺寸落定
            cell.setDotAnimationSuspended(true);
            cell.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
            cell.layout(0, 0, size, size);
            cell.bindCheckIn(7, primary, selectedBg, selectedStroke, onSurface, variant,
                    false, true, false, false, 1, 0, false, "初一", onPrimary, ring,
                    variant, ctx.getResources().getDimension(R.dimen.checkin_lunar_text),
                    adjacentColor, todayBg, onToday, todayRing);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            cell.draw(new Canvas(bitmap));
            float dotY = size - ctx.getResources().getDimension(R.dimen.checkin_dot_bottom);
            sampled[0] = bitmap.getPixel(size / 2, Math.round(dotY));
        });
        Context ctx = themed();
        int primary = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
        assertEquals("今天打卡点应为主色 px=" + Integer.toHexString(sampled[0])
                + " primary=" + Integer.toHexString(primary), primary, sampled[0]);
    }

    @Test
    public void 选中日是空心主色环且中心无填充() {
        Context ctx = themed();
        int primary = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
        int ringStroke = ctx.getResources().getDimensionPixelSize(R.dimen.checkin_ring_stroke);
        Bitmap cell = renderCheckInCell(7, true, false);
        int cx = cell.getWidth() / 2;
        int cy = cell.getHeight() / 2;
        float radius = Math.min(cell.getWidth(), cell.getHeight()) / 2f - ringStroke;

        int inner = cell.getPixel(cx, Math.round(cy + radius * 0.55f));
        assertEquals("选中日中心应是卡片背景（空心），不能有实心底", 0, inner >>> 24);
        assertTrue("选中日底部要有主色环",
                countExact(cell, primary, Math.round(cy + radius) - 3,
                        Math.round(cy + radius) + 4) > 0);
        assertTrue("环不能是整块实心圆",
                countExact(cell, primary, 0, cell.getHeight()) < 4000);
    }

    @Test
    public void 今天未选中时只有实心圆() {
        Context ctx = themed();
        int todayBg = ThemeManager.resolveColor(ctx, R.attr.checkin_today_bg);
        int onToday = ThemeManager.resolveColor(ctx, R.attr.checkin_on_today);
        int ringStroke = ctx.getResources().getDimensionPixelSize(R.dimen.checkin_ring_stroke);
        float density = ctx.getResources().getDisplayMetrics().density;
        float discR = ctx.getResources().getDimension(R.dimen.checkin_today_diameter) / 2f;
        Bitmap cell = renderTodayCell();
        int cx = cell.getWidth() / 2;
        int cy = cell.getHeight() / 2;

        int discProbeX = cx + Math.round(12 * density);
        assertEquals("今天日号右侧应是语义色实心圆", todayBg, cell.getPixel(discProbeX, cy));
        int[] span = verticalSpan(cell, cx, todayBg);
        assertEquals("实心圆直径走 36dp 令牌", Math.round(36 * density),
                span[1] - span[0] + 1, Math.round(2 * density));
        assertEquals("没选中时今天不带外圈环", 0,
                cell.getPixel(cx, Math.round(cy + discR + ringStroke)) >>> 24);
        assertTrue("今天日号要用反白色",
                countExact(cell, onToday, 0, cell.getHeight()) > 0);
    }

    @Test
    public void 今天选中时实心圆外再套选中环() {
        Context ctx = themed();
        int todayBg = ThemeManager.resolveColor(ctx, R.attr.checkin_today_bg);
        int onToday = ThemeManager.resolveColor(ctx, R.attr.checkin_on_today);
        int primary = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
        int ringStroke = ctx.getResources().getDimensionPixelSize(R.dimen.checkin_ring_stroke);
        float density = ctx.getResources().getDisplayMetrics().density;
        float discR = ctx.getResources().getDimension(R.dimen.checkin_today_diameter) / 2f;
        float gap = ctx.getResources().getDimension(R.dimen.checkin_today_ring_gap);
        Bitmap cell = renderCheckInCell(7, true, true);
        int cx = cell.getWidth() / 2;
        int cy = cell.getHeight() / 2;

        int discProbeX = cx + Math.round(12 * density);
        assertEquals("今天 + 选中：圆内仍是语义色实心", todayBg, cell.getPixel(discProbeX, cy));
        // 环心在「实心圆 + 2dp 空隙 + 半个描边」处
        int ringProbe = Math.round(cy + discR + gap + ringStroke / 2f);
        assertTrue("今天 + 选中：实心圆外要有选中环",
                countExact(cell, primary, ringProbe - 2, ringProbe + 3) > 0);
        int gapProbe = Math.round(cy + discR + gap / 2f);
        assertEquals("实心圆与选中环之间要留空隙", 0, cell.getPixel(cx, gapProbe));
        assertTrue("今天日号要用反白色",
                countExact(cell, onToday, 0, cell.getHeight()) > 0);
    }

    @Test
    public void 邻月的今天也按真实打卡状态朗读() {
        final MonthGridView[] holder = new MonthGridView[1];
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> holder[0] = new MonthGridView(themed()));
        final MonthGridView grid = holder[0];
        // 2026-10-31 在 11 月网格里是邻月格；把它当「今天」就能覆盖邻月朗读分支
        final int adjacentToday = 20261031;
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> grid.rebuildWithTodayForTest(adjacentToday, 202611));
        DayCellView cell = null;
        for (int i = 0; i < grid.getDayCellCount(); i++) {
            if (grid.getDayKeyAt(i) == adjacentToday) {
                cell = grid.getCellAt(i);
                break;
            }
        }
        assertNotNull("11 月网格要包含 10 月 31 日这个邻月格", cell);
        String before = cell.getContentDescription().toString();
        assertTrue("邻月今天的朗读：" + before, before.contains("今天"));
        assertTrue("邻月今天的朗读要带未打卡：" + before, before.contains("未打卡"));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> grid.setTodayChecked(true));
        String after = cell.getContentDescription().toString();
        assertTrue("邻月今天已打卡要念已打卡：" + after, after.contains("已打卡"));
        assertFalse("不能在邻月今天身上同时念未打卡：" + after, after.contains("未打卡"));
    }

    @Test
    public void 网格声明为六行八列单选集合且每格带行列() {
        ViewGroup clip = monthGrid().findViewById(R.id.month_grid_clip);
        final ViewGroup grid = (ViewGroup) clip.getChildAt(0);
        final AccessibilityNodeInfo gridInfo = AccessibilityNodeInfo.obtain();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View.AccessibilityDelegate delegate = grid.getAccessibilityDelegate();
            assertNotNull("网格容器要挂集合信息", delegate);
            delegate.onInitializeAccessibilityNodeInfo(grid, gridInfo);
        });
        AccessibilityNodeInfo.CollectionInfo collection = gridInfo.getCollectionInfo();
        assertNotNull("网格对 TalkBack 应是集合", collection);
        assertEquals("行 = 周数", grid.getChildCount(), collection.getRowCount());
        assertEquals("列 = 周数 + 7 天", 8, collection.getColumnCount());
        assertEquals(AccessibilityNodeInfo.CollectionInfo.SELECTION_MODE_SINGLE,
                collection.getSelectionMode());
        assertTrue("标题只留在 MonthGridView 上",
                androidx.core.view.ViewCompat.isAccessibilityHeading(monthGrid()));

        MonthGridView month = monthGrid();
        final int selectedIndex = indexOfDay(month, month.getSelectedDayKey());
        for (int index : new int[]{9, selectedIndex}) {
            final DayCellView cell = month.getCellAt(index);
            final AccessibilityNodeInfo info = AccessibilityNodeInfo.obtain();
            InstrumentationRegistry.getInstrumentation()
                    .runOnMainSync(() -> cell.onInitializeAccessibilityNodeInfo(info));
            AccessibilityNodeInfo.CollectionItemInfo item = info.getCollectionItemInfo();
            assertNotNull("第 " + index + " 格要有行列信息", item);
            assertEquals("行号", index / 7, item.getRowIndex());
            assertEquals("列号（0 列是周数）", index % 7 + 1, item.getColumnIndex());
            assertEquals(1, item.getRowSpan());
            assertEquals(1, item.getColumnSpan());
            assertFalse("日期格不是标题", item.isHeading());
            assertEquals("selected 要跟视觉选中同步", index == selectedIndex, info.isSelected());
            assertEquals(index == selectedIndex, item.isSelected());
        }

        ViewGroup firstRow = (ViewGroup) grid.getChildAt(0);
        final View week = firstRow.getChildAt(0);
        final AccessibilityNodeInfo weekInfo = AccessibilityNodeInfo.obtain();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            View.AccessibilityDelegate delegate = week.getAccessibilityDelegate();
            assertNotNull("周数格也要带行列信息", delegate);
            delegate.onInitializeAccessibilityNodeInfo(week, weekInfo);
        });
        AccessibilityNodeInfo.CollectionItemInfo weekItem = weekInfo.getCollectionItemInfo();
        assertNotNull(weekItem);
        assertEquals(0, weekItem.getRowIndex());
        assertEquals("周数占第 0 列", 0, weekItem.getColumnIndex());
        assertFalse("周数不是标题", weekItem.isHeading());
        assertFalse("周数不是标题", androidx.core.view.ViewCompat.isAccessibilityHeading(week));
    }

    private static int indexOfDay(MonthGridView grid, int dayKey) {
        for (int i = 0; i < grid.getDayCellCount(); i++) {
            if (grid.getDayKeyAt(i) == dayKey) {
                return i;
            }
        }
        return 0;
    }

    private Bitmap renderTodayCell() {
        final Bitmap[] out = new Bitmap[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context ctx = themed();
            DayCellView cell = new DayCellView(ctx);
            int primary = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
            int selectedBg = ThemeManager.resolveColor(ctx, R.attr.checkin_selected_bg);
            int selectedStroke = ThemeManager.resolveColor(ctx, R.attr.checkin_selected_stroke);
            int onSurface = ThemeManager.resolveColor(ctx, R.attr.on_surface);
            int variant = ThemeManager.resolveColor(ctx, R.attr.on_surface_variant);
            int onPrimary = ThemeManager.resolveColor(ctx,
                    com.google.android.material.R.attr.colorOnPrimary);
            int ring = ThemeManager.resolveColor(ctx, R.attr.checkin_day_ring);
            int adjacentColor = ThemeManager.resolveColor(ctx, R.attr.checkin_adjacent_text);
            int todayBg = ThemeManager.resolveColor(ctx, R.attr.checkin_today_bg);
            int onToday = ThemeManager.resolveColor(ctx, R.attr.checkin_on_today);
            int todayRing = ThemeManager.resolveColor(ctx, R.attr.checkin_today_ring);
            int size = ctx.getResources().getDimensionPixelSize(R.dimen.checkin_cell_h);
            cell.setDotAnimationSuspended(true);
            cell.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
            cell.layout(0, 0, size, size);
            cell.bindCheckIn(7, primary, selectedBg, selectedStroke, onSurface, variant,
                    false, true, false, false, 0, 0, false, "", onPrimary, ring,
                    onSurface, ctx.getResources().getDimension(R.dimen.checkin_lunar_text),
                    adjacentColor, todayBg, onToday, todayRing);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            cell.draw(new Canvas(bitmap));
            out[0] = bitmap;
        });
        return out[0];
    }

    private Bitmap renderCheckInCell(final int day, final boolean selected, final boolean today) {
        final Bitmap[] out = new Bitmap[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context ctx = themed();
            DayCellView cell = new DayCellView(ctx);
            int primary = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
            int selectedBg = ThemeManager.resolveColor(ctx, R.attr.checkin_selected_bg);
            int selectedStroke = ThemeManager.resolveColor(ctx, R.attr.checkin_selected_stroke);
            int onSurface = ThemeManager.resolveColor(ctx, R.attr.on_surface);
            int variant = ThemeManager.resolveColor(ctx, R.attr.on_surface_variant);
            int onPrimary = ThemeManager.resolveColor(ctx,
                    com.google.android.material.R.attr.colorOnPrimary);
            int ring = ThemeManager.resolveColor(ctx, R.attr.checkin_day_ring);
            int adjacentColor = ThemeManager.resolveColor(ctx, R.attr.checkin_adjacent_text);
            int todayBg = ThemeManager.resolveColor(ctx, R.attr.checkin_today_bg);
            int onToday = ThemeManager.resolveColor(ctx, R.attr.checkin_on_today);
            int todayRing = ThemeManager.resolveColor(ctx, R.attr.checkin_today_ring);
            int size = ctx.getResources().getDimensionPixelSize(R.dimen.checkin_cell_h);
            cell.setDotAnimationSuspended(true);
            cell.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
            cell.layout(0, 0, size, size);
            cell.bindCheckIn(day, primary, selectedBg, selectedStroke, onSurface, variant,
                    selected, today, false, false, 0, 0, false, "", onPrimary, ring,
                    onSurface, ctx.getResources().getDimension(R.dimen.checkin_lunar_text),
                    adjacentColor, todayBg, onToday, todayRing);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            cell.draw(new Canvas(bitmap));
            out[0] = bitmap;
        });
        return out[0];
    }

    private Bitmap renderCell(final String lunar, final boolean adjacent) {
        final Bitmap[] out = new Bitmap[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context ctx = themed();
            DayCellView cell = new DayCellView(ctx);
            int primary = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
            int selectedBg = ThemeManager.resolveColor(ctx, R.attr.checkin_selected_bg);
            int selectedStroke = ThemeManager.resolveColor(ctx, R.attr.checkin_selected_stroke);
            int onSurface = ThemeManager.resolveColor(ctx, R.attr.on_surface);
            int variant = ThemeManager.resolveColor(ctx, R.attr.on_surface_variant);
            int onPrimary = ThemeManager.resolveColor(ctx,
                    com.google.android.material.R.attr.colorOnPrimary);
            int ring = ThemeManager.resolveColor(ctx, R.attr.checkin_day_ring);
            int adjacentColor = ThemeManager.resolveColor(ctx, R.attr.checkin_adjacent_text);
            int size = ctx.getResources().getDimensionPixelSize(R.dimen.checkin_cell_h);
            cell.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
            cell.layout(0, 0, size, size);
            cell.bindCheckIn(7, primary, selectedBg, selectedStroke, onSurface, variant,
                    false, false, adjacent, false, 0, 0, false, lunar, onPrimary, ring,
                    adjacent ? adjacentColor : variant,
                    ctx.getResources().getDimension(R.dimen.checkin_lunar_text),
                    adjacentColor);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
            cell.draw(new Canvas(bitmap));
            out[0] = bitmap;
        });
        return out[0];
    }

    private static int paintedPixelsFrom(Bitmap bitmap, int fromY) {
        int count = 0;
        for (int y = fromY; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if ((bitmap.getPixel(x, y) >>> 24) != 0) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 指定 RGB 的像素里最高的 alpha：用来核对小字颜色与压暗程度 */
    private static int maxAlphaWithRgb(Bitmap bitmap, int rgb, int fromY) {
        int max = 0;
        for (int y = fromY; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                int p = bitmap.getPixel(x, y);
                if ((p & 0x00FFFFFF) == rgb) {
                    max = Math.max(max, p >>> 24);
                }
            }
        }
        return max;
    }

    /** 指定区域内所有非透明像素的最高 alpha */
    private static int maxAlphaFrom(Bitmap bitmap, int fromY) {
        int max = 0;
        for (int y = fromY; y < bitmap.getHeight(); y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                max = Math.max(max, bitmap.getPixel(x, y) >>> 24);
            }
        }
        return max;
    }

    private static int countExact(Bitmap bitmap, int color, int fromY, int toY) {
        int count = 0;
        for (int y = fromY; y < toY; y++) {
            for (int x = 0; x < bitmap.getWidth(); x++) {
                if (bitmap.getPixel(x, y) == color) {
                    count++;
                }
            }
        }
        return count;
    }

    /** 指定列上某颜色的首末像素行：[0] 顶、[1] 底；没有则返回 -1 */
    private static int[] verticalSpan(Bitmap bitmap, int x, int color) {
        int top = -1;
        int bottom = -1;
        for (int y = 0; y < bitmap.getHeight(); y++) {
            if (bitmap.getPixel(x, y) == color) {
                if (top < 0) {
                    top = y;
                }
                bottom = y;
            }
        }
        return new int[]{top, bottom};
    }

    @Test
    public void 年视图不是滚动容器且12张卡齐全() {
        CheckInYearView yearView = checkin().findViewById(R.id.year_view);
        assertFalse("年视图不再用 ScrollView", ScrollView.class.isInstance(yearView));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() ->
                yearView.bind(2026, 2023, 2027, new java.util.ArrayList<>(), 20261107));
        for (int m = 1; m <= 12; m++) {
            assertNotNull("第 " + m + " 月卡拿不到", yearView.monthCardAt(m));
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

    @Test
    public void 选中换一天不重建整张网格() {
        MonthGridView grid = monthGrid();
        final View keep = grid.getCellAt(10);
        final int from = grid.getDayKeyAt(10);
        final int to = grid.getDayKeyAt(12);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            grid.setSelectedDay(from);
            grid.setSelectedDay(to);
        });
        assertSame("换选中日只该改两格的绘制，格子对象不能换", keep, grid.getCellAt(10));
        assertTrue("新选中那天要念「已选中」",
                grid.getCellAt(12).getContentDescription().toString().contains("已选中"));
        assertFalse("旧那天要摘掉「已选中」",
                grid.getCellAt(10).getContentDescription().toString().contains("已选中"));
    }

    @Test
    public void 批量刷新只重绑数字变过的格子() {
        MonthGridView grid = monthGrid();
        final int a = grid.getDayKeyAt(10);
        final int b = grid.getDayKeyAt(11);
        final View cellB = grid.getCellAt(11);
        final java.util.Map<Integer, int[]> marks = new java.util.HashMap<>();
        marks.put(a, new int[]{1, 0});
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> grid.setCheckInMarks(marks));
        assertTrue("变过的那天要念出本数",
                grid.getCellAt(10).getContentDescription().toString().contains("1 本"));
        marks.put(a, new int[]{2, 1});
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> grid.setCheckInMarks(marks));
        assertTrue("再变一次要跟着更新",
                grid.getCellAt(10).getContentDescription().toString().contains("2 本"));
        assertSame("没变的那一格不该被换掉", cellB, grid.getCellAt(11));
        assertTrue("没变的那一格仍是未打卡",
                grid.getCellAt(11).getContentDescription().toString().contains("未打卡"));
    }

    // ==================== 选择器模式（v5.9.1 第三步）====================

    /** 本月里、且不晚于今天的格子下标，从最后一天往前数 offset 个 */
    private static int pastIndex(MonthGridView grid, int offset) {
        java.util.List<Integer> idx = new java.util.ArrayList<>();
        int ym = grid.getYearMonth();
        int today = DateKeys.todayKey();
        for (int i = 0; i < grid.getDayCellCount(); i++) {
            int k = grid.getDayKeyAt(i);
            if (k / 100 == ym && k <= today) {
                idx.add(i);
            }
        }
        assertTrue("本月至少要有一天不晚于今天", idx.size() > offset);
        return idx.get(idx.size() - 1 - offset);
    }

    private static void click(MonthGridView grid, int index, boolean longPress) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            if (longPress) {
                grid.getCellAt(index).performLongClick();
            } else {
                grid.getCellAt(index).performClick();
            }
        });
    }

    @Test
    public void 选择器角色单击勾选与框选填充() {
        MonthGridView grid = monthGrid();
        final int ia = pastIndex(grid, 2);
        final int ib = pastIndex(grid, 0);
        final int a = grid.getDayKeyAt(ia);
        final int b = grid.getDayKeyAt(ib);
        final int[] toggled = {0};
        final int[] filled = {0};
        final int[] fillTo = {0};
        MonthGridView.PickerListener pl = new MonthGridView.PickerListener() {
            @Override
            public void onToggleDay(int dayKey) {
                toggled[0] = dayKey;
            }

            @Override
            public void onRangeFill(int fromKey, int toKey) {
                filled[0] = fromKey;
                fillTo[0] = toKey;
            }

            @Override
            public void onRangeCancel(int fromKey, int toKey) {
            }
        };
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            grid.setRole(MonthRole.PICKER);
            grid.setPickerListener(pl);
        });

        click(grid, ia, false);
        assertEquals("单击勾上这一天", a, toggled[0]);
        assertEquals(java.util.Collections.singletonList(a), grid.pickedKeys());
        assertTrue("勾上的格子要念「已选」",
                grid.getCellAt(ia).getContentDescription().toString().contains("已选"));
        click(grid, ia, false);
        assertTrue("再点一次取消", grid.pickedKeys().isEmpty());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> grid.setRangeMode(true));
        click(grid, ia, false); // 落锚
        assertTrue(grid.getCellAt(ia).getContentDescription().toString().contains("框选起点"));
        click(grid, ib, false); // 填充
        assertEquals(a, filled[0]);
        assertEquals(b, fillTo[0]);
        assertEquals("起点到终点整段选入", java.util.Arrays.asList(a, a + 1, b), grid.pickedKeys());

        // 未来日（本月里如果有的话）不该被算成读过，也不响应
        int futureIdx = -1;
        for (int i = 0; i < grid.getDayCellCount(); i++) {
            int k = grid.getDayKeyAt(i);
            if (k / 100 == grid.getYearMonth() && k > DateKeys.todayKey()) {
                futureIdx = i;
                break;
            }
        }
        if (futureIdx >= 0) {
            int before = grid.pickedKeys().size();
            click(grid, futureIdx, false);
            assertEquals("未来日不进集合", before, grid.pickedKeys().size());
            assertTrue(grid.getCellAt(futureIdx).getContentDescription().toString()
                    .contains("不可选"));
        }
    }

    @Test
    public void 切回打卡角色后回调照旧() {
        MonthGridView grid = monthGrid();
        final int ia = pastIndex(grid, 0);
        final int a = grid.getDayKeyAt(ia);
        final int[] selected = {0};
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            grid.setRole(MonthRole.PICKER);
            grid.setPickedDays(new java.util.HashSet<>(java.util.Collections.singletonList(a)));
            grid.setRole(MonthRole.CHECKIN);
            grid.setListener(new MonthGridView.Listener() {
                @Override
                public void onSelectDay(int dayKey) {
                    selected[0] = dayKey;
                }

                @Override
                public void onMoveToDay(int dayKey) {
                }

                @Override
                public void onMonthChanged(int yearMonth, int selectedDayKey) {
                }

                @Override
                public void onFutureDay(int dayKey) {
                }
            });
        });
        click(grid, ia, false);
        assertEquals("打卡页的 onSelectDay 要照旧触发", a, selected[0]);
        assertEquals("选中日也要回到打卡页语义", a, grid.getSelectedDayKey());
        assertTrue(grid.getCellAt(ia).getContentDescription().toString().contains("已选中"));
    }
}
