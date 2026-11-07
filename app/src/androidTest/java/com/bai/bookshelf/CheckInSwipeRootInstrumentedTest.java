package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.ui.checkin.CheckInSwipeRoot;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 纵向手势上移到根容器后的拦截规则：空白区可滑、列表方向让行、
 * 悬浮按钮与年视图让行、位移不足不触发。
 */
@RunWith(AndroidJUnit4.class)
public class CheckInSwipeRootInstrumentedTest {

    private static final int W = 1080;
    private static final int H = 2400;

    private Context ctx;
    private CheckInSwipeRoot root;
    private FakeList list;
    private View fab;
    private Boolean lastUp;
    private int calls;

    @Before
    public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            calls = 0;
            lastUp = null;
            root = new CheckInSwipeRoot(ctx);
            list = new FakeList(ctx);
            fab = new View(ctx);
            FrameLayout.LayoutParams listLp = new FrameLayout.LayoutParams(W, 800);
            listLp.topMargin = 1000;
            root.addView(list, listLp);
            FrameLayout.LayoutParams fabLp = new FrameLayout.LayoutParams(200, 150);
            fabLp.leftMargin = 800;
            fabLp.topMargin = 1900;
            root.addView(fab, fabLp);
            root.setListener(up -> {
                lastUp = up;
                calls++;
            });
            root.setScrollList(list);
            root.setExcludedView(fab);
            root.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, W, H);
        });
    }

    @Test
    public void 空白区上滑收周() {
        swipe(500, 400, 500, 160);
        assertSwipe(true);
    }

    @Test
    public void 空白区下滑展开() {
        swipe(500, 400, 500, 640);
        assertSwipe(false);
    }

    @Test
    public void 横向拖动不触发() {
        swipe(300, 400, 700, 430);
        assertNone();
    }

    @Test
    public void 位移不足不触发() {
        swipe(500, 400, 500, 420);
        assertNone();
    }

    @Test
    public void 列表上滑交给列表滚动() {
        list.canScrollBack = false;
        swipe(500, 1400, 500, 1160);
        assertNone();
    }

    @Test
    public void 列表在顶部下拉展开且不被列表吃掉() {
        list.canScrollBack = false;
        swipe(500, 1400, 500, 1640);
        assertSwipe(false);
    }

    @Test
    public void 列表未到顶部时下拉交给列表() {
        list.canScrollBack = true;
        swipe(500, 1400, 500, 1640);
        assertNone();
    }

    @Test
    public void 悬浮今按钮不参与手势() {
        swipe(900, 1950, 900, 1710);
        assertNone();
    }

    @Test
    public void 年视图可见时全部让行() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> root.setGuard(() -> true));
        swipe(500, 400, 500, 160);
        assertNone();
    }

    private void swipe(float x1, float y1, float x2, float y2) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            long t = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x1, y1, 0);
            MotionEvent move = MotionEvent.obtain(t, t + 30, MotionEvent.ACTION_MOVE, x2, y2, 0);
            MotionEvent up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x2, y2, 0);
            root.dispatchTouchEvent(down);
            root.dispatchTouchEvent(move);
            root.dispatchTouchEvent(up);
            down.recycle();
            move.recycle();
            up.recycle();
        });
    }

    private void assertSwipe(boolean up) {
        assertEquals("回调次数", 1, calls);
        assertEquals("滑动方向", up, lastUp);
    }

    private void assertNone() {
        assertEquals("不应触发纵向回调", 0, calls);
    }

    /** canScrollVertically(-1) 由测试直接控制，模拟列表在顶部/半途 */
    private static final class FakeList extends View {
        boolean canScrollBack;

        FakeList(Context ctx) {
            super(ctx);
            // 真实 RecyclerView 会消费 DOWN；让假列表也一样，测试才会走父容器的拦截路径
            setClickable(true);
        }

        @Override
        public boolean canScrollVertically(int direction) {
            return direction < 0 && canScrollBack;
        }
    }
}
