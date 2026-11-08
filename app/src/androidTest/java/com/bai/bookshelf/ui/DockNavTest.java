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
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.ActivityMainBinding;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.NavOrder;
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
        // 第二层是四个 tab 的横排容器（v5.10 打卡插在第 3 格）
        assertTrue("dock 的第二个子节点应是 tab 容器",
                dock.getChildAt(1) instanceof ViewGroup);
        assertEquals("四个 tab 数量不对", 4, ((ViewGroup) dock.getChildAt(1)).getChildCount());
        assertEquals("四格等分要求 dock 本身铺满宽度",
                ViewGroup.LayoutParams.MATCH_PARENT, dock.getLayoutParams().width);
    }

    @Test
    public void 水波贴合tab且不涂出胶囊端头() {
        FrameLayout dock = root().findViewById(R.id.nav_dock);
        for (int id : new int[]{R.id.tab_shelf, R.id.tab_stats,
                R.id.tab_checkin, R.id.tab_mine}) {
            View tab = root().findViewById(id);
            Drawable fg = tab.getForeground();
            assertTrue("tab 的前景不是 RippleDrawable：" + fg, fg instanceof RippleDrawable);
            assertNotNull("ripple 少了 mask，水波就是方的",
                    ((RippleDrawable) fg).findDrawableByLayerId(android.R.id.mask));
            assertTrue("tab 必须可点可聚焦", tab.isClickable() && tab.isFocusable());
            // v5.10：固定 84dp×4 会超出 360dp 屏的可用宽度，改 0dp + weight=1 等分
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tab.getLayoutParams();
            assertEquals("tab 宽应为 0dp（交给 weight 等分）", 0, lp.width);
            assertEquals("tab 必须 weight=1 等分", 1f, lp.weight, 0.001f);
            // v5.10：固定 84dp 时代用「宽高比 ≥ 0.75」守椭圆水波形状；等分 4 格后
            // 每格宽度随屏宽变化（360dp 屏约 86dp、更宽的屏更大），这个比值不再恒定。
            // 改守真正不会退化的约束：每格不得窄于 56dp 触摸目标；水波 mask 由
            // ripple_circle 自己裁剪到 View 边界，不会画到胶囊圆角外。
            int screenW = ApplicationProvider.getApplicationContext()
                    .getResources().getDisplayMetrics().widthPixels;
            int perTab = (screenW - dock.getPaddingLeft() - dock.getPaddingRight()) / 4;
            assertTrue("等分后每格不能窄于触摸目标，实测 " + perTab + "px",
                    perTab >= px(R.dimen.nav_touch_size));
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
    public void 四个tab纯图标且图标有朗读标签() {
        int[] tabs = {R.id.tab_shelf, R.id.tab_stats, R.id.tab_checkin, R.id.tab_mine};
        for (int id : tabs) {
            View tab = root().findViewById(id);
            assertNotNull(tab);
            List<TextView> texts = new ArrayList<>();
            List<ImageView> icons = new ArrayList<>();
            collect(tab, texts, icons);
            assertTrue("纯图标：tab 里最多只有角标一个文字（打卡格没有角标）",
                    texts.size() <= 1);
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

    @Test
    public void 底部锚定控件的避让会叠加导航栏高度() {
        // 不启动 Activity（应用锁 + recreate 会让测试挂起），直接给真实 View 树分发
        // 一份带底部 inset 的 WindowInsets，验证 dock_reserve 之上确实再加了导航栏高度。
        Context ctx = ApplicationProvider.getApplicationContext();
        Context themed = new ContextThemeWrapper(ctx,
                ThemeManager.styleRes(ThemeManager.current(ctx)));
        View shelf = LayoutInflater.from(themed).inflate(R.layout.fragment_shelf, null);
        View fab = shelf.findViewById(R.id.fab_scan);
        View bar = shelf.findViewById(R.id.selection_bar);
        View recycler = shelf.findViewById(R.id.recycler);
        final int extra = px(R.dimen.nav_touch_size);
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            // 与 ShelfFragment.onViewCreated 的接线保持一致
            InsetsHelper.applyDockReserveMargin(fab);
            InsetsHelper.applyDockReserveMargin(bar);
            InsetsHelper.applyDockReservePadding(recycler);
            WindowInsetsCompat insets = new WindowInsetsCompat.Builder()
                    .setInsets(WindowInsetsCompat.Type.systemBars(),
                            Insets.of(0, 0, 0, extra))
                    .build();
            ViewCompat.dispatchApplyWindowInsets(shelf, insets);
        });
        int reserve = px(R.dimen.dock_reserve);
        assertEquals("FAB 要叠加导航栏 inset", reserve + extra,
                ((ViewGroup.MarginLayoutParams) fab.getLayoutParams()).bottomMargin);
        assertEquals("选择模式条要叠加导航栏 inset", reserve + extra,
                ((ViewGroup.MarginLayoutParams) bar.getLayoutParams()).bottomMargin);
        assertEquals("列表底部留白要叠加导航栏 inset", reserve + extra,
                recycler.getPaddingBottom());
    }

    @Test
    public void 隐藏统计后其余三格定宽且dock收成内容宽() {
        View root = root();
        ActivityMainBinding binding = ActivityMainBinding.bind(root);
        MainActivity.layoutTabs(binding, NavOrder.DEFAULT,
                new java.util.HashSet<>(java.util.Collections.singletonList(NavOrder.TAB_STATS)));

        assertEquals("统计格应隐藏", View.GONE, root.findViewById(R.id.tab_stats).getVisibility());
        int slot = px(R.dimen.nav_slot_w);
        for (int id : new int[]{R.id.tab_shelf, R.id.tab_checkin, R.id.tab_mine}) {
            View tab = root.findViewById(id);
            assertEquals(View.VISIBLE, tab.getVisibility());
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) tab.getLayoutParams();
            assertEquals("不满 4 格时每格应定宽 nav_slot_w", slot, lp.width);
            assertEquals(0f, lp.weight, 0.001f);
        }
        View dock = root.findViewById(R.id.nav_dock);
        View items = root.findViewById(R.id.nav_items);
        assertEquals("只剩三格时 dock 不能铺满整条",
                ViewGroup.LayoutParams.WRAP_CONTENT, dock.getLayoutParams().width);
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, items.getLayoutParams().width);
        assertEquals("不满 4 格时整组居中", android.view.Gravity.CENTER,
                ((LinearLayout) items).getGravity());
    }

    @Test
    public void 自定义顺序按配置重排且四格仍等分() {
        View root = root();
        ActivityMainBinding binding = ActivityMainBinding.bind(root);
        MainActivity.layoutTabs(binding, new int[]{NavOrder.TAB_CHECKIN, NavOrder.TAB_SHELF,
                NavOrder.TAB_STATS, NavOrder.TAB_MINE}, new java.util.HashSet<>());

        LinearLayout items = root.findViewById(R.id.nav_items);
        assertEquals(4, items.getChildCount());
        assertEquals(R.id.tab_checkin, items.getChildAt(0).getId());
        assertEquals(R.id.tab_shelf, items.getChildAt(1).getId());
        assertEquals(R.id.tab_stats, items.getChildAt(2).getId());
        assertEquals(R.id.tab_mine, items.getChildAt(3).getId());

        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT,
                root.findViewById(R.id.nav_dock).getLayoutParams().width);
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) items.getChildAt(0)
                .getLayoutParams();
        assertEquals(0, lp.width);
        assertEquals(1f, lp.weight, 0.001f);
    }

    @Test
    public void 指示器落在可见格中心且隐藏页淡出() {
        View root = root();
        ActivityMainBinding binding = ActivityMainBinding.bind(root);
        MainActivity.layoutTabs(binding, NavOrder.DEFAULT,
                new java.util.HashSet<>(java.util.Collections.singletonList(NavOrder.TAB_STATS)));
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            int w = 1080;
            int h = 2400;
            root.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, w, h);
        });

        View items = root.findViewById(R.id.nav_items);
        View pill = root.findViewById(R.id.nav_indicator);
        View shelf = root.findViewById(R.id.tab_shelf);
        assertTrue("底栏没有真正布局出来", shelf.getWidth() > 0);

        MainActivity.placeIndicator(items, shelf, pill);
        assertEquals(1f, pill.getAlpha(), 0.001f);
        float targetCenter = items.getX() + shelf.getX() + shelf.getWidth() / 2f;
        float pillCenter = pill.getLeft() + pill.getTranslationX() + pill.getWidth() / 2f;
        assertEquals("指示器要落在格子的中心", targetCenter, pillCenter, 1f);

        MainActivity.placeIndicator(items, root.findViewById(R.id.tab_stats), pill);
        assertEquals("隐藏页不该假装有自己的位置", 0f, pill.getAlpha(), 0.001f);
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
