package com.bai.bookshelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.ui.widget.ReadingWidgetProvider;
import com.bai.bookshelf.util.ThemeManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 小组件 RemoteViews 渲染（v5.8.14）：不依赖桌面宿主，
 * 直接 apply 到 FrameLayout，验证主题取色、Bitmap 进度条与到期行都能落屏。
 */
@RunWith(AndroidJUnit4.class)
public class ReadingWidgetRenderTest {

    private Context ctx() {
        return ApplicationProvider.getApplicationContext();
    }

    private ReadingWidgetProvider.Snapshot snapshot() {
        ReadingWidgetProvider.Snapshot s = new ReadingWidgetProvider.Snapshot();
        s.reading = "在读：测试之书";
        s.goal = "今年 5 / 24 种";
        s.progress = 20;
        s.overdue = "逾期 1 本";
        s.overdueVisible = true;
        s.due = "今日到期 2 本";
        s.dueVisible = true;
        return s;
    }

    @Test
    public void 小组件按主题渲染颜色与进度() {
        Context ctx = ctx();
        int original = ThemeManager.current(ctx);
        try {
            for (int theme : new int[]{ThemeManager.THEME_GREEN, ThemeManager.THEME_BLUE,
                    ThemeManager.THEME_NIGHT}) {
                ThemeManager.set(ctx, theme);
                View root = ReadingWidgetProvider.build(ctx, snapshot())
                        .apply(ctx, new FrameLayout(ctx));

                TextView reading = root.findViewById(R.id.widget_reading);
                assertEquals("在读：测试之书", reading.getText().toString());
                Context themed = new ContextThemeWrapper(ctx, ThemeManager.styleRes(theme));
                assertEquals(ThemeManager.resolveColor(themed, R.attr.on_surface),
                        reading.getCurrentTextColor());

                TextView percent = root.findViewById(R.id.widget_percent);
                assertEquals("20%", percent.getText().toString());

                ImageView bar = root.findViewById(R.id.widget_progress);
                Drawable drawable = bar.getDrawable();
                assertTrue("进度条应是 BitmapDrawable", drawable instanceof BitmapDrawable);
                assertEquals(Math.round(4 * ctx.getResources().getDisplayMetrics().density),
                        bar.getLayoutParams().height);

                assertEquals(View.VISIBLE, root.findViewById(R.id.widget_due).getVisibility());
                assertEquals(View.VISIBLE,
                        root.findViewById(R.id.widget_due_today).getVisibility());
                assertNotNull(root.getBackground());
                Drawable background = root.getBackground();
                assertTrue(background instanceof GradientDrawable);
                int expectedBg;
                if (theme == ThemeManager.THEME_NIGHT) {
                    expectedBg = ctx.getColor(R.color.night_surface);
                } else if (theme == ThemeManager.THEME_BLUE) {
                    expectedBg = ctx.getColor(R.color.blue_surface);
                } else {
                    expectedBg = ctx.getColor(R.color.green_surface);
                }
                assertEquals(expectedBg,
                        ((GradientDrawable) background).getColor().getDefaultColor());

                if (theme == ThemeManager.THEME_GREEN) {
                    saveRender(ctx, root);
                }
            }
        } finally {
            ThemeManager.set(ctx, original);
        }
    }

    /** 离屏画一张 2×2 小组件，测试后 run-as 拉出来目检（不碰桌面、不切前台） */
    private void saveRender(Context ctx, View root) {
        int size = 420;
        root.measure(View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(size, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(bitmap));
        try (FileOutputStream out = new FileOutputStream(
                new File(ctx.getCacheDir(), "widget_render.png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        } catch (Exception ignored) {
        }
    }
}
