package com.bai.bookshelf.util;

import java.util.Locale;

/**
 * 金额的三种展示口径（v5.9.0 收口）：以前三个页面各写了一个同名的 money()，
 * 格式化规则却不一样，读代码时看不出这行的钱为什么和别人不一样，这里显式命名。
 */
public final class Money {

    private Money() {
    }

    /** 「¥12.34」——永远两位小数，详情页逐条列价用 */
    public static String fixed(double value) {
        return "¥" + String.format(Locale.getDefault(), "%.2f", value);
    }

    /** 「12.34」/「120」——不带符号，整数就不补零；调用方自己拼「¥」 */
    public static String plain(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.getDefault(), "%.2f", value);
    }

    /** 「¥12.34」/「¥120」——小额保精度、大额收位数，统计页一行塞得下 */
    public static String compact(double value) {
        return value < 10 ? String.format(Locale.getDefault(), "¥%.2f", value)
                : String.format(Locale.getDefault(), "¥%.0f", value);
    }
}
