package com.bai.bookshelf.ui.widget;

import android.content.Context;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * 固定长宽比的图片控件（v4.19）。
 *
 * <p><b>要解决的问题：</b>书架网格里的封面之前靠外部「估算屏幕宽度」来算高度，
 * 估算值和真实列宽对不上（列宽由 GridLayoutManager 按 span 平分、再减去
 * ItemDecoration 的偏移决定），导致同一种封面在不同列里宽高比不一致——
 * 中间列的封面比两侧列更宽，一屏看上去参差不齐，很「奇怪」。
 *
 * <p><b>做法：</b>让控件自己按<b>实测宽度</b>换算高度（高 = 宽 × 4/3，约 3:4，
 * 与列表模式 54dp×72dp 的封面一致），无论列宽是多少、几列、平板还是折叠屏，
 * 每一本书的封面比例都严格相同，也不会出现被裁切变形的问题。
 */
public class RatioImageView extends AppCompatImageView {

    /** 高宽比（高 / 宽），默认 3:4 */
    private float ratio = 4f / 3f;

    public RatioImageView(Context context) {
        super(context);
    }

    public RatioImageView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public RatioImageView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /** 设置高宽比（高 / 宽），如 4f/3f 表示 3:4 */
    public void setRatio(float ratio) {
        if (ratio > 0f && this.ratio != ratio) {
            this.ratio = ratio;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        if (width <= 0 || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            // 宽度未知（理论上不会发生），交给父类按原规则测量
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        int height = Math.max(1, Math.round(width * ratio));
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
    }
}
