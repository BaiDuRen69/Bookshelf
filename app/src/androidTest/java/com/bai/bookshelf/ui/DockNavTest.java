package com.bai.bookshelf.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
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

    /** 直接取 @dimen 的真实像素值：几何断言要跟着令牌走，不要写死数字 */
    private int px(int dimenRes) {
        // 用 round 而不是强转：平台解析尺寸是四舍五入的，26dp 在 2.75 密度下是 71.5px
        return Math.round(ApplicationProvider.getApplicationContext()
                .getResources().getDimension(dimenRes));
    }

    @Test
    public void dock是胶囊且指示器压在图标下面() {
        FrameLayout dock = root().findViewById(R.id.nav_dock);
        assertNotNull("dock 不见了", dock);
        assertEquals("dock 高度应取 @dimen/dock_h", px(R.dimen.dock_h),
                dock.getLayoutParams().height);
        GradientDrawable bg = (GradientDrawable) dock.getBackground();
        assertEquals("圆角必须等于高度的一半（真胶囊）",
                dock.getLayoutParams().height / 2f, bg.getCornerRadius(), 0.6f);
        assertEquals("指示器必须是 dock 的第一个子节点，否则会把图标盖住",
                R.id.nav_indicator, dock.getChildAt(0).getId());
        View indicator = dock.findViewById(R.id.nav_indicator);
        assertEquals(px(R.dimen.nav_indicator_size), indicator.getLayoutParams().width);
        assertEquals(px(R.dimen.nav_indicator_size), indicator.getLayoutParams().height);
        // 第二层才是三个 tab 的横排容器
        assertTrue("dock 的第二个子节点应是 tab 容器",
                dock.getChildAt(1) instanceof ViewGroup);
        assertEquals("三个 tab 数量不对", 3, ((ViewGroup) dock.getChildAt(1)).getChildCount());
    }

    @Test
    public void 水波贴合tab且不涂出胶囊端头() {
        FrameLayout dock = root().findViewById(R.id.nav_dock);
        for (int id : new int[]{R.id.tab_shelf, R.id.tab_stats, R.id.tab_mine}) {
            View tab = root().findViewById(id);
            Drawable fg = tab.getForeground();
            assertTrue("tab 的前景不是 RippleDrawable：" + fg, fg instanceof RippleDrawable);
            assertNotNull("ripple 少了 mask，水波就是方的",
                    ((RippleDrawable) fg).findDrawableByLayerId(android.R.id.mask));
            assertTrue("tab 必须可点可聚焦", tab.isClickable() && tab.isFocusable());
            assertEquals("tab 宽应取 @dimen/dock_item_w", px(R.dimen.dock_item_w),
                    tab.getLayoutParams().width);
            // mask 是椭圆，会被拉成宿主 View 的宽高比。上一代水波挂在 120×64dp 的 tab 上
            // （比例 0.53）灰印就顶到了胶囊端头外面——这里守形状比例，而不是守某个 id 存在。
            float ratio = dock.getLayoutParams().height / (float) tab.getLayoutParams().width;
            assertTrue("tab 宽高比 " + ratio + " 太扁，椭圆水波会贴到胶囊圆角外", ratio >= 0.75f);
            assertTrue("dock 左右内边距不足，最外侧水波会压在胶囊端头上",
                    dock.getPaddingLeft() >= px(R.dimen.dock_padding_h));
        }
    }

    @Test
    public void 内容铺到底且dock两侧点击不被吃掉() {
        View root = root();
        View container = root.findViewById(R.id.container);
        assertEquals("container 必须铺满整屏（内容要能滚到 dock 下面）",
                ViewGroup.LayoutParams.MATCH_PARENT, container.getLayoutParams().height);
        View bar = root.findViewById(R.id.bottom_nav);
        assertFalse("外层容器加了 clickable 会吃掉 dock 两侧空白处的点击", bar.isClickable());
        assertFalse("dock 本身不是按钮，不该拦点击",
                ((View) root.findViewById(R.id.nav_dock)).isClickable());
        assertEquals("bottom_nav 必须贴在底部", android.view.Gravity.BOTTOM,
                ((FrameLayout.LayoutParams) bar.getLayoutParams()).gravity
                        & android.view.Gravity.BOTTOM);
        View scrim = root.findViewById(R.id.nav_scrim);
        assertNotNull("少了底部渐隐遮罩", scrim);
        assertEquals(px(R.dimen.dock_scrim_h), scrim.getLayoutParams().height);
        ViewGroup decor = (ViewGroup) container.getParent();
        assertEquals("遮罩必须排在 container 之后、bottom_nav 之前（压在内容上、dock 下面）",
                R.id.nav_scrim, decor.getChildAt(1).getId());
        assertEquals(R.id.bottom_nav, decor.getChildAt(2).getId());
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
            assertEquals(px(R.dimen.nav_icon_size), icon.getLayoutParams().width);
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
    public void dock面色走dock_bg且夜读比卡面亮一档() {
        FrameLayout dock = root().findViewById(R.id.nav_dock);
        ColorStateList fill = ((GradientDrawable) dock.getBackground()).getColor();
        assertNotNull("dock 填充色取不到，断言失去意义", fill);
        Context themed = new ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
                ThemeManager.styleRes(ThemeManager.current(
                        ApplicationProvider.getApplicationContext())));
        assertEquals("dock 底色必须取 ?attr/dock_bg",
                ThemeManager.resolveColor(themed, R.attr.dock_bg), fill.getDefaultColor());
        assertTrue("?attr/dock_bg 解析成透明，说明有主题漏配这个属性",
                Color.alpha(fill.getDefaultColor()) != 0);
        // 夜读单独核一遍：dock 必须与炭黑卡面分得开（描边已不是分层手段）
        Context night = new ContextThemeWrapper(ApplicationProvider.getApplicationContext(),
                ThemeManager.styleRes(ThemeManager.THEME_NIGHT));
        assertEquals("夜读 dock 应指向 dock_surface_night", 0xFF221F1C,
                ThemeManager.resolveColor(night, R.attr.dock_bg));
        assertTrue("夜读 dock 与卡面同色，去掉描边后分不出层",
                ThemeManager.resolveColor(night, R.attr.dock_bg)
                        != ThemeManager.resolveColor(night, R.attr.surface));
    }

    @Test
    public void 底部锚定控件按dock_reserve避让() {
        // MarginLayoutParams 有个坑：只要写了 layout_margin，四个 layout_marginXxx 全被忽略。
        // fab_scan 当初就是「XML 里明明加了 marginBottom、真机仍压在 dock 上」，所以这里读运行时
        // 真实的 bottomMargin，而不是看 XML 有没有那行。
        Context ctx = ApplicationProvider.getApplicationContext();
        Context themed = new ContextThemeWrapper(ctx,
                ThemeManager.styleRes(ThemeManager.current(ctx)));
        View shelf = LayoutInflater.from(themed).inflate(R.layout.fragment_shelf, null);
        int reserve = px(R.dimen.dock_reserve);
        View fab = shelf.findViewById(R.id.fab_scan);
        assertEquals("FAB 的底部外边距没吃到 dock_reserve（可能被 layout_margin 覆盖了）",
                reserve, ((ViewGroup.MarginLayoutParams) fab.getLayoutParams()).bottomMargin);
        View bar = shelf.findViewById(R.id.selection_bar);
        assertEquals("选择模式条没按 dock_reserve 避让",
                reserve, ((ViewGroup.MarginLayoutParams) bar.getLayoutParams()).bottomMargin);
        View recycler = shelf.findViewById(R.id.recycler);
        assertEquals("列表底部留白不是 dock_reserve，最后一本会被 dock 压住",
                reserve, recycler.getPaddingBottom());
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
