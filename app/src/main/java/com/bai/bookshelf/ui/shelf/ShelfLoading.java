package com.bai.bookshelf.ui.shelf;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.view.View;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.FragmentShelfBinding;

/**
 * 书架首屏骨架屏（v5.6 从 ShelfFragment 抽出）：
 * 呼吸动画只在加载期间运行，形状跟随当前视图（网格九宫格 / 列表行）。
 */
public class ShelfLoading {

    private ObjectAnimator animator;

    public void show(FragmentShelfBinding b, boolean show, int viewMode) {
        if (b == null) {
            return;
        }
        b.llLoading.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show) {
            // 骨架屏按当前视图选择形状，避免网格模式先闪一下列表骨架
            b.llLoading.removeAllViews();
            android.view.LayoutInflater inflater =
                    android.view.LayoutInflater.from(b.getRoot().getContext());
            if (viewMode == 1) {
                b.llLoading.addView(inflater.inflate(
                        R.layout.view_skeleton_grid, b.llLoading, false));
            } else {
                for (int i = 0; i < 5; i++) {
                    b.llLoading.addView(inflater.inflate(
                            R.layout.item_skeleton_row, b.llLoading, false));
                }
            }
            if (animator == null) {
                animator = ObjectAnimator.ofFloat(b.llLoading, "alpha", 0.45f, 1f);
                animator.setDuration(900);
                animator.setRepeatMode(ValueAnimator.REVERSE);
                animator.setRepeatCount(ValueAnimator.INFINITE);
            }
            animator.start();
        } else {
            if (animator != null) {
                animator.cancel();
            }
            b.llLoading.setAlpha(1f);
        }
    }

    /** 视图销毁时停止动画 */
    public void cancel() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }
}
