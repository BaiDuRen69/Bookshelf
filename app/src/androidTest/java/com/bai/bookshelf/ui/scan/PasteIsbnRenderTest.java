package com.bai.bookshelf.ui.scan;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.ActivityPasteIsbnBinding;
import com.bai.bookshelf.util.ThemeManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileOutputStream;

/**
 * 粘贴 ISBN 页的离屏渲染（v5.8.16）：整页 inflate + 量一遍，确认主题属性、
 * 底栏与进度条都在位（这类页面曾在真机之外没人看过，属性写错只会到运行时炸）。
 * 只画到 Bitmap 存缓存，不起 Activity、不切前台。
 */
@RunWith(AndroidJUnit4.class)
public class PasteIsbnRenderTest {

    @Test
    public void 整页可离屏渲染() {
        Context ctx = ApplicationProvider.getApplicationContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                render(ctx);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void render(Context ctx) throws Exception {
        Context themed = new ContextThemeWrapper(ctx,
                ThemeManager.styleRes(ThemeManager.current(ctx)));
        ActivityPasteIsbnBinding b = ActivityPasteIsbnBinding.inflate(
                android.view.LayoutInflater.from(themed));

        int width = ctx.getResources().getDisplayMetrics().widthPixels;
        float density = ctx.getResources().getDisplayMetrics().density;
        int height = Math.round(720 * density);
        b.getRoot().measure(
                View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        b.getRoot().layout(0, 0, width, height);

        assertNotNull(b.toolbar.getNavigationIcon());
        assertEquals("粘贴 ISBN 清单", b.toolbar.getTitle().toString());
        assertEquals("解析并查询", b.btnQuery.getText().toString());
        assertEquals("保存 0 种", b.btnSave.getText().toString());
        assertFalse("没解析前保存键应禁用", b.btnSave.isEnabled());
        assertEquals("地点：未设置 ›", b.btnLocation.getText().toString());
        assertEquals(View.GONE, b.pbQuery.getVisibility());

        // 预览区塞一条占位行，验证行高与分隔线能正常量出来
        TextView placeholder = new TextView(themed);
        placeholder.setText("9787111544937 · 正在查询…");
        placeholder.setTextColor(ThemeManager.resolveColor(themed, R.attr.on_surface_variant));
        b.llItems.addView(placeholder, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Math.round(60 * density)));

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(ThemeManager.resolveColor(themed, android.R.attr.colorBackground));
        b.getRoot().draw(canvas);
        try (FileOutputStream out = new FileOutputStream(
                new File(ctx.getCacheDir(), "paste_isbn.png"))) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
        assertEquals(View.VISIBLE, b.bottomBar.getVisibility());
        assertEquals(height, b.bottomBar.getBottom());
    }
}
