package com.bai.bookshelf.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.ThemeManager;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

/**
 * 底部导航浮动 dock（v5.8.25）：几何、层级、水波形状、图标着色全部按 inflate 后的真实值断言。
 *
 * <p>重点是水波——方形水波的根因是「可点元素用了不带 mask 的 selectableItemBackground」，
 * 光看代码改没改不够，得确认 foreground 是带圆形 mask 的 RippleDrawable。
 */
@RunWith(AndroidJUnit4.class)
public class DockNavTest {

    private View root;

    private View root() {
        if (root == null) {
            Context ctx = ApplicationProvider.getApplicationContext();
            final View[] out = new View[1];
            InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
                Context themed = new ContextThemeWrapper(ctx,
                        ThemeManager.styleRes(ThemeManager.current(ctx)));
                LayoutInflater inflater = LayoutInflater.from(themed).cloneInContext(themed);
                // 真机走 AppCompatActivity 的 inflater，ImageView 会被换成 AppCompatImageView，
                // app:tint 才有人读。测试里不换这一层，app:tint 会被静默忽略（踩过一次）。
                inflater.setFactory((name, context, attrs) ->
                        "ImageView".equals(name) ? new AppCompatImageView(context, attrs) : null);
                out[0] = inflater.inflate(R.layout.activity_main, null);
            });
            root = out[0];
        }
        return root;
    }

    private float dp(float value) {
        Context ctx = ApplicationProvider.getApplicationContext();
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                ctx.getResources().getDisplayMetrics());
    }

    @Test
    public void dock是胶囊且指示器压在图标下面() {
        FrameLayout dock = root().findViewById(R.id.nav_dock);
        assertNotNull("dock 不见了", dock);
        assertEquals("dock 高度应为 dock_h", (int) dp(64), dock.getLayoutParams().height);
        GradientDrawable bg = (GradientDrawable) dock.getBackground();
        assertEquals("dock 圆角应等于高度的一半（真胶囊）", dp(32), bg.getCornerRadius(), 0.6f);
        assertEquals("指示器必须是 dock 的第一个子节点，否则会把图标盖住",
                R.id.nav_indicator, dock.getChildAt(0).getId());
        View indicator = dock.findViewById(R.id.nav_indicator);
        assertEquals((int) dp(44), indicator.getLayoutParams().width);
        assertEquals((int) dp(44), indicator.getLayoutParams().height);
        // 第二层才是三个 tab 的横排容器
        assertTrue("dock 的第二个子节点应是 tab 容器",
                dock.getChildAt(1) instanceof ViewGroup);
        assertEquals("三个 tab 数量不对", 3, ((ViewGroup) dock.getChildAt(1)).getChildCount());
    }

    @Test
    public void 水波是圆形且不溢出胶囊() {
        int[][] pairs = {{R.id.tab_shelf, R.id.touch_shelf},
                {R.id.tab_stats, R.id.touch_stats}, {R.id.tab_mine, R.id.touch_mine}};
        for (int[] pair : pairs) {
            View tab = root().findViewById(pair[0]);
            View touch = root().findViewById(pair[1]);
            // 水波挂在 48dp 的居中层上：正方形边界 → 真圆，且离 dock 圆角还有 36dp
            assertNull("tab 上不该再有前景水波（会把灰印涂到胶囊外）", tab.getForeground());
            Drawable fg = touch.getForeground();
            assertTrue("内层触控块的前景不是 RippleDrawable：" + fg,
                    fg instanceof RippleDrawable);
            assertNotNull("ripple 少了 mask，水波就是方的",
                    ((RippleDrawable) fg).findDrawableByLayerId(android.R.id.mask));
            assertEquals("触控块应为 48dp 见方", (int) dp(48), touch.getWidth() > 0
                    ? touch.getWidth() : touch.getLayoutParams().width);
            assertEquals((int) dp(48), touch.getLayoutParams().height);
            assertTrue("tab 必须可点可聚焦", tab.isClickable() && tab.isFocusable());
        }
    }

    @Test
    public void 三个tab纯图标且图标有朗读标签() {
        int[] tabs = {R.id.tab_shelf, R.id.tab_stats, R.id.tab_mine};
        for (int id : tabs) {
            View tab = root().findViewById(id);
            assertNotNull(tab);
            List<TextView> texts = new ArrayList<>();
            List<ImageView> icons = new ArrayList<>();
            collect(tab, texts, icons);
            assertEquals("纯图标：tab 里不该再有文字标签（角标除外）", 1, texts.size());
            assertEquals(1, icons.size());
            ImageView icon = icons.get(0);
            assertEquals((int) dp(24), icon.getLayoutParams().width);
            assertNotNull("图标丢了 contentDescription，TalkBack 念不出来",
                    icon.getContentDescription());
        }
    }

    @Test
    public void 图标着色走选中态选择器() {
        ImageView icon = root().findViewById(R.id.iv_tab_shelf);
        ColorStateList tint = icon.getImageTintList();
        assertNotNull("图标没挂 nav_icon_tint 选择器", tint);
        int selected = tint.getColorForState(new int[]{android.R.attr.state_selected}, 0);
        int normal = tint.getColorForState(new int[]{}, 0);
        assertTrue("选中与未选中同色，选择器没生效", selected != normal);
        Context themed = new ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
                ThemeManager.styleRes(ThemeManager.current(
                        ApplicationProvider.getApplicationContext())));
        assertEquals("选中色应取 colorPrimary",
                ThemeManager.resolveColor(themed, ThemeManager.ATTR_COLOR_PRIMARY), selected);
    }

    @Test
    public void 底栏容器与dock同色不能透窗底() {
        // 真机踩过的坑：容器改成 transparent 后，夜读下 dock 下方露出浅色 windowBackground，
        // 拉出一条奶油白带。容器必须和 dock 一样吃 ?attr/surface。
        View bar = root().findViewById(R.id.bottom_nav);
        FrameLayout dock = root().findViewById(R.id.nav_dock);
        Drawable barBg = bar.getBackground();
        assertTrue("bottom_nav 背景不是纯色：" + barBg, barBg instanceof ColorDrawable);
        int barColor = ((ColorDrawable) barBg).getColor();
        assertTrue("bottom_nav 背景透明，会露出 windowBackground", Color.alpha(barColor) != 0);
        ColorStateList dockFill = ((GradientDrawable) dock.getBackground()).getColor();
        assertNotNull("dock 填充色取不到，断言失去意义", dockFill);
        assertEquals("容器色必须等于 dock 填充色（surface），否则底栏会出现断层",
                dockFill.getDefaultColor(), barColor);
    }

    private void collect(View v, List<TextView> texts, List<ImageView> icons) {
        if (v instanceof TextView) {
            texts.add((TextView) v);
        } else if (v instanceof ImageView) {
            icons.add((ImageView) v);
        }
        if (v instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) v;
            for (int i = 0; i < group.getChildCount(); i++) {
                collect(group.getChildAt(i), texts, icons);
            }
        }
    }
}
