package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.R;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 底部导航胶囊（v5.8.17 修）：胶囊淡入淡出只能动底色，图标与文字必须一直可见。
 * v5.8.7 起动的是整个 View 的 alpha，未选中的 Tab 整块淡没了——这里把这个不变量钉住。
 */
@RunWith(AndroidJUnit4.class)
public class NavPillTest {

    private LinearLayout pill;
    private ImageView icon;
    private TextView label;

    private void build(Context ctx) {
        Context themed = new ContextThemeWrapper(ctx,
                ThemeManager.styleRes(ThemeManager.current(ctx)));
        pill = new LinearLayout(themed);
        pill.setOrientation(LinearLayout.VERTICAL);
        icon = new ImageView(themed);
        label = new TextView(themed);
        label.setText("统计");
        pill.addView(icon);
        pill.addView(label);
    }

    /** 在测量/布局过的胶囊上跑一遍选中→未选中 */
    private void run(Context ctx) {
        build(ctx);
        pill.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        pill.layout(0, 0, 300, pill.getMeasuredHeight());

        NavPill.show(pill, 40f);
        assertEquals("选中时胶囊不该整体透明", 1f, pill.getAlpha(), 0.001f);
        assertEquals("选中时图标不该整体透明", 1f, icon.getAlpha(), 0.001f);
        assertEquals("选中时文字不该整体透明", 1f, label.getAlpha(), 0.001f);
        assertNotNull("选中应有胶囊底色", pill.getBackground());

        NavPill.hide(pill);
        assertEquals("未选中时图标必须仍然可见", 1f, pill.getAlpha(), 0.001f);
        assertEquals("未选中时文字必须仍然可见", 1f, label.getAlpha(), 0.001f);
    }

    @Test
    public void 切换只动底色不动内容() {
        Context ctx = ApplicationProvider.getApplicationContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> run(ctx));
    }

    @Test
    public void 底色淡出后摘掉背景() throws Exception {
        Context ctx = ApplicationProvider.getApplicationContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            build(ctx);
            NavPill.show(pill, 0f);
            NavPill.hide(pill);
        });
        // 让主线程把 90ms 的淡出动画跑完
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        Thread.sleep(300);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> assertNull("淡出结束应摘掉底色", pill.getBackground()));
    }
}
