package com.bai.bookshelf.util;

import android.util.TypedValue;
import android.widget.TextView;

import androidx.annotation.DimenRes;

import com.bai.bookshelf.R;

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

    /**
     * 自绘 View 的 Paint 字号（v5.9.0）：Paint.setTextSize 收 px，
     * 直接写 9/10/8 这类字面量既绕过字阶也不跟系统字体缩放，统一从令牌取。
     */
    public static float paintPx(android.content.Context ctx, @DimenRes int dimen) {
        return ctx.getResources().getDimension(dimen);
    }

    /**
     * L5 标签档：图表里最小的那些字统一走它。夜读是应用内主题而不是 -night
     * 资源限定符，所以这里按主题选档（布局侧走 ?attr/text_size_overline，
     * Paint 拿不到 style，只能自己选）。
     */
    public static float labelPx(android.content.Context ctx) {
        return ctx.getResources().getDimension(
                ThemeManager.current(ctx) == ThemeManager.THEME_NIGHT
                        ? R.dimen.text_caption_night : R.dimen.text_caption);
    }
}
