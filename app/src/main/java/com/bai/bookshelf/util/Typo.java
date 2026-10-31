package com.bai.bookshelf.util;

import android.util.TypedValue;
import android.widget.TextView;

import androidx.annotation.DimenRes;

/**
 * 代码里设置字号的唯一入口（v5.8.4）。
 *
 * <p>布局侧的字号早就收进 {@code text_*} 令牌了，但 Java 里还有几十处
 * {@code setTextSize(13)} 这样的裸数字——改字阶时根本搜不全。现在统一走
 * {@link #text(TextView, int)}，按资源取值（px），系统字体缩放照常生效。
 */
public final class Typo {

    private Typo() {
    }

    /** 按设计令牌设置字号：{@code Typo.text(tv, R.dimen.text_body_small)} */
    public static void text(TextView tv, @DimenRes int dimen) {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                tv.getResources().getDimension(dimen));
    }
}
