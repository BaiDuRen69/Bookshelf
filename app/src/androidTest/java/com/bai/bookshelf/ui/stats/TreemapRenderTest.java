package com.bai.bookshelf.ui.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;

import androidx.core.graphics.ColorUtils;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.StatsHoldings;
import com.bai.bookshelf.util.ThemeManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** 树状图配色、布局与图例（v5.8.15）：相邻不撞色、夜读暗金梯度、每块都画在框内 */
@RunWith(AndroidJUnit4.class)
public class TreemapRenderTest {

    private static final String[] NAMES =
            {"文学", "历史", "计算机", "小说", "经济", "哲学", "艺术", "其他"};
    private static final int[] SIZES = {12, 9, 7, 5, 4, 3, 2, 2};

    private List<StatsHoldings.Group> groups(int count) {
        List<StatsHoldings.Group> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            StatsHoldings.Group g = new StatsHoldings.Group(NAMES[i]);
            g.titles = SIZES[i];
            g.copies = SIZES[i];
            g.readDone = SIZES[i] / 2;
            list.add(g);
        }
        return list;
    }

    private CategoryTreemapView newView(Context ctx, int theme, int count) {
        CategoryTreemapView view = new CategoryTreemapView(
                new ContextThemeWrapper(ctx, ThemeManager.styleRes(theme)));
        view.setData(groups(count));
        return view;
    }

    /** RGB 三通道差之和：>40 肉眼才分得出两块 */
    private static int distance(int a, int b) {
        return Math.abs(((a >> 16) & 0xFF) - ((b >> 16) & 0xFF))
                + Math.abs(((a >> 8) & 0xFF) - ((b >> 8) & 0xFF))
                + Math.abs((a & 0xFF) - (b & 0xFF));
    }

    private static double contrast(int a, int b) {
        double la = ColorUtils.calculateLuminance(a);
        double lb = ColorUtils.calculateLuminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** 每块上的标签至少 3:1，暗金第一块那种中亮度底色不能写白字 */
    private static void assertLabelsReadable(CategoryTreemapView view, int count) {
        for (int i = 0; i < count; i++) {
            int fill = view.colorAt(i);
            double ratio = contrast(fill, view.labelColorFor(fill));
            assertTrue("第 " + i + " 块标签对比度只有 " + String.format(
                    java.util.Locale.ROOT, "%.2f", ratio), ratio >= 3.0);
        }
    }

    private static boolean hasColor(Bitmap bitmap, int color) {
        for (int y = 0; y < bitmap.getHeight(); y += 4) {
            for (int x = 0; x < bitmap.getWidth(); x += 4) {
                if (bitmap.getPixel(x, y) == color) {
                    return true;
                }
            }
        }
        return false;
    }

    @Test
    public void 配色图例与布局() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        int original = ThemeManager.current(ctx);
        try {
            // ---- 亮色：色板顺序分配 + 每块都画得下 ----
            ThemeManager.set(ctx, ThemeManager.THEME_GREEN);
            CategoryTreemapView light = newView(ctx, ThemeManager.THEME_GREEN, 6);
            for (int i = 1; i < 6; i++) {
                assertNotEquals("亮色主题第 " + i + " 块与上一块撞色",
                        light.colorAt(i - 1), light.colorAt(i));
                assertTrue("亮色主题第 " + i + " 块与上一块差得太小",
                        distance(light.colorAt(i - 1), light.colorAt(i)) >= 40);
            }
            LinearLayout legend = new LinearLayout(new ContextThemeWrapper(ctx,
                    ThemeManager.styleRes(ThemeManager.THEME_GREEN)));
            legend.setOrientation(LinearLayout.VERTICAL);
            TreemapLegend.fill(legend, groups(6), light::colorAt);
            assertEquals("6 类两列一行 = 3 行", 3, legend.getChildCount());
            assertEquals("图例应显示", View.VISIBLE, legend.getVisibility());

            Bitmap shot = save(ctx, light, legend, "treemap_light.png", ThemeManager.THEME_GREEN);
            int visible = 0;
            for (int i = 0; i < 6; i++) {
                if (hasColor(shot, light.colorAt(i))) {
                    visible++;
                }
            }
            assertEquals("6 类应全部画在框内", 6, visible);

            // 8 类时色板会轮回到第一个，仍与上一块不同
            CategoryTreemapView eight = newView(ctx, ThemeManager.THEME_GREEN, 8);
            assertNotEquals(eight.colorAt(5), eight.colorAt(6));
            assertNotEquals(eight.colorAt(6), eight.colorAt(7));

            // ---- 夜读：暗金梯度，逐块靠向炭黑底 ----
            ThemeManager.set(ctx, ThemeManager.THEME_NIGHT);
            CategoryTreemapView night = newView(ctx, ThemeManager.THEME_NIGHT, 8);
            for (int i = 1; i < 8; i++) {
                int a = night.colorAt(i - 1);
                int b = night.colorAt(i);
                assertNotEquals("夜读第 " + i + " 块与上一块同色", a, b);
                assertTrue("夜读第 " + i + " 块与上一块差得太小："
                                + Integer.toHexString(a) + " vs " + Integer.toHexString(b),
                        distance(a, b) >= 20);
            }
            int first = night.colorAt(0);
            assertTrue("夜读首块应是暗金（R>G>B）：" + Integer.toHexString(first),
                    ((first >> 16) & 0xFF) > ((first >> 8) & 0xFF)
                            && ((first >> 8) & 0xFF) > (first & 0xFF));
            assertTrue("夜读末块应已靠向炭黑底",
                    ((night.colorAt(7) >> 16) & 0xFF) < ((first >> 16) & 0xFF));
            assertLabelsReadable(light, 6);
            assertLabelsReadable(night, 8);
            save(ctx, night, null, "treemap_night.png", ThemeManager.THEME_NIGHT);
        } finally {
            ThemeManager.set(ctx, original);
        }
    }

    private Bitmap save(Context ctx, View treemap, LinearLayout legend,
                        String fileName, int theme) {
        float density = ctx.getResources().getDisplayMetrics().density;
        LinearLayout stack = new LinearLayout(new ContextThemeWrapper(ctx,
                ThemeManager.styleRes(theme)));
        stack.setOrientation(LinearLayout.VERTICAL);
        stack.setBackgroundColor(ctx.getColor(theme == ThemeManager.THEME_NIGHT
                ? R.color.night_surface : R.color.green_surface));
        int pad = Math.round(12 * density);
        stack.setPadding(pad, pad, pad, pad);
        stack.addView(treemap, new LinearLayout.LayoutParams(
                Math.round(340 * density), Math.round(200 * density)));
        if (legend != null) {
            stack.addView(legend, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        int width = Math.round(364 * density);
        stack.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        stack.layout(0, 0, width, stack.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(width, Math.max(1, stack.getMeasuredHeight()),
                Bitmap.Config.ARGB_8888);
        stack.draw(new Canvas(bitmap));
        try (FileOutputStream out = new FileOutputStream(new File(ctx.getCacheDir(), fileName))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception ignored) {
        }
        return bitmap;
    }
}
