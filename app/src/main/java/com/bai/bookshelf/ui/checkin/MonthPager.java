package com.bai.bookshelf.ui.checkin;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.MotionEvent;
import android.view.VelocityTracker;

import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.bai.bookshelf.util.CheckInMonthGrid;
import com.bai.bookshelf.util.Motion;

/**
 * 横向翻月（P3 从 MonthGridView 搬出）：双层页面的平移、提交/回弹/取消，
 * 以及这一手的速度与拖动状态。
 *
 * <p>它不认识角色、数据与测量：那些都留在门面，通过 {@link Host} 要。判定与时长
 * 这两步纯计算在 {@link CheckInDragMath} 里，和纵向的 progress/settleToWeek 并列。
 *
 * <p>邻页只在这里存在——最多同时两页，提交后旧页由门面摘掉、{@code monthDelta} 归零、
 * 打卡点入场动画解冻。
 */
final class MonthPager {

    /** 门面提供的能力：建页、摘页、邻页转正、冻结点动画、能不能翻 */
    interface Host {
        /** 当前页（pager 只平移它的根视图） */
        MonthPage current();

        /** 目标月建一页并挂上裁剪容器（含置结构脏与 requestLayout） */
        MonthPage buildNeighbor(int targetYearMonth);

        /** 从裁剪容器上摘掉这一页 */
        void discardPage(MonthPage page);

        /** 邻页转正：门面换当前页、夹选中日、重绑、回调与播报 */
        void adopt(MonthPage page);

        /** 转场期间冻结/解冻格子的打卡点入场动画 */
        void setDotAnimationsSuspended(boolean suspended);

        /** 选择器角色自己带月份按钮，不参与翻月拖动 */
        boolean canPage();

        /** 当前月份（yyyymm），邻页按它偏移 */
        int yearMonth();

        /** 一页的宽度（px，门面保证 >=1） */
        float pageWidthPx();

        /** 提交后的月份回调与播报由门面收尾 */
        void onMonthSettled();
    }

    /** 横向提交阈值：拖过页宽的 25%，或抬手速度超过 500dp/s */
    private static final float COMMIT_FRACTION = 0.25f;
    private static final float COMMIT_VELOCITY_DP = 500f;
    /** 程序化切月（年视图返回、工具栏）一整段时长 */
    private static final long PAGING_MS = 180L;
    /** 拖动提交动画的时长下限；剩余距离越少越快，最短 90ms */
    private static final long SETTLE_MIN_MS = 90L;
    /** 未达阈值时的回弹时长 */
    private static final long RETURN_MS = 140L;
    /** 横向位移要明显强于纵向才算翻月，纵向归 CheckInSwipeRoot */
    private static final float PAGING_RATIO = 1.2f;

    private final Host host;
    private final float density;
    private final int touchSlopPx;
    private final FastOutSlowInInterpolator returnInterpolator = new FastOutSlowInInterpolator();

    /** 横向翻月拖动的当前页位移（px，正数 = 向右） */
    private float monthX;
    /** 这一手翻向哪一页：+1 下一个月，-1 上一个月，0 没在翻 */
    private int monthDelta;
    @Nullable
    private MonthPage neighbor;
    @Nullable
    private ValueAnimator monthAnim;
    /** 切月动画令牌：后一次切月取消前一次的回调，避免旧的结束事件解冻点动画 */
    private int monthAnimSeq;
    @Nullable
    private VelocityTracker tracker;
    private float downX;
    private float downY;
    private float monthVx;
    /** 这一手是否已经接管成翻月拖动 */
    private boolean dragging;

    MonthPager(Host host, float density, int touchSlopPx) {
        this.host = host;
        this.density = density;
        this.touchSlopPx = touchSlopPx;
    }

    // ==================== 对外 ====================

    boolean isDragging() {
        return dragging;
    }

    boolean hasNeighbor() {
        return neighbor != null;
    }

    @Nullable
    MonthPage neighborPage() {
        return neighbor;
    }

    float offsetPx() {
        return monthX;
    }

    float neighborTranslationXPx() {
        return neighbor == null ? 0f : neighbor.root.getTranslationX();
    }

