package com.bai.bookshelf.ui.stats;

import android.text.Spannable;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.graphics.Typeface;

import java.util.Calendar;
import java.util.Locale;

/**
 * 统计页的文案格式：日期/金额/星期/当天详情，以及「已阅读 X 小时 X 分钟」的混排样式。
 * 纯字符串逻辑集中在这里，便于单测。
 */
public final class StatsText {

    private StatsText() {
    }

    public static String formatYmd(int y, int m, int d) {
        return y + "年" + m + "月" + d + "日";
    }

    /** 点击日历某天后的详情文案（v4.20） */
    public static String dayDetailText(int year, int month0, int day, int count) {
        String week = weekLabel(year, month0, day);
        if (count > 0) {
            return String.format(Locale.getDefault(), "%d年%d月%d日 %s · 购买 %d 本",
                    year, month0 + 1, day, week, count);
        }
        return String.format(Locale.getDefault(), "%d年%d月%d日 %s · 没有购买记录",
                year, month0 + 1, day, week);
    }

    /** 金额展示：¥123.45 / ¥123（去掉多余的 .00） */
    public static String formatMoney(double v) {
        if (v <= 0) {
            return "¥0";
        }
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return "¥" + String.valueOf((long) v);
        }
        return String.format(Locale.getDefault(), "¥%.2f", v);
    }

    public static String pad2(int v) {
        return v < 10 ? "0" + v : String.valueOf(v);
    }

    /**
     * 解析 yyyy-MM-dd；非法或空返回 null。返回 {年, 月(0-based), 日}。
     * v3.0：显式校验分隔符，避免 2024/05/06、2024-0506 这类脏数据被误解析成合法日期。
     */
    public static int[] parseYmd(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        if (t.length() < 10 || t.charAt(4) != '-' || t.charAt(7) != '-') {
            return null;
        }
        try {
            int y = Integer.parseInt(t.substring(0, 4));
            int m = Integer.parseInt(t.substring(5, 7));
            int d = Integer.parseInt(t.substring(8, 10));
            if (m < 1 || m > 12 || d < 1 || d > 31) {
                return null;
            }
            return new int[]{y, m - 1, d};
        } catch (Exception e) {
            return null;
        }
    }

    public static int daysInMonth(int year, int month0) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month0, 1);
        return c.getActualMaximum(Calendar.DAY_OF_MONTH);
    }

    /** 星期标签：周一 … 周日 */
    public static String weekLabel(int year, int month0, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month0, day);
        String[] names = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        // Calendar.DAY_OF_WEEK：1=周日 … 7=周六 → 转成「周一为 0」
        int dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        return names[dow];
    }

    /** 数据不足时统一说的话（口径层给 -1，呈现层翻成中文） */
    public static final String NO_DATA = "数据不足";

    /** 单位相对数字的比例（v5.9.1）：0.45em，靠比例而不是靠 marginBottom 顶基线 */
    static final float UNIT_SCALE = 0.45f;

    /**
     * 数字 + 单位排成一行（v5.9.1）：数字保持 1em（字号由样式档决定），
     * 单位收到 {@link #UNIT_SCALE}em。unit 为空、value 为空或 -1（口径层的
     * 「数据不足」）时返回纯文本，不加任何 span，读屏也不会念出多余结构。
     */
    public static CharSequence metric(String value, String unit) {
        String[] parts = metricParts(value, unit);
        String v = parts[0];
        String u = parts[1];
        if (u.isEmpty()) {
            return v;
        }
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(v);
        int from = sb.length();
        sb.append(u);
        sb.setSpan(new RelativeSizeSpan(UNIT_SCALE), from, sb.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /**
     * 数字与单位的拆分（纯函数，JVM 可测）：返回 {数字段, 单位段}。
     * 单位为空、或数字段是「数据不足」这类占位时，单位段给空串——
     * {@link #metric} 只按这里的边界加 span，所以边界对了排版就对了。
     */
    static String[] metricParts(String value, String unit) {
        String v = metricValue(value);
        if (unit == null || unit.isEmpty() || NO_DATA.equals(v)) {
            return new String[]{v, ""};
        }
        return new String[]{v, unit};
    }

    /** 带次级色的版本：颜色要 Context 解析 ?attr/on_surface_variant，只能由调用侧给 */
    public static CharSequence metric(String value, String unit, int unitColor) {
        CharSequence base = metric(value, unit);
        if (!(base instanceof Spannable)) {
            return base;
        }
        Spannable sp = (Spannable) base;
        sp.setSpan(new ForegroundColorSpan(unitColor), metricValue(value).length(), sp.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    /** -1 / 空 = 数据不足；其余原样（小数、千分位都不截断） */
    private static String metricValue(String value) {
        if (value == null || value.trim().isEmpty() || "-1".equals(value.trim())) {
            return NO_DATA;
        }
        return value;
    }

    /** 「已阅读 X 小时 X 分钟」：单位 15sp、数字 28sp 衬线，突出但不笨重 */
    public static CharSequence buildDurationText(long hours, long minutes) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append(span("已阅读 ", 15, false));
        sb.append(serifNumber(String.valueOf(hours)));
        sb.append(span(" 小时 ", 15, false));
        sb.append(serifNumber(String.valueOf(minutes)));
        sb.append(span(" 分钟", 15, false));
        return sb;
    }

    private static CharSequence serifNumber(String s) {
        SpannableString ss = new SpannableString(s);
        ss.setSpan(new AbsoluteSizeSpan(28, true),
                0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ss.setSpan(new TypefaceSpan("serif"),
                0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return ss;
    }

    static CharSequence span(String s, int spSize, boolean bold) {
        SpannableString ss = new SpannableString(s);
        ss.setSpan(new AbsoluteSizeSpan(spSize, true),
                0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (bold) {
            ss.setSpan(new StyleSpan(Typeface.BOLD),
                    0, s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return ss;
    }
}
