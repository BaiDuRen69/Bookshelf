package com.bai.bookshelf.ui.checkin;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.transition.Transition;
import androidx.transition.TransitionValues;

import java.util.HashMap;
import java.util.Map;

/**
 * 以指定 pivot 做 scale / alpha / translation 的自定义 Transition。
 *
 * <p>AutoTransition 不提供 pivot，也无法把缩放中心钉在选中日期格上；
 * 这里在 capture 阶段登记每个目标 View 的规格，动画全部由
 * TransitionManager.beginDelayedTransition 驱动，createAnimator 只产出属性动画。
 */
public final class ScalePivotTransition extends Transition {

    private static final String KEY = "com.bai.bookshelf:scalePivot:value";

    public static final class Spec {
        final float fromScale;
        final float toScale;
        final float fromAlpha;
        final float toAlpha;
        final float pivotX;
        final float pivotY;
        final float fromTranslationX;
        final float fromTranslationY;
        final float toTranslationX;
        final float toTranslationY;
        final boolean hideAtEnd;

        /** 月↔年 / 点月放大：起点位移为 0 */
        public Spec(float fromScale, float toScale, float fromAlpha, float toAlpha,
                    float pivotX, float pivotY, float toTranslationX, float toTranslationY,
                    boolean hideAtEnd) {
            this(fromScale, toScale, fromAlpha, toAlpha, pivotX, pivotY,
                    0f, 0f, toTranslationX, toTranslationY, hideAtEnd);
        }

        /** 切年：新页从 ±(0.35×内容宽) 回到 0，旧页反向退出 */
        public Spec(float fromScale, float toScale, float fromAlpha, float toAlpha,
                    float pivotX, float pivotY, float fromTranslationX, float fromTranslationY,
                    float toTranslationX, float toTranslationY, boolean hideAtEnd) {
            this.fromScale = fromScale;
            this.toScale = toScale;
            this.fromAlpha = fromAlpha;
            this.toAlpha = toAlpha;
            this.pivotX = pivotX;
            this.pivotY = pivotY;
            this.fromTranslationX = fromTranslationX;
            this.fromTranslationY = fromTranslationY;
            this.toTranslationX = toTranslationX;
            this.toTranslationY = toTranslationY;
            this.hideAtEnd = hideAtEnd;
        }
    }

    private final Map<View, Spec> specs = new HashMap<>();

    public void spec(View view, Spec spec) {
        if (view != null && spec != null) {
            specs.put(view, spec);
        }
    }

    public void clear() {
        specs.clear();
    }

    @Override
    public void captureStartValues(@NonNull TransitionValues values) {
        values.values.put(KEY, specs.containsKey(values.view) ? "start" : "skip");
    }

    @Override
    public void captureEndValues(@NonNull TransitionValues values) {
        values.values.put(KEY, specs.containsKey(values.view) ? "end" : "skip");
    }

    @Nullable
    @Override
    public Animator createAnimator(@NonNull ViewGroup sceneRoot,
                                   @Nullable TransitionValues startValues,
                                   @Nullable TransitionValues endValues) {
        if (startValues == null || endValues == null) {
            return null;
        }
        final View view = endValues.view;
        final Spec spec = specs.get(view);
        if (spec == null) {
            return null;
        }
        view.setPivotX(spec.pivotX);
        view.setPivotY(spec.pivotY);
        ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
        anim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            float scale = spec.fromScale + (spec.toScale - spec.fromScale) * f;
            float alpha = spec.fromAlpha + (spec.toAlpha - spec.fromAlpha) * f;
            view.setScaleX(scale);
            view.setScaleY(scale);
            view.setAlpha(alpha);
            view.setTranslationX(spec.fromTranslationX
                    + (spec.toTranslationX - spec.fromTranslationX) * f);
            view.setTranslationY(spec.fromTranslationY
                    + (spec.toTranslationY - spec.fromTranslationY) * f);
        });
        anim.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setScaleX(1f);
                view.setScaleY(1f);
                view.setAlpha(1f);
                view.setTranslationX(0f);
                view.setTranslationY(0f);
                if (spec.hideAtEnd) {
                    view.setVisibility(View.GONE);
                }
            }
        });
        return anim;
    }
}
