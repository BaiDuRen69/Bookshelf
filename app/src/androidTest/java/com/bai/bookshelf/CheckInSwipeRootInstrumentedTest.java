package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.ui.checkin.CheckInSwipeRoot;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

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
    // 拖动逐帧回调的记录（v5.9.5 第 3 项）
    private final List<Float> dragDy = new ArrayList<>();
    private int dragBegins;
    private int dragEnds;
    private int dragCancels;
    private boolean dragBeginUp;
    private float dragEndVy;

    @Before
    public void setUp() {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            calls = 0;
            lastUp = null;
            dragDy.clear();
            dragBegins = 0;
            dragEnds = 0;
            dragCancels = 0;
            dragBeginUp = false;
            dragEndVy = 0f;
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
        // 距离跟着系统的 touchSlop 走：阈值改小改大都该是「不过 slop 不算手势」
        int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        swipe(500, 400, 500, 400 - slop / 2);
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

    // ==================== v5.9.5 第 3 项：拖动逐帧回调 ====================

    @Test
    public void 拖动逐帧给位移且首帧已扣起始slop() {
        final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        installDragListener();
        // 4 个 slop 的位移一步到位：扣掉起始 slop 后应当正好是 3 个
        swipe(500, 400, 500, 400 - 4 * slop);
        assertEquals("只开始一次", 1, dragBegins);
        assertTrue("上滑标记", dragBeginUp);
        assertEquals("首帧位移", -3f * slop, dragDy.get(0), 1f);
        assertEquals("抬手一次", 1, dragEnds);
        assertTrue("上滑速度为负", dragEndVy < 0f);
        assertEquals("有拖动回调时不再走抬手旧路径", 0, calls);
    }

    @Test
    public void 连续位移单调且越拖越大() {
        final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        installDragListener();
        dragSteps(500, 400, 4 * slop);
        assertTrue("至少 3 帧", dragDy.size() >= 3);
        for (int i = 1; i < dragDy.size(); i++) {
            assertTrue("位移逐帧更负", dragDy.get(i) < dragDy.get(i - 1));
        }
        assertEquals("最后一帧等于总位移扣 slop", -3f * slop,
                dragDy.get(dragDy.size() - 1), 1f);
    }

    @Test
    public void 手势被取消时通知取消不通知抬手() {
        final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        installDragListener();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            long t = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 500, 400, 0);
            MotionEvent move = MotionEvent.obtain(t, t + 30, MotionEvent.ACTION_MOVE,
                    500, 400 - 4 * slop, 0);
            MotionEvent cancel = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_CANCEL,
                    500, 400 - 4 * slop, 0);
            root.dispatchTouchEvent(down);
            root.dispatchTouchEvent(move);
            root.dispatchTouchEvent(cancel);
            down.recycle();
            move.recycle();
            cancel.recycle();
        });
        assertEquals("取消一次", 1, dragCancels);
        assertEquals("取消后没有抬手回调", 0, dragEnds);
    }

    @Test
    public void 列表让行时拖动回调也不接管() {
        final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        installDragListener();
        list.canScrollBack = false;
        swipe(500, 1400, 500, 1400 - 4 * slop); // 列表已在顶部：上滑归列表滚动
        assertEquals("不该开始拖动", 0, dragBegins);
    }

    /** 挂上逐帧回调：进度由拖动接管后，抬手只报速度 */
    private void installDragListener() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> root.setDragListener(
                new CheckInSwipeRoot.DragListener() {
                    @Override
                    public void onDragBegin(boolean up) {
                        dragBegins++;
                        dragBeginUp = up;
                    }

                    @Override
                    public void onDrag(float dyPx) {
                        dragDy.add(dyPx);
                    }

                    @Override
                    public void onDragEnd(float velocityY) {
                        dragEnds++;
                        dragEndVy = velocityY;
                    }

                    @Override
                    public void onDragCancel() {
                        dragCancels++;
                    }
                }));
    }

    /** 同一只手拆成多帧 MOVE，验证逐帧回调 */
    private void dragSteps(float x, float y1, int total) {
        final int slop = ViewConfiguration.get(ctx).getScaledTouchSlop();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            long t = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y1, 0);
            root.dispatchTouchEvent(down);
            down.recycle();
            int[] steps = {slop + 1, 2 * slop, 3 * slop, total};
            for (int i = 0; i < steps.length; i++) {
                MotionEvent move = MotionEvent.obtain(t, t + 30L * (i + 1),
                        MotionEvent.ACTION_MOVE, x, y1 - steps[i], 0);
                root.dispatchTouchEvent(move);
                move.recycle();
            }
            MotionEvent up = MotionEvent.obtain(t, t + 150, MotionEvent.ACTION_UP,
                    x, y1 - total, 0);
            root.dispatchTouchEvent(up);
            up.recycle();
        });
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
