package com.bai.bookshelf.util;

import android.graphics.drawable.Drawable;
import android.graphics.drawable.TransitionDrawable;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;

/** 筛选胶囊选中态反馈（v5.9.3）：背景 120ms 交叉 + scale 0.96→1，不动列表 */
public final class PillMotion {

    private static final int DURATION_MS = 120;

    private PillMotion() {
    }

    public static void toggle(View chip, boolean selected) {
        toggle(chip, selected, DURATION_MS);
    }

    public static void toggle(View chip, boolean selected, int durationMs) {
        int target = selected ? R.drawable.bg_filter_pill_selected : R.drawable.bg_filter_pill;
        if (!Motion.enabled(chip.getContext())) {
            chip.setBackgroundResource(target);
            chip.setScaleX(1f);
            chip.setScaleY(1f);
            return;
        }
        Drawable plain = ContextCompat.getDrawable(chip.getContext(), R.drawable.bg_filter_pill);
        Drawable chosen = ContextCompat.getDrawable(chip.getContext(), R.drawable.bg_filter_pill_selected);
        TransitionDrawable fade = new TransitionDrawable(selected
                ? new Drawable[]{plain, chosen}
                : new Drawable[]{chosen, plain});
        chip.setBackground(fade);
        fade.startTransition(durationMs);
        chip.postDelayed(() -> {
            if (chip.getBackground() == fade) {
                chip.setBackgroundResource(target);
            }
        }, durationMs);
        chip.setScaleX(0.96f);
        chip.setScaleY(0.96f);
        chip.animate().scaleX(1f).scaleY(1f)
                .setDuration(durationMs)
                .setInterpolator(Motion.STANDARD)
                .start();
    }
}
