package com.bai.bookshelf.ui.checkin;

import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;
import androidx.transition.AutoTransition;
import androidx.transition.ChangeBounds;
import androidx.transition.ChangeTransform;
import androidx.transition.Transition;
import androidx.transition.TransitionManager;
import androidx.transition.TransitionSet;

import com.bai.bookshelf.R;

/**
 * 打卡页月视图 ↔ 年视图切换（类似澎湃 OS 日历的缩放转场）。
 *
 * <p>全部由 TransitionManager 驱动：AutoTransition 负责场景淡入淡出/边界稳定，
 * ScalePivotTransition 负责以指定 pivot 做 scale / alpha / translation。
 * 动画只作用于 host 内的两个 content，Toolbar、悬浮「今」、MainActivity 的 dock
 * 与系统状态栏都不在动画树里；RecyclerView、小结、图例被显式 excludeTarget。
 *
 * <p>缩放中心由 MonthGridView.selectedCellCenterIn(host) 给出（当前选中日期格），
 * 不是屏幕中心。动画窗口内不查库、不 notifyDataSetChanged，数据刷新统一延后到
 * TransitionListener.onTransitionEnd。
 */
public final class CheckInViewSwitcher {

    public static final long DURATION_MS = 300;
    /** 切年比月↔年切换略快，落在规格的 250–280ms 区间 */
    public static final long YEAR_SWITCH_MS = 260;
    public static final float SMALL_SCALE = 0.3f;
    public static final float MONTH_ZOOM = 2.0f;
    /** 切年新旧页的水平位移比例（相对内容区宽度） */
    public static final float YEAR_SHIFT_RATIO = 0.35f;
    private static final FastOutSlowInInterpolator INTERPOLATOR =
            new FastOutSlowInInterpolator();

    private final ViewGroup host;
    private final View monthContent;
    private final View yearContent;
    private final CheckInYearView yearView;
    private final MonthGridView monthGrid;
    private final ScalePivotTransition scaleTransition = new ScalePivotTransition();
    private boolean animating;
    private boolean yearVisible;

