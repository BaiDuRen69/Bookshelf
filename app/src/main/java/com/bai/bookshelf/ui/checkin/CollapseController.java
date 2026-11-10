package com.bai.bookshelf.ui.checkin;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;

import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

/**
 * 纵向折叠的状态机（P4 从 MonthGridView 搬出）：进度 p、目标态 weekMode、
 * 手指接管标记与收尾动画。
 *
 * <p>它不认识高度怎么插值、页面怎么平移——那些留在门面，通过 {@link Host} 回来；
 * 也不认得模式（MONTH/WEEK/DETAIL），那是 fragment 的事。
 * 收尾时长按剩余距离缩放这一步纯计算在 {@link CheckInDragMath#collapseMs}。
 */
final class CollapseController {

    interface Host {
        /**
         * 进度落定：门面按 p 重算自身高度（变了才 requestLayout）、把选中周平移到
         * 表头下，再把进度转给页头。
         */
        void onCollapseProgress(float progress, boolean live);
    }

    /** 没有指定时长时的整段收起/展开 */
    private static final long DEFAULT_MS = 220L;

    private final Host host;
    private final FastOutSlowInInterpolator interpolator = new FastOutSlowInInterpolator();
    /** 目标态：true = 只显示选中那一周 */
    private boolean weekMode;
    /** 折叠进度 0..1；手势/动画只改它，测量与位移都从这里派生 */
    private float progress;
    @Nullable
    private ValueAnimator anim;
    /** 手指正在拖动：动画让位，进度只由手指提供 */
    private boolean dragging;

    CollapseController(Host host) {
        this.host = host;
    }

    boolean weekMode() {
        return weekMode;
    }

    /** 当前折叠进度：拖动起点要从这里取，不能假设从 0 开始 */
    float progress() {
        return progress;
    }

    boolean isDragging() {
        return dragging;
    }

    /**
     * 整月 ↔ 单周。{@code durationMs <= 0} 用默认 {@value #DEFAULT_MS}ms；
     * 手指还在拖（{@link #setDragging(boolean)} 为 true）时动画一律不接管，避免和手指抢进度。
     */
    void setWeekMode(boolean week, boolean animate, long durationMs) {
        weekMode = week;
        float target = week ? 1f : 0f;
        cancel();
        if (!animate || dragging || Math.abs(progress - target) < 0.001f) {
            setProgress(target, false);
            return;
        }
        ValueAnimator next = ValueAnimator.ofFloat(progress, target);
        next.setDuration(durationMs > 0 ? durationMs : DEFAULT_MS);
        next.setInterpolator(interpolator);
        next.addUpdateListener(a -> setProgress((float) a.getAnimatedValue(), false));
        next.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (anim != animation) {
                    return; // 已被新的手势打断
                }
                anim = null;
                setProgress(weekMode ? 1f : 0f, false);
            }
        });
        anim = next;
        next.start();
    }

    /** 手指接管期间动画让位；置 true 顺手打断正在跑的收起动画 */
    void setDragging(boolean dragging) {
        this.dragging = dragging;
        if (dragging) {
            cancel();
        }
    }

    void setProgress(float p, boolean live) {
        progress = Math.max(0f, Math.min(1f, p));
        host.onCollapseProgress(progress, live);
    }

    void cancel() {
        ValueAnimator a = anim;
        anim = null;
        if (a != null) {
            a.cancel();
        }
    }
}
