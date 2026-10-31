package com.bai.bookshelf.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.LinearLayout;

import com.bai.bookshelf.R;

/**
 * 底部导航选中胶囊（v5.8.17 修）：只淡入淡出**底色**，图标与文字全程保持可见。
 *
 * <p>v5.8.7 的实现直接对胶囊 View 做 alpha 动画，而胶囊里装着图标和标签，
 * 结果非当前 Tab 整块淡成透明——底部导航看起来「只剩当前页那一个」。
 * 这里把透明度动画挂到背景 Drawable 上，View 自身 alpha 恒为 1。
 */
public final class NavPill {

    private static final int FULL_ALPHA = 255;

    private NavPill() {
    }

    /** 选中：铺上胶囊底色并从 0 淡入，同时带一点位移与缩放（slideFromPx 是进场方向偏移） */
    public static void show(LinearLayout pill, float slideFromPx) {
        pill.animate().cancel();
        pill.setAlpha(1f);
        pill.setBackgroundResource(R.drawable.bg_nav_selected);
        Drawable bg = pill.getBackground();
        if (bg != null) {
            bg = bg.mutate();
            bg.setAlpha(0);
            fade(pill, bg, FULL_ALPHA, 120, null);
        }
        pill.setScaleX(0.94f);
        pill.setScaleY(0.94f);
        pill.setTranslationX(slideFromPx);
        pill.animate().scaleX(1f).scaleY(1f).translationX(0f).setDuration(120).start();
    }

    /** 未选中：底色淡出后摘掉背景；胶囊留在原位，内容照常显示 */
    public static void hide(LinearLayout pill) {
        pill.animate().cancel();
        pill.setAlpha(1f);
        pill.setScaleX(1f);
        pill.setScaleY(1f);
        pill.setTranslationX(0f);
        Drawable bg = pill.getBackground();
        if (bg == null) {
            return;
        }
        bg = bg.mutate();
        final Drawable target = bg;
        fade(pill, target, 0, 90, () -> {
            // 只摘自己那张底，快速连点时别把刚铺上的新胶囊删掉
            if (pill.getBackground() == target) {
                pill.setBackground(null);
            }
        });
    }

    private static void fade(final View pill, final Drawable bg,
                             int to, int durationMs, final Runnable end) {
        int from = bg.getAlpha();
        if (from == to) {
            if (end != null) {
                end.run();
            }
            return;
        }
        ValueAnimator animator = ValueAnimator.ofInt(from, to);
        animator.setDuration(durationMs);
        animator.addUpdateListener(a -> {
            bg.setAlpha((Integer) a.getAnimatedValue());
            pill.invalidate();
        });
        if (end != null) {
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    end.run();
                }
            });
        }
        animator.start();
    }
}