    /** 有动画在跑或邻页还挂着，就算转场中 */
    private boolean inTransition() {
        return monthAnim != null || neighbor != null;
    }

    /** 程序化切月（工具栏、年视图返回）走和手指一样的双层转场 */
    void shiftMonth(int delta) {
        beginTransition(delta);
        animateTo(settleTarget(delta), PAGING_MS, delta);
    }

    /**
     * DOWN 建表、每帧入表。门面无条件在 {@code super.dispatchTouchEvent} <b>之前</b>调它：
     * 抬手帧要先入表，pager 在派发过程中算提交才取得到速度。
     */
    void track(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            if (tracker != null) {
                tracker.recycle();
            }
            tracker = VelocityTracker.obtain();
        }
        if (tracker != null) {
            tracker.addMovement(ev);
        }
    }

    /** 派发结束（super 已返回）再收表 */
    void finishTracking(MotionEvent ev) {
        int action = ev.getActionMasked();
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                && tracker != null) {
            tracker.recycle();
            tracker = null;
        }
    }

    boolean onInterceptTouchEvent(MotionEvent ev) {
        // 纵向收周/展开归 CheckInSwipeRoot；这里只拦横向翻月，一拦下就把格子的手势断掉
        int action = ev.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            downX = ev.getX();
            downY = ev.getY();
            dragging = false;
            return false;
        }
        if (action != MotionEvent.ACTION_MOVE || !host.canPage()) {
            return dragging;
        }
        float dx = ev.getX() - downX;
        return isHorizontalPaging(dx, ev.getY() - downY) && startDrag(dx);
    }

    boolean onTouchEvent(MotionEvent ev) {
        int action = ev.getActionMasked();
        switch (action) {
            case MotionEvent.ACTION_DOWN:
                downX = ev.getX();
                downY = ev.getY();
                dragging = false;
                return host.canPage();
            case MotionEvent.ACTION_MOVE: {
                float dx = ev.getX() - downX;
                if (!dragging && host.canPage() && isHorizontalPaging(dx, ev.getY() - downY)) {
                    startDrag(dx);
                }
                if (dragging) {
                    move(dx);
                }
                return dragging;
            }
            case MotionEvent.ACTION_UP:
                if (dragging) {
                    monthVx = xVelocity();
                    dragUp();
                }
                dragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (dragging) {
                    dragging = false;
                    returnToMonth();
                }
                return true;
            default:
                return dragging;
        }
    }

    /** 视图离开窗口：打断转场、拆掉邻页，别把动画回调留在已销毁的视图上 */
    void cancelTransition() {
        dragging = false;
        if (tracker != null) {
            tracker.recycle();
            tracker = null;
        }
        if (inTransition()) {
            cancelAnim();
            dropNeighbor();
            host.setDotAnimationsSuspended(false);
        }
    }

    /** 视图没挂窗口时 Choreographer 可能不给帧，直接把提交/回弹动画走完 */
    void finishAnimation() {
        if (monthAnim != null) {
            monthAnim.end();
        }
    }

    // ==================== 内部 ====================

    /** 横向要明显强于纵向才算翻月；位移过 slop */
    private boolean isHorizontalPaging(float dx, float dy) {
        return Math.abs(dx) > touchSlopPx && Math.abs(dx) > Math.abs(dy) * PAGING_RATIO;
    }

    /** 手指接管：建好邻页、冻结点动画，位移立刻跟手 */
    private boolean startDrag(float dx) {
        dragging = true;
        monthDelta = dx < 0 ? 1 : -1;
        prepareNeighbor(CheckInMonthGrid.shiftMonth(host.yearMonth(), monthDelta));
        host.setDotAnimationsSuspended(true);
        move(dx);
        return true;
    }

    /** 1:1 跟手：夹在一页之内，中途换向就换邻页 */
    private void move(float dx) {
        int delta = dx < 0 ? 1 : -1;
        if (delta != monthDelta) {
            monthDelta = delta;
            prepareNeighbor(CheckInMonthGrid.shiftMonth(host.yearMonth(), delta));
        }
        float width = host.pageWidthPx();
        translate(Math.max(-width, Math.min(width, dx)));
    }

    /** 松手：过 25% 页宽或 500dp/s 就提交，剩下的距离越短补完越快；否则回弹 */
    private void dragUp() {
        float width = host.pageWidthPx();
        int delta = CheckInDragMath.commitDelta(monthX, monthVx, width, density,
                COMMIT_FRACTION, COMMIT_VELOCITY_DP);
        if (delta == 0) {
            returnToMonth();
            return;
        }
        if (delta != monthDelta || neighbor == null) {
            beginTransition(delta);
        }
        float to = settleTarget(delta);
        long ms = CheckInDragMath.settleMs(monthX, to, width, PAGING_MS, SETTLE_MIN_MS);
        animateTo(to, ms, delta);
    }

    /** 邻页就位：目标月没变就复用，变了（含手指往回拨）重建；最多同时两页 */
    private void prepareNeighbor(int target) {
        if (neighbor != null && neighbor.yearMonth == target) {
            return;
        }
        if (neighbor != null) {
            host.discardPage(neighbor);
        }
        neighbor = host.buildNeighbor(target);
        translate(monthX);
    }

    /** 建邻页、定方向、冻结点动画——手指提交和程序化切月都从这里起步 */
    private void beginTransition(int delta) {
        cancelAnim();
        monthDelta = delta;
        prepareNeighbor(CheckInMonthGrid.shiftMonth(host.yearMonth(), delta));
        host.setDotAnimationsSuspended(true);
    }

    /** 两页一起平移；邻页停在当前页的另一侧，提交时正好归位 */
    private void translate(float x) {
        monthX = x;
        host.current().root.setTranslationX(x);
        if (neighbor != null) {
            neighbor.root.setTranslationX(monthDelta * host.pageWidthPx() + x);
        }
    }

    /** 提交方向上当前页的落点：邻页此时平移量正好是 0 */
    private float settleTarget(int delta) {
        return -delta * host.pageWidthPx();
    }

    private void animateTo(float to, long durationMs, final int commitDelta) {
        final int token = ++monthAnimSeq;
        ValueAnimator anim = ValueAnimator.ofFloat(monthX, to);
        anim.addUpdateListener(a -> translate((float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (token == monthAnimSeq) {
                    monthAnim = null;
                    commit(commitDelta);
                }
            }
        });
        monthAnim = anim;
        anim.setDuration(durationMs);
        anim.setInterpolator(Motion.DECEL);
        anim.start();
    }

    /** 动画到位：邻页转正、旧页摘掉、选中日夹进新月份由门面做 */
    private void commit(int delta) {
        MonthPage page = neighbor;
        if (page == null) {
            return;
        }
        neighbor = null;
        monthDelta = 0;
        host.adopt(page);
        translate(0f);
        host.onMonthSettled();
        host.setDotAnimationsSuspended(false);
    }

    /** 未过阈值：回到 0 后拆掉邻页，点动画解冻 */
    private void returnToMonth() {
        final int token = ++monthAnimSeq;
        ValueAnimator anim = ValueAnimator.ofFloat(monthX, 0f);
        anim.addUpdateListener(a -> translate((float) a.getAnimatedValue()));
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (token != monthAnimSeq) {
                    return;
                }
                monthAnim = null;
                monthDelta = 0;
                dropNeighbor();
                host.setDotAnimationsSuspended(false);
            }
        });
        monthAnim = anim;
        anim.setDuration(RETURN_MS);
        anim.setInterpolator(returnInterpolator);
        anim.start();
    }

    private void dropNeighbor() {
        MonthPage old = neighbor;
        neighbor = null;
        if (old != null) {
            host.discardPage(old);
        }
        translate(0f);
    }

    private void cancelAnim() {
        if (monthAnim != null) {
            monthAnimSeq++; // 这次转场的结束回调作废：打断时保留当前位移
            ValueAnimator anim = monthAnim;
            monthAnim = null;
            anim.cancel();
        }
    }

    private float xVelocity() {
        if (tracker == null) {
            return 0f;
        }
        tracker.computeCurrentVelocity(1000);
        return tracker.getXVelocity();
    }
}
