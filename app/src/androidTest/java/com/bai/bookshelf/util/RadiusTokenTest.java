package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.appcompat.content.res.AppCompatResources;
import androidx.cardview.widget.CardView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

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

    @Test
    public void 换令牌后各处仍是规定档位() {
        assertEquals(dp(8), cornerOf(R.drawable.bg_icon_container), TOLERANCE_PX);
        assertEquals(dp(8), cornerOf(R.drawable.bg_excerpt_thumb), TOLERANCE_PX);
        assertEquals(dp(20), cornerOf(R.drawable.bg_float_card), TOLERANCE_PX);
        assertEquals("小组件兜底底", dp(20), cornerOf(R.drawable.bg_widget), TOLERANCE_PX);
        for (int id : new int[]{R.drawable.bg_widget_blue, R.drawable.bg_widget_graphite,
                R.drawable.bg_widget_green, R.drawable.bg_widget_night,
                R.drawable.bg_widget_orange, R.drawable.bg_widget_purple,
                R.drawable.bg_widget_red}) {
            assertEquals("主题小组件底半径不一致", dp(20), cornerOf(id), TOLERANCE_PX);
        }
    }

    /**
     * 五、明确保持原样的小控件（防的是以后有人再来「全局收敛」）。
     *
     * <p>图表与日历是 Canvas 自绘（Treemap 6、MiniBar 6、Heatmap 6/2/1.5、
     * DistRow 4、ReadingStats 2、CalendarPicker 9），没有可断言的公开出口，
     * 改动只能靠代码评审挡住；这里钉住的是 drawable 那一层。
     */
    @Test
    public void 小控件与封面保持小圆角() {
        for (int id : new int[]{R.drawable.bg_chip, R.drawable.bg_status_chip,
                R.drawable.bg_badge, R.drawable.bg_progress_badge, R.drawable.bg_cover}) {
            assertEquals(ctx.getResources().getResourceEntryName(id) + " 应保持 8dp",
                    dp(8), cornerOf(id), TOLERANCE_PX);
        }
        for (int id : new int[]{R.drawable.bg_dialog_row, R.drawable.bg_summary_chip}) {
            assertEquals(ctx.getResources().getResourceEntryName(id) + " 应保持 12dp",
                    dp(12), cornerOf(id), TOLERANCE_PX);
        }
        assertEquals("搜索框本来就是 44dp 高的胶囊", dp(22),
                cornerOf(R.drawable.bg_search), TOLERANCE_PX);
        assertEquals(dp(20), cornerOf(R.drawable.bg_filter_pill), TOLERANCE_PX);
        assertEquals(dp(20), cornerOf(R.drawable.bg_filter_pill_selected), TOLERANCE_PX);
    }

    /** 布局里的 CardView：只有统计页两张 hero 卡升到 20dp，其余统计卡保持 16dp 形成层级 */
    /**
     * 弹窗与底部面板：走的是「主题属性 → 形状样式」这条链，不是 drawable，
     * 所以必须按真实链路解析一次，确认 24dp 真的落在弹窗上。
     */
    @Test
    public void 弹窗与面板顶部走24dp() {
        // 弹窗：MaterialAlertDialog.Builder 用的就是 ThemeOverlay.Bookshelf.Dialog
        Context dialogCtx = new ContextThemeWrapper(ctx, R.style.ThemeOverlay_Bookshelf_Dialog);
        float[] dialog = shapeOf(dialogCtx, "shapeAppearanceCornerExtraLarge");
        assertEquals("弹窗四角应为 radius_2xl", dp(24), dialog[0], TOLERANCE_PX);

        // 面板：bottomSheetStyle → Widget.Bookshelf.BottomSheet → shapeAppearance
        Context sheetCtx = new ContextThemeWrapper(ctx, R.style.Widget_Bookshelf_BottomSheet);
        float[] top = shapeOf(sheetCtx, "shapeAppearance");
        assertEquals("面板左上应为 radius_2xl", dp(24), top[1], TOLERANCE_PX);
        assertEquals("面板右上应为 radius_2xl", dp(24), top[2], TOLERANCE_PX);
        assertTrue("面板底部两角必须是直角或继承值，实测 " + java.util.Arrays.toString(top),
                top[3] <= 0f && top[4] <= 0f);
    }

    /**
     * 沿「主题属性 → 样式 → cornerSize* 」取圆角。
     *
     * <p>工程开了 nonTransitiveRClass，Material 的 attr / styleable 不在 App 的 R 里，
     * 所以按名字在运行时取属性 id（取不到就直接断言失败，不静默跳过）。
     *
     * @return [cornerSize, topL, topR, bottomL, bottomR]（未定义的返回 -1）
     */
    private float[] shapeOf(Context themed, String shapeAttrName) {
        int attr = materialAttr(shapeAttrName);
        TypedValue tv = new TypedValue();
        assertTrue("主题里找不到属性 " + shapeAttrName,
                themed.getTheme().resolveAttribute(attr, tv, true));
        int[] ids = new int[]{materialAttr("cornerSize"), materialAttr("cornerSizeTopLeft"),
                materialAttr("cornerSizeTopRight"), materialAttr("cornerSizeBottomLeft"),
                materialAttr("cornerSizeBottomRight")};
        android.content.res.TypedArray a = themed.obtainStyledAttributes(tv.resourceId, ids);
        try {
            float[] out = new float[ids.length];
            for (int i = 0; i < ids.length; i++) {
                out[i] = a.getDimension(i, -1f);
            }
            return out;
        } finally {
            a.recycle();
        }
    }

    private int materialAttr(String name) {
        // 库资源在运行时并进 App 的资源空间，所以先按 App 包名找，再退回库包名
        int id = ctx.getResources().getIdentifier(name, "attr", ctx.getPackageName());
        if (id == 0) {
            id = ctx.getResources().getIdentifier(name, "attr", "com.google.android.material");
        }
        assertTrue("找不到属性 " + name + "（依赖版本变了？）", id != 0);
        return id;
    }

    @Test
    public void 布局卡片半径按层级走() {
        final float[] r = new float[4];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context themed = new ContextThemeWrapper(ctx,
                    ThemeManager.styleRes(ThemeManager.current(ctx)));
            View series = LayoutInflater.from(themed).inflate(R.layout.item_series_book, null);
            r[0] = ((CardView) ((ViewGroup) series).getChildAt(0)).getRadius();
            View stats = LayoutInflater.from(themed).inflate(R.layout.fragment_stats, null);
            r[1] = ((CardView) stats.findViewById(R.id.card_stats_hero)).getRadius();
            r[2] = ((CardView) stats.findViewById(R.id.card_year_report)).getRadius();
            r[3] = ((CardView) stats.findViewById(R.id.card_reading)).getRadius();
        });
        assertEquals("系列内封面卡应为 radius_sm", dp(8), r[0], TOLERANCE_PX);
        assertEquals("统计 hero 卡应为 radius_xl", dp(20), r[1], TOLERANCE_PX);
        assertEquals("书房年报卡应为 radius_xl", dp(20), r[2], TOLERANCE_PX);
        assertEquals("普通统计卡必须还是 16dp（层级差）", dp(16), r[3], TOLERANCE_PX);
    }
}
