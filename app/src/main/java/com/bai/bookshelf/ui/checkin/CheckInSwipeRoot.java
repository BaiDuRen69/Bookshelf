package com.bai.bookshelf.ui.checkin;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.Nullable;

/**
 * 打卡页根容器：纵向收周 / 展开手势的唯一 owner。
 *
 * <p>以前纵向手势只写在 MonthGridView 里，只有手指落在日历 6 行上才滑得动；
 * 上移到整页后，页头、图例、分割线、小结、把手、下方空白都能收周，
 * 同时让列表、横向切月、悬浮「今」各走各的。
 */
public class CheckInSwipeRoot extends FrameLayout {

    public interface Listener {
        /** true = 上滑（收周），false = 下滑（展开） */
        void onVerticalSwipe(boolean up);
    }

    /** 年视图可见 / 切换动画中不接管手势 */
    public interface Guard {
        boolean blocked();
    }

    private static final float SLOP_DP = 16f;
    private static final float DIRECTION_RATIO = 1.2f;
    private static final float FLING_DP_PER_SEC = 300f;

    private final float density;
    @Nullable
    private Listener listener;
    @Nullable
    private Guard guard;
    /** 书目列表：上滑交给它滚动，只有已经在顶部时才接管下拉 */
    @Nullable
    private View scrollList;
    /** 逐日明细列表：完全排除，退出明细走顶部把手 */
    @Nullable
    private View excludedList;
    /** 悬浮「今」：点按属于按钮，不参与纵向手势 */
    @Nullable
    private View excludedView;
    @Nullable
    private VelocityTracker tracker;
    private float downX;
    private float downY;
    private boolean tracking;
    private boolean dragging;

    public CheckInSwipeRoot(Context ctx) {
        this(ctx, null);
    }

    public CheckInSwipeRoot(Context ctx, @Nullable AttributeSet attrs) {
        super(ctx, attrs);
        density = ctx.getResources().getDisplayMetrics().density;
    }

    public void setListener(@Nullable Listener l) {
        listener = l;
    }

    public void setGuard(@Nullable Guard g) {
        guard = g;
    }

    public void setScrollList(@Nullable View v) {
        scrollList = v;
    }

    public void setExcludedList(@Nullable View v) {
        excludedList = v;
    }

    public void setExcludedView(@Nullable View v) {
        excludedView = v;
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (tracker == null) {
            tracker = VelocityTracker.obtain();
        }
        tracker.addMovement(ev);
        boolean handled = super.dispatchTouchEvent(ev);
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            tracker.recycle();
            tracker = null;
        }
        return handled;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                begin(ev);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (!tracking || dragging) {
                    return dragging;
                }
                float dy = ev.getY() - downY;
                float dx = ev.getX() - downX;
                if (!vertical(ev)) {
                    return false; // 横向留给 MonthGridView 的切月手势
                }
                if (yieldToList(dy)) {
                    tracking = false; // 这一手归列表，后面不再抢
                    return false;
                }
                dragging = true;
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                // 空白区域没有子 View 消费 DOWN：自己接住，后续才能在 onTouchEvent 里判方向
                return tracking;
            case MotionEvent.ACTION_MOVE:
                if (tracking && !dragging && vertical(ev) && !yieldToList(ev.getY() - downY)) {
                    dragging = true;
                }
                return tracking;
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    fire(ev);
                }
                boolean handled = tracking;
                reset();
                return handled;
            case MotionEvent.ACTION_CANCEL:
                reset();
                return true;
            default:
                return tracking;
        }
    }

    private boolean vertical(MotionEvent ev) {
        float dy = ev.getY() - downY;
        float dx = ev.getX() - downX;
        return Math.abs(dy) > slop() && Math.abs(dy) > Math.abs(dx) * DIRECTION_RATIO;
    }

    /** 记录起点；年视图、逐日列表、悬浮按钮上的触摸不参与 */
    private void begin(MotionEvent ev) {
        dragging = false;
        tracking = false;
        downX = ev.getX();
        downY = ev.getY();
        if (guard != null && guard.blocked()) {
            return;
        }
        if (isInside(excludedView, ev.getX(), ev.getY())
                || isInside(excludedList, ev.getX(), ev.getY())) {
            return;
        }
        tracking = true;
    }

    /** 手指从书目列表开始：上滑交给列表滚动；只有列表已在顶部才接管下拉 */
    private boolean yieldToList(float dy) {
        if (scrollList == null || !isInside(scrollList, downX, downY)) {
            return false;
        }
        if (dy < 0) {
            return true;
        }
        return scrollList.canScrollVertically(-1);
    }

    private void fire(MotionEvent ev) {
        float dy = ev.getY() - downY;
        boolean fast = false;
        if (tracker != null) {
            tracker.computeCurrentVelocity(1000);
            fast = Math.abs(tracker.getYVelocity()) > FLING_DP_PER_SEC * density;
        }
        if (listener != null && (Math.abs(dy) > slop() || fast)) {
            listener.onVerticalSwipe(dy < 0);
        }
    }

    private void reset() {
        tracking = false;
        dragging = false;
    }

    private float slop() {
        return SLOP_DP * density;
    }

    private boolean isInside(@Nullable View view, float x, float y) {
        if (view == null || view.getVisibility() != VISIBLE) {
            return false;
        }
        // 沿父链累加 left/top，不依赖 attach 与 window 坐标：instrumentation 里也能直接测
        float lx = 0;
        float ly = 0;
        View v = view;
        while (v != null && v != this) {
            lx += v.getLeft();
            ly += v.getTop();
            if (!(v.getParent() instanceof View)) {
                break;
            }
            v = (View) v.getParent();
        }
        return x >= lx && x < lx + view.getWidth()
                && y >= ly && y < ly + view.getHeight();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
        reset();
    }
}
