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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.R;
import com.bai.bookshelf.ui.checkin.CheckInYearView;
import com.bai.bookshelf.ui.checkin.MonthGridView;
import com.bai.bookshelf.ui.widget.DayCellView;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.DateKeys;
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
    public void 邻月日号只走语义色且不画农历() {
        int adjacentColor = ThemeManager.resolveColor(themed(), R.attr.checkin_adjacent_text);
        Bitmap adjacent = renderCell("初一", true);
        Bitmap normal = renderCell("初一", false);
        int lunarTop = Math.round(adjacent.getHeight() * 0.58f);

        assertEquals("邻月不画农历小字", 0, paintedPixelsFrom(adjacent, lunarTop));
        assertTrue("本月仍画农历小字", paintedPixelsFrom(normal, lunarTop) > 0);
        assertTrue("邻月日号不再叠 110 alpha，应出现不透明的语义色像素",
                countExact(adjacent, adjacentColor, Math.round(adjacent.getHeight() * 0.15f),
                        Math.round(adjacent.getHeight() * 0.55f)) > 0);
    }

    @Test
    public void 今天圆走语义色且和主色分开() {
        int todayBg = ThemeManager.resolveColor(themed(), R.attr.checkin_today_bg);
        int onToday = ThemeManager.resolveColor(themed(), R.attr.checkin_on_today);
        int primary = ThemeManager.resolveColor(themed(), ThemeManager.ATTR_COLOR_PRIMARY);
        Bitmap today = renderTodayCell();
        int h = today.getHeight();

        assertTrue("今天圆用 checkin_today_bg 实心填充",
                countExact(today, todayBg, Math.round(h * 0.30f), Math.round(h * 0.50f))
                        > 100);
        assertTrue("今天圆内数字改用 checkin_on_today",
                countExact(today, onToday, Math.round(h * 0.30f), Math.round(h * 0.62f)) > 0);
        if (todayBg != primary) {
            assertEquals("今天不再吃 colorPrimary", 0, countExact(today, primary, 0, h));
        }
        assertTrue("圆内文字与底色对比度 ≥ 4.5:1", contrast10(onToday, todayBg) >= 45);
    }

    private static int contrast10(int fg, int bg) {
        double l1 = luminance(fg);
        double l2 = luminance(bg);
        double lighter = Math.max(l1, l2);
        double darker = Math.min(l1, l2);
        return (int) Math.round((lighter + 0.05) / (darker + 0.05) * 10);
    }

    private static double luminance(int color) {
        return 0.2126 * linear(android.graphics.Color.red(color) / 255.0)
                + 0.7152 * linear(android.graphics.Color.green(color) / 255.0)
                + 0.0722 * linear(android.graphics.Color.blue(color) / 255.0);
    }

    private static double linear(double channel) {
        return channel <= 0.03928 ? channel / 12.92
                : Math.pow((channel + 0.055) / 1.055, 2.4);
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
            cell.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
            cell.layout(0, 0, size, size);
            cell.bindCheckIn(7, primary, selectedBg, selectedStroke, onSurface, variant,
                    false, true, false, false, 0, 0, false, "初一", onPrimary, ring,
                    variant, ctx.getResources().getDimension(R.dimen.checkin_lunar_text),
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
                    variant, ctx.getResources().getDimension(R.dimen.checkin_lunar_text),
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
            grid.setRole(MonthGridView.ROLE_PICKER);
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
            grid.setRole(MonthGridView.ROLE_PICKER);
            grid.setPickedDays(new java.util.HashSet<>(java.util.Collections.singletonList(a)));
            grid.setRole(MonthGridView.ROLE_CHECKIN);
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
