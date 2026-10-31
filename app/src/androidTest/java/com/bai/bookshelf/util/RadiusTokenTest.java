package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.R;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 圆角令牌（v5.8.22 批次 H）：把「改的是资源、生效的是渲染值」这件事钉死。
 *
 * <p>shape XML 里的 corners 只有解析成 GradientDrawable 才看得出真实半径，
 * 所以这里直接取运行时值断言，不靠目测截图。
 */
@RunWith(AndroidJUnit4.class)
public class RadiusTokenTest {

    private static final float TOLERANCE_PX = 0.6f;

    private final Context ctx = ApplicationProvider.getApplicationContext();

    private float dp(int value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }

    private float dimenPx(int id) {
        return ctx.getResources().getDimension(id);
    }

    private float cornerOf(int drawableId) {
        GradientDrawable d = (GradientDrawable) AppCompatResources.getDrawable(ctx, drawableId);
        assertNotNull("drawable 取不出来: " + ctx.getResources().getResourceEntryName(drawableId), d);
        return d.getCornerRadius();
    }

    @Test
    public void 令牌档位齐了且值不变() {
        assertEquals(8f, dimenPx(R.dimen.radius_sm) / dp(1), TOLERANCE_PX);
        assertEquals(12f, dimenPx(R.dimen.radius_md) / dp(1), TOLERANCE_PX);
        assertEquals(16f, dimenPx(R.dimen.radius_lg) / dp(1), TOLERANCE_PX);
        assertEquals(20f, dimenPx(R.dimen.radius_xl) / dp(1), TOLERANCE_PX);
        assertEquals(24f, dimenPx(R.dimen.radius_2xl) / dp(1), TOLERANCE_PX);
        assertEquals(22f, dimenPx(R.dimen.radius_pill) / dp(1), TOLERANCE_PX);
    }

    @Test
    public void 底部导航选中块是真胶囊() {
        float r = cornerOf(R.drawable.bg_nav_selected);
        assertEquals("选中块半径应等于 radius_pill", dimenPx(R.dimen.radius_pill), r, TOLERANCE_PX);
        // 胶囊判据：半径 = 控件高度的一半。小米 11 上 dumpsys 实测该 pill 高 121px（44dp @440dpi）
        float pillHeightPx = 121f;
        assertEquals("半径应当是胶囊高度的一半", pillHeightPx / 2f, r, 2f);
    }

    @Test
    public void 分段选中块与底槽同心() {
        float track = cornerOf(R.drawable.bg_seg_track);
        float inner = cornerOf(R.drawable.bg_seg_selected);
        assertEquals("底槽仍是 16dp", dp(16), track, TOLERANCE_PX);
        // 内层 = 外层 − 内边距：统计页与购买日历的分段容器 padding 都是 3dp
        assertEquals("内层半径应为 16 − 3 = 13dp", dp(13), inner, TOLERANCE_PX);
        assertTrue("内层必须比外层小，否则看着像贴边", inner < track);
    }

    @Test
    public void 没有把标准卡片圆角改掉() {
        assertEquals("card_corner 保持 16dp（标准卡片）", dp(16),
                ctx.getResources().getDimension(R.dimen.card_corner), TOLERANCE_PX);
        assertEquals("扫码大胶囊保持 26dp", dp(26),
                cornerOf(R.drawable.bg_scan_capsule), TOLERANCE_PX);
    }
}
