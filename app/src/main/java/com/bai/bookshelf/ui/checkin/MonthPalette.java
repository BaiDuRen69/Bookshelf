package com.bai.bookshelf.ui.checkin;

import android.content.Context;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.ThemeManager;

/**
 * 月历格子用到的语义色与农历字号：建视图时解析一次。
 *
 * <p>切主题由 Activity {@code recreate()} 带过来，不做热更新；因此这里没有
 * refresh 入口，也不该在绑格子/打卡时重复查资源。
 */
final class MonthPalette {

    final int primary;
    final int selectedBg;
    final int selectedStroke;
    final int onSurface;
    final int variant;
    final int pickedBg;
    final int referenceBg;
    final int previewBg;
    final int onPrimary;
    final int ring;
    final int lunar;
    final int holiday;
    final int adjacentText;
    final int todayBg;
    final int onToday;
    final int todayRing;
    /** 0 表示大字模式/窄屏不显示农历，给格子省一行 */
    final float lunarTextSize;

    private MonthPalette(int primary, int selectedBg, int selectedStroke, int onSurface,
                         int variant, int pickedBg, int referenceBg, int previewBg,
                         int onPrimary, int ring, int lunar, int holiday, int adjacentText,
                         int todayBg, int onToday, int todayRing, float lunarTextSize) {
        this.primary = primary;
        this.selectedBg = selectedBg;
        this.selectedStroke = selectedStroke;
        this.onSurface = onSurface;
        this.variant = variant;
        this.pickedBg = pickedBg;
        this.referenceBg = referenceBg;
        this.previewBg = previewBg;
        this.onPrimary = onPrimary;
        this.ring = ring;
        this.lunar = lunar;
        this.holiday = holiday;
        this.adjacentText = adjacentText;
        this.todayBg = todayBg;
        this.onToday = onToday;
        this.todayRing = todayRing;
        this.lunarTextSize = lunarTextSize;
    }

    static MonthPalette resolve(Context ctx) {
        int primary = ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY);
        int onSurface = ThemeManager.resolveColor(ctx, R.attr.on_surface);
        int variant = ThemeManager.resolveColor(ctx, R.attr.on_surface_variant);
        float scale = ctx.getResources().getConfiguration().fontScale;
        float widthDp = ctx.getResources().getDisplayMetrics().widthPixels
                / ctx.getResources().getDisplayMetrics().density;
        // 大字模式/窄屏把农历整行收掉，公历数字与打卡点不裁
        float lunarTextSize = scale < 1.3f && widthDp >= 360f
                ? ctx.getResources().getDimension(R.dimen.checkin_lunar_text) : 0f;
        return new MonthPalette(
                primary,
                ThemeManager.resolveColor(ctx, R.attr.checkin_selected_bg),
                ThemeManager.resolveColor(ctx, R.attr.checkin_selected_stroke),
                onSurface,
                variant,
                ThemeManager.resolveColor(ctx, R.attr.primary_container),
                ThemeManager.resolveColor(ctx, R.attr.surface_dim),
                ThemeManager.resolveColor(ctx, R.attr.outline),
                // 主色实心圆上的文字走 material colorOnPrimary；nonTransitiveRClass 下要全限定
                ThemeManager.resolveColor(ctx,
                        com.google.android.material.R.attr.colorOnPrimary),
                ThemeManager.resolveColor(ctx, R.attr.checkin_day_ring),
                ThemeManager.resolveColor(ctx, R.attr.on_surface_variant),
                ThemeManager.resolveColor(ctx, R.attr.checkin_holiday_text),
                ThemeManager.resolveColor(ctx, R.attr.checkin_adjacent_text),
                ThemeManager.resolveColor(ctx, R.attr.checkin_today_bg),
                ThemeManager.resolveColor(ctx, R.attr.checkin_on_today),
                ThemeManager.resolveColor(ctx, R.attr.checkin_today_ring),
                lunarTextSize);
    }
}
