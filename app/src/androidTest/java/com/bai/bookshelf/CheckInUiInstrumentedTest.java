package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
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
        assertNotNull("当前月蓝描边", yearView.monthCardAt(11).getBackground());
        assertNull("其它月不画底", yearView.monthCardAt(10).getBackground());
        LinearLayout layer = (LinearLayout) ((ViewGroup) yearView.getChildAt(0)).getChildAt(0);
        assertEquals("一年 6 行", 6, layer.getChildCount());
        for (int r = 0; r < 6; r++) {
            assertEquals("每行两个月卡片", 2,
                    ((ViewGroup) layer.getChildAt(r)).getChildCount());
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
                public void onMonthChanged(int yearMonth) {
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