    public CheckInViewSwitcher(ViewGroup host, View monthContent, View yearContent,
                               CheckInYearView yearView, MonthGridView monthGrid) {
        this.host = host;
        this.monthContent = monthContent;
        this.yearContent = yearContent;
        this.yearView = yearView;
        this.monthGrid = monthGrid;
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
            runIfPresent(onEnd);
            return;
        }
        begin();
        prepareLayers(true);
        float px = pivotOf(pivot, 0);
        float py = pivotOf(pivot, 1);
        scaleTransition.clear();
        scaleTransition.spec(monthContent, new ScalePivotTransition.Spec(
                1f, SMALL_SCALE, 1f, 0f, px, py, 0f, 0f, true));
        scaleTransition.spec(yearContent, new ScalePivotTransition.Spec(
                SMALL_SCALE, 1f, 0f, 1f, px, py, 0f, 0f, false));
        TransitionManager.beginDelayedTransition(host, transitionSet(onEnd));
        yearVisible = true;
    }

    /** 年视图返回月视图：反向缩放，中心同样是选中日期所在位置 */
    public void toMonth(int[] pivot, @Nullable Runnable onEnd) {
        if (animating || !yearVisible) {
            runIfPresent(onEnd);
            return;
        }
        begin();
        prepareLayers(false);
        float px = pivotOf(pivot, 0);
        float py = pivotOf(pivot, 1);
        scaleTransition.clear();
        scaleTransition.spec(yearContent, new ScalePivotTransition.Spec(
                1f, SMALL_SCALE, 1f, 0f, px, py, 0f, 0f, true));
        scaleTransition.spec(monthContent, new ScalePivotTransition.Spec(
                SMALL_SCALE, 1f, 0f, 1f, px, py, 0f, 0f, false));
        TransitionManager.beginDelayedTransition(host, transitionSet(onEnd));
        yearVisible = false;
    }

    /**
     * 年视图点某个月：该月卡 1.0→2.0 放大并平移到内容中心，其余 11 张淡出。
     * 结束后统一复位，再交给 Fragment 切回月视图。
     */
    public void zoomMonthCard(int month1, @Nullable Runnable onEnd) {
        if (animating) {
            runIfPresent(onEnd);
            return;
        }
        View card = yearView.monthCardAt(month1);
        if (card == null) {
            runIfPresent(onEnd);
            return;
        }
        begin();
        int[] loc = new int[2];
        int[] hostLoc = new int[2];
        card.getLocationInWindow(loc);
        yearContent.getLocationInWindow(hostLoc);
        float cx = loc[0] + card.getWidth() / 2f - hostLoc[0];
        float cy = loc[1] + card.getHeight() / 2f - hostLoc[1];
        float dx = yearContent.getWidth() / 2f - cx;
        float dy = yearContent.getHeight() / 2f - cy;
        scaleTransition.clear();
        scaleTransition.spec(card, new ScalePivotTransition.Spec(
                1f, MONTH_ZOOM, 1f, 1f, card.getWidth() / 2f, card.getHeight() / 2f,
                dx, dy, false));
        for (int m = 1; m <= 12; m++) {
            View other = yearView.monthCardAt(m);
            if (other != null && other != card) {
                scaleTransition.spec(other, new ScalePivotTransition.Spec(
                        1f, 1f, 1f, 0f, other.getWidth() / 2f, other.getHeight() / 2f,
                        0f, 0f, false));
            }
        }
        TransitionManager.beginDelayedTransition((ViewGroup) yearContent, transitionSet(onEnd));
    }

    /**
     * 年视图左右滑切年：旧页 0→∓(0.35×内容宽) 淡出，新页 ±(0.35×内容宽)→0 淡入。
     * 同样由 TransitionManager + AutoTransition + ScalePivotTransition 编排。
     */
    public void switchYear(int delta, View oldLayer, View newLayer,
                           @Nullable Runnable onEnd) {
        if (animating) {
            runIfPresent(onEnd);
            return;
        }
        begin();
        float distance = host.getWidth() * YEAR_SHIFT_RATIO;
        float dir = delta < 0 ? -1f : 1f;
        newLayer.setVisibility(View.VISIBLE);
        newLayer.setAlpha(0f);
        newLayer.setTranslationX(-dir * distance);
        oldLayer.setAlpha(1f);
        oldLayer.setTranslationX(0f);
        float px = newLayer.getWidth() / 2f;
        float py = newLayer.getHeight() / 2f;
        scaleTransition.clear();
        scaleTransition.spec(oldLayer, new ScalePivotTransition.Spec(
                1f, 1f, 1f, 0f, px, py, 0f, 0f, dir * distance, 0f, true));
        scaleTransition.spec(newLayer, new ScalePivotTransition.Spec(
                1f, 1f, 0f, 1f, px, py, -dir * distance, 0f, 0f, 0f, false));
        TransitionSet set = transitionSet(onEnd);
        set.setDuration(YEAR_SWITCH_MS);
        TransitionManager.beginDelayedTransition((ViewGroup) yearContent, set);
    }

    private void begin() {
        animating = true;
        monthGrid.suspendDotAnimations(true);
        monthContent.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        yearContent.setLayerType(View.LAYER_TYPE_HARDWARE, null);
    }

    /** beginDelayedTransition 之前写好起始状态；两层都保持 VISIBLE 才能被 Transition 捕获 */
    private void prepareLayers(boolean enteringYear) {
        monthContent.setVisibility(View.VISIBLE);
        yearContent.setVisibility(View.VISIBLE);
        if (enteringYear) {
            set(monthContent, 1f, 1f);
            set(yearContent, SMALL_SCALE, 0f);
        } else {
            set(monthContent, SMALL_SCALE, 0f);
            set(yearContent, 1f, 1f);
        }
    }

    private static void set(View v, float scale, float alpha) {
        v.setScaleX(scale);
        v.setScaleY(scale);
        v.setAlpha(alpha);
        v.setTranslationX(0f);
        v.setTranslationY(0f);
    }

    private TransitionSet transitionSet(@Nullable Runnable onEnd) {
        AutoTransition auto = new AutoTransition();
        for (int i = auto.getTransitionCount() - 1; i >= 0; i--) {
            Transition child = auto.getTransitionAt(i);
            // 布局变化与 transform 都交给 ScalePivotTransition，避免两条动画抢同一属性
            if (child instanceof ChangeTransform || child instanceof ChangeBounds) {
                auto.removeTransition(child);
            }
        }
        TransitionSet set = new TransitionSet();
        set.addTransition(auto).addTransition(scaleTransition);
        set.setDuration(DURATION_MS);
        set.setInterpolator(INTERPOLATOR);
        // 内容区之外的层不参与动画；列表/小结/图例也不逐项重绘
        set.excludeTarget(R.id.toolbar, true);
        set.excludeTarget(R.id.fab_today, true);
        set.excludeTarget(R.id.rv_checkin, true);
        set.excludeTarget(R.id.ll_checkin_summary, true);
        set.excludeTarget(R.id.ll_checkin_legend, true);
        set.excludeTarget(R.id.ll_year_summary, true);
        set.excludeTarget(R.id.ll_year_legend, true);
        set.addListener(new Transition.TransitionListener() {
            @Override
            public void onTransitionStart(Transition transition) {
            }

            @Override
            public void onTransitionEnd(Transition transition) {
                finish();
                runIfPresent(onEnd); // 动画结束后才刷新数据 / 定位月份
            }

            @Override
            public void onTransitionCancel(Transition transition) {
                finish();
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

    private void finish() {
        animating = false;
        monthGrid.suspendDotAnimations(false);
        monthContent.setLayerType(View.LAYER_TYPE_NONE, null);
        yearContent.setLayerType(View.LAYER_TYPE_NONE, null);
    }

    private static float pivotOf(int[] pivot, int index) {
        return pivot != null && pivot.length > index ? pivot[index] : 0f;
    }

    private static void runIfPresent(@Nullable Runnable action) {
        if (action != null) {
            action.run();
        }
    }
}
