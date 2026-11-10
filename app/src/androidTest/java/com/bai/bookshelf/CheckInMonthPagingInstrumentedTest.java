package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.ui.checkin.MonthGridView;
import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.ThemeManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;

/**
 * v5.9.5 第 3 项：横向双层实时翻月。
 *
 * <p>只看三件事：拖动中位移 1:1 跟手且邻页已在、过了阈值才提交、
 * {@code onMonthChanged} 一次拖动最多回调一次（回弹那次一个都不回）。
 */
@RunWith(AndroidJUnit4.class)
public class CheckInMonthPagingInstrumentedTest {

    private static final int W = 1080;
    private static final float DOWN_Y = 300f;

    private Context ctx;
    private MonthGridView grid;
    private int monthCalls;
    private int selectCalls;
    private int lastYearMonth;
    private int slop;

    @Before
    public void setUp() {
        Context raw = ApplicationProvider.getApplicationContext();
        ctx = new android.view.ContextThemeWrapper(raw,
                ThemeManager.styleRes(ThemeManager.current(raw)));
        slop = ViewConfiguration.get(raw).getScaledTouchSlop();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            monthCalls = 0;
            selectCalls = 0;
            lastYearMonth = 0;
            grid = new MonthGridView(ctx);
            grid.setListener(new MonthGridView.Listener() {
                @Override
                public void onSelectDay(int dayKey) {
                    selectCalls++;
                }

                @Override
                public void onMoveToDay(int dayKey) {
                }

                @Override
                public void onMonthChanged(int yearMonth, int selectedDayKey) {
                    monthCalls++;
                    lastYearMonth = yearMonth;
                }

                @Override
                public void onFutureDay(int dayKey) {
                }
            });
            grid.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            grid.layout(0, 0, W, grid.getMeasuredHeight());
        });
    }

    @Test
    public void 慢拖过三成页宽提交到下一月() {
        int before = month();
        drag(-0.3f * W, 400L);
        finishAnim();
        assertEquals("提交后月份 +1", CheckInMonthGrid.shiftMonth(before, 1), month());
        assertEquals("只在提交时回调一次", 1, monthCalls);
        assertEquals("回调带上新的月份", month(), lastYearMonth);
        assertFalse("邻页已摘掉", hasNeighbor());
        assertEquals("翻月不误点日期格", 0, selectCalls);
    }

    @Test
    public void 慢拖一成页宽只回弹不提交() {
        int before = month();
        drag(-0.1f * W, 600L);
        finishAnim();
        assertEquals("没提交就不换月", before, month());
        assertEquals("回弹不回调 onMonthChanged", 0, monthCalls);
        assertFalse("邻页已摘掉", hasNeighbor());
        assertEquals("位移归零", 0f, pagerOffset(), 0.01f);
    }

    @Test
    public void 拖动中位移一比一且邻页在场() {
        dragTo(-0.2f * W);
        assertEquals("当前页跟手", -0.2f * W, pagerOffset(), 1f);
        assertTrue("邻页已经挂上", hasNeighbor());
        assertEquals("邻页停在另一侧", W - 0.2f * W, neighborTranslationX(), 1f);
        assertEquals("拖而未抬手：还没提交", 0, monthCalls);
        cancel();
        finishAnim();
    }

    @Test
    public void 反向慢拖提交到上一月() {
        int before = month();
        drag(0.3f * W, 400L);
        finishAnim();
        assertEquals("提交后月份 -1", CheckInMonthGrid.shiftMonth(before, -1), month());
        assertEquals(1, monthCalls);
    }

    @Test
    public void 快甩不到三成也提交() {
        int before = month();
        // 120px / 30ms = 4000px/s（约 1450dp/s），远超 500dp/s；位移这边没到 25%
        drag(-120f, 30L);
        finishAnim();
        assertEquals("速度够了就换月", CheckInMonthGrid.shiftMonth(before, 1), month());
        assertEquals(1, monthCalls);
    }

    @Test
    public void 没过slop不算翻月() {
        dragTo(-slop / 2f);
        assertFalse("没有邻页", hasNeighbor());
        assertEquals("没接管手势", 0f, pagerOffset(), 0.01f);
        cancel();
    }

    /** §六.3：邻页只准存在一张，中途换向也不能叠出第三页 */
    @Test
    public void 拖动与换向全程最多两页() {
        dragTo(-0.2f * W);
        assertEquals("当前页 + 邻页", 2, pageCount());
        moveTo(0.2f * W); // 往回拨过中线：换成上一月的邻页
        assertEquals("换向不叠第三页", 2, pageCount());
        assertTrue("邻页还在", hasNeighbor());
        cancel();
        finishAnim();
        assertEquals("回弹后只剩当前页", 1, pageCount());
    }

    @Test
    public void 提交后旧页摘掉只剩一页() {
        dragTo(-0.3f * W);
        assertEquals(2, pageCount());
        up(-0.3f * W);
        finishAnim();
        assertEquals("旧页已从裁剪容器移除", 1, pageCount());
        assertFalse("邻页已转正为当前页", hasNeighbor());
    }

    // ==================== 手势与反射脚手架 ====================

    /** DOWN → MOVE（指定时长，用来控速）→ UP */
    private void drag(float dx, long moveMillis) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            long t = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN,
                    W / 2f, DOWN_Y, 0);
            MotionEvent move = MotionEvent.obtain(t, t + moveMillis, MotionEvent.ACTION_MOVE,
                    W / 2f + dx, DOWN_Y, 0);
            MotionEvent up = MotionEvent.obtain(t, t + moveMillis + 1, MotionEvent.ACTION_UP,
                    W / 2f + dx, DOWN_Y, 0);
            grid.dispatchTouchEvent(down);
            grid.dispatchTouchEvent(move);
            grid.dispatchTouchEvent(up);
            down.recycle();
            move.recycle();
            up.recycle();
        });
    }

    /** 只拖到一半不抬手，看逐帧的跟手量 */
    private void dragTo(float dx) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            long t = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN,
                    W / 2f, DOWN_Y, 0);
            MotionEvent move = MotionEvent.obtain(t, t + 200L, MotionEvent.ACTION_MOVE,
                    W / 2f + dx, DOWN_Y, 0);
            grid.dispatchTouchEvent(down);
            grid.dispatchTouchEvent(move);
            down.recycle();
            move.recycle();
        });
    }

    /** 接着 dragTo 那一帧再 MOVE 一次（事件时间往后走，测速表才不乱） */
    private void moveTo(float dx) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            long t = SystemClock.uptimeMillis();
            MotionEvent ev = MotionEvent.obtain(t, t + 400L, MotionEvent.ACTION_MOVE,
                    W / 2f + dx, DOWN_Y, 0);
            grid.dispatchTouchEvent(ev);
            ev.recycle();
        });
    }

    /** 接着 dragTo 那一帧抬手 */
    private void up(float dx) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            long t = SystemClock.uptimeMillis();
            MotionEvent ev = MotionEvent.obtain(t, t + 401L, MotionEvent.ACTION_UP,
                    W / 2f + dx, DOWN_Y, 0);
            grid.dispatchTouchEvent(ev);
            ev.recycle();
        });
    }

    private void cancel() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            long t = SystemClock.uptimeMillis();
            MotionEvent ev = MotionEvent.obtain(t, t, MotionEvent.ACTION_CANCEL,
                    W / 2f - 0.2f * W, DOWN_Y, 0);
            grid.dispatchTouchEvent(ev);
            ev.recycle();
        });
    }

    /** 裁剪容器里挂着几页：不引新钩子，直接量真实视图层级 */
    private int pageCount() {
        final int[] out = new int[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            ViewGroup clip = grid.findViewById(R.id.month_grid_clip);
            out[0] = clip.getChildCount();
        });
        return out[0];
    }

    /** Choreographer 可能一帧都不给：直接让提交/回弹动画走完，阈值与回调照原路跑 */
    private void finishAnim() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(grid::finishPagerAnimation);
    }

    private int month() {
        final int[] out = new int[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> out[0] = grid.getYearMonth());
        return out[0];
    }

    private float pagerOffset() {
        final float[] out = new float[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> out[0] = grid.pagerOffsetPx());
        return out[0];
    }

    private boolean hasNeighbor() {
        final boolean[] out = new boolean[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> out[0] = grid.hasNeighborPage());
        return out[0];
    }

    private float neighborTranslationX() {
        final float[] out = new float[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> out[0] = grid.neighborPageTranslationXPx());
        return out[0];
    }
}
