package com.bai.bookshelf.ui.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.view.ContextThemeWrapper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.ThemeManager;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 分段控件的「内缩」选中块与水波（v5.8.27）。
 *
 * <p>要守住的观感是「水波边界与选中白框完全重合」，两者靠同一组 6dp inset 实现。
 * 这里不读 XML，而是把 drawable 真画进 Bitmap 量出被着色的矩形——
 * 顺带证明「{@code <inset>} 直接套 {@code <shape>} / {@code <ripple>}」这种嵌套写法
 * 在运行时确实能解析（写成属性形式才保险，但嵌套更短）。
 */
@RunWith(AndroidJUnit4.class)
public class SegTabRippleTest {

    private Context themed() {
        Context ctx = ApplicationProvider.getApplicationContext();
        return new ContextThemeWrapper(ctx,
                ThemeManager.styleRes(ThemeManager.current(ctx)));
    }

    private float dp(float v) {
        return v * ApplicationProvider.getApplicationContext().getResources()
                .getDisplayMetrics().density;
    }

    /**
     * inset 走的是 getDimensionPixelOffset（**截断**），不是 PixelSize（四舍五入）：
     * 6dp 在 2.75 密度下是 16.5px，框架给 16，测试按 17 断言就会假失败。
     */
    private int insetPx(float v) {
        return (int) dp(v);
    }

    /** 画进画布后，被着色区域的外接矩形 */
    private Rect paintedBounds(int res, int w, int h) {
        Drawable d = themed().getDrawable(res);
        assertNotNull("drawable 加载失败：" + res, d);
        Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        d.setBounds(0, 0, w, h);
        d.draw(c);
        int left = w;
        int top = h;
        int right = -1;
        int bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (Color.alpha(bmp.getPixel(x, y)) > 8) {
                    left = Math.min(left, x);
                    top = Math.min(top, y);
                    right = Math.max(right, x);
                    bottom = Math.max(bottom, y);
                }
            }
        }
        assertTrue("什么都没画出来，inset 版 drawable 是空的", right >= 0);
        return new Rect(left, top, right, bottom);
    }

    @Test
    public void 统计页选中块内缩6dp() {
        int inset = insetPx(6);
        int w = insetPx(80);
        int h = insetPx(48);
        Rect r = paintedBounds(R.drawable.bg_seg_selected_inset, w, h);
        assertEquals("左边内缩应为 6dp", inset, r.left);
        assertEquals("上边内缩应为 6dp", inset, r.top);
        // 胶囊高 = 格高 − 上下各 6dp；像素取整允许 ±1
        assertEquals("格高 48dp − 上下各 6dp = 36dp 胶囊",
                h - 2 * inset, r.height(), 1);
        assertEquals("右边内缩应为 6dp", w - inset, r.right + 1, 1);
    }

    @Test
    public void 购书统计页选中块同尺寸内缩() {
        int inset = insetPx(6);
        int h = insetPx(40);
        Rect r = paintedBounds(R.drawable.bg_seg_selected_inset_sm, insetPx(80), h);
        assertEquals(inset, r.left);
        assertEquals(inset, r.top);
        assertEquals("格高 40dp − 上下各 6dp = 28dp 胶囊",
                h - 2 * inset, r.height(), 1);
    }

    @Test
    public void 水波与选中框用同一组inset() {
        // 水波本身不带按下状态时画不出东西，这里只验「能解析成 inset 且四边内缩一致」
        Drawable ripple = themed().getDrawable(R.drawable.ripple_seg_tab);
        assertTrue("ripple_seg_tab 不是 InsetDrawable，说明 <inset> 套 <ripple> 没被解析："
                        + ripple, ripple instanceof android.graphics.drawable.InsetDrawable);
        Rect pad = new Rect();
        ripple.getPadding(pad);
        int inset = insetPx(6);
        assertEquals("水波与选中框的 inset 必须一致才会重合", inset, pad.left);
        assertEquals(inset, pad.top);
        assertEquals(inset, pad.right);
        assertEquals(inset, pad.bottom);

        Drawable small = themed().getDrawable(R.drawable.ripple_seg_tab_sm);
        assertTrue("ripple_seg_tab_sm 不是 InsetDrawable：" + small,
                small instanceof android.graphics.drawable.InsetDrawable);
        small.getPadding(pad);
        assertEquals(inset, pad.left);
    }
}
