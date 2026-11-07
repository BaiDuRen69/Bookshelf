package com.bai.bookshelf.ui.checkin;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.transition.AutoTransition;
import androidx.transition.ChangeTransform;
import androidx.transition.Transition;
import androidx.transition.TransitionManager;

/**
 * 打卡页月视图 ↔ 年视图切换（类似澎湃 OS 日历的缩放转场）。
 *
 * <p>TransitionManager + AutoTransition 管可见性淡入淡出与布局稳定；缩放由
 * ViewPropertyAnimator 补，AutoTransition 里的 ChangeTransform 会先移除，
 * 避免两条动画抢 scaleX/scaleY。动画只作用于 host 内的两个 content，
 * Toolbar、悬浮「今」、MainActivity 的 dock 与系统状态栏都不在动画树里。
 *
 * <p>缩放中心由 MonthGridView.selectedCellCenterIn(host) 给出（当前选中日期格），
 * 不是屏幕中心。全程只改 alpha/scale/translation，不触发 requestLayout。
 */
public final class CheckInViewSwitcher {

    private static final long DURATION_MS = 300;
    private static final float SMALL_SCALE = 0.3f;
    private static final float MONTH_ZOOM = 2.0f;
    private static final FastOutSlowInInterpolator INTERPOLATOR =
            new FastOutSlowInInterpolator();

    private final ViewGroup host;
    private final View monthContent;
    private final View yearContent;
    private final CheckInYearView yearView;
    private boolean animating;
    private boolean yearVisible;

    public CheckInViewSwitcher(ViewGroup host, View monthContent, View yearContent,
                               CheckInYearView yearView) {
        this.host = host;
        this.monthContent = monthContent;
        this.yearContent = yearContent;
        this.yearView = yearView;
    }

    public boolean isAnimating() {
        return animating;
    }

    public boolean isYearVisible() {
        return yearVisible;
    }

    /** 标题栏 / 「年」按钮：月视图缩小淡出，年视图从选中日期格放大进入 */
    public void toYear(int[] pivot, @Nullable Runnable onEnd) {
        if (animating || yearVisible) {
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }
        animating = true;
        setPivot(monthContent, pivot);
        setPivot(yearContent, pivot);
        resetScales(1f, SMALL_SCALE);
        TransitionManager.beginDelayedTransition(host, autoTransition(onEnd));
        yearContent.setVisibility(View.VISIBLE);
        monthContent.setVisibility(View.GONE);
        animateScale(monthContent, SMALL_SCALE);
        animateScale(yearContent, 1f);
        yearVisible = true;
    }

    /** 年视图返回月视图：反向缩放，中心同样是选中日期所在位置 */
    public void toMonth(int[] pivot, @Nullable Runnable onEnd) {
        if (animating || !yearVisible) {
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }
        animating = true;
        setPivot(monthContent, pivot);
        setPivot(yearContent, pivot);
        resetScales(SMALL_SCALE, 1f);
        TransitionManager.beginDelayedTransition(host, autoTransition(onEnd));
        monthContent.setVisibility(View.VISIBLE);
        yearContent.setVisibility(View.GONE);
        animateScale(monthContent, 1f);
        animateScale(yearContent, SMALL_SCALE);
        yearVisible = false;
    }

    /**
     * 年视图点某个月：该月卡 1.0→2.0 放大并平移到内容中心，其余 11 张淡出。
     * 动画结束统一复位，供下次进入年视图。
     */
    public void zoomMonthCard(int month1, @Nullable Runnable onEnd) {
        if (animating) {
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }
        View card = yearView.monthCardAt(month1);
        if (card == null) {
            if (onEnd != null) {
                onEnd.run();
            }
            return;
        }
        animating = true;
        int[] loc = new int[2];
        int[] hostLoc = new int[2];
        card.getLocationInWindow(loc);
        yearContent.getLocationInWindow(hostLoc);
        float cx = loc[0] + card.getWidth() / 2f - hostLoc[0];
        float cy = loc[1] + card.getHeight() / 2f - hostLoc[1];
        float dx = yearContent.getWidth() / 2f - cx;
        float dy = yearContent.getHeight() / 2f - cy;
        card.setPivotX(card.getWidth() / 2f);
        card.setPivotY(card.getHeight() / 2f);
        card.animate().scaleX(MONTH_ZOOM).scaleY(MONTH_ZOOM)
                .translationX(dx).translationY(dy)
                .setDuration(DURATION_MS).setInterpolator(INTERPOLATOR).start();
        for (int m = 1; m <= 12; m++) {
            View other = yearView.monthCardAt(m);
            if (other != null && other != card) {
                other.animate().alpha(0f).setDuration(DURATION_MS)
                        .setInterpolator(INTERPOLATOR).start();
            }
        }
        yearContent.postDelayed(() -> {
            for (int m = 1; m <= 12; m++) {
                View v = yearView.monthCardAt(m);
                if (v != null) {
                    v.animate().cancel();
                    v.setScaleX(1f);
                    v.setScaleY(1f);
                    v.setTranslationX(0f);
                    v.setTranslationY(0f);
                    v.setAlpha(1f);
                }
            }
            animating = false;
            if (onEnd != null) {
                onEnd.run();
            }
        }, DURATION_MS);
    }

    private Transition autoTransition(@Nullable Runnable onEnd) {
        AutoTransition set = new AutoTransition();
        set.setDuration(DURATION_MS);
        set.setInterpolator(INTERPOLATOR);
        for (int i = set.getTransitionCount() - 1; i >= 0; i--) {
            Transition child = set.getTransitionAt(i);
            if (child instanceof ChangeTransform) {
                set.removeTransition(child); // scale 交给 ViewPropertyAnimator
            }
        }
        set.addListener(new Transition.TransitionListener() {
            @Override
            public void onTransitionStart(Transition transition) {
            }

            @Override
            public void onTransitionEnd(Transition transition) {
                animating = false;
                if (onEnd != null) {
                    onEnd.run();
                }
            }

            @Override
            public void onTransitionCancel(Transition transition) {
                animating = false;
            }

            @Override
            public void onTransitionPause(Transition transition) {
            }

            @Override
            public void onTransitionResume(Transition transition) {
            }
        });
        return set;
    }

    private void resetScales(float month, float year) {
        monthContent.setScaleX(month);
        monthContent.setScaleY(month);
        yearContent.setScaleX(year);
        yearContent.setScaleY(year);
    }

    private void animateScale(View target, float to) {
        target.animate().scaleX(to).scaleY(to)
                .setDuration(DURATION_MS).setInterpolator(INTERPOLATOR).start();
    }

    private static void setPivot(View target, int[] pivot) {
        if (pivot == null || pivot.length < 2) {
            return;
        }
        target.setPivotX(pivot[0]);
        target.setPivotY(pivot[1]);
    }
}
