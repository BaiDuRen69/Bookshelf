package com.bai.bookshelf.util;

import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.bai.bookshelf.R;

/**
 * 系统栏内边距处理（v4.18）。
 *
 * <p><b>背景：</b>本应用 targetSdk = 36，在 Android 15（API 35）及以上系统会
 * <b>强制开启 edge-to-edge</b>（内容画到状态栏/导航栏之下），这与 Android 14
 * 及更早版本「系统自动给窗口加状态栏留白」的行为不同。
 *
 * <p>v4.16 及以前，页面顶部用的是 {@code MaterialToolbar}，其标题栏自带
 * {@code fitsSystemWindows} 语义会自动避让状态栏，所以看不出问题；v4.17 为了
 * 压缩纵向空间把书架页顶部换成了自定义 {@code LinearLayout}，这一层自动让位
 * 能力随之丢失，导致搜索框直接顶到状态栏下面、被系统状态栏覆盖而点不到。
 *
 * <p><b>方案：</b>统一在这里显式处理系统栏 inset，避免每个页面各写一套：
 * <ul>
 *   <li>{@link #applyTopInset(View)}：给顶部栏加「状态栏高度」的顶部内边距，
 *       内容下移到状态栏下方；</li>
 *   <li>{@link #applyBottomInset(View)}：给底部栏加「导航栏高度」的底部内边距，
 *       内容上移到手势条上方。</li>
 * </ul>
 *
 * <p>之所以用「加内边距」而不是「加 margin」：顶部栏自身是主色背景，
 * 让它的高度延伸到状态栏之下、只把里面的内容往下压，视觉上状态栏与工具栏
 * 连成一片（这正是 v4.16 截图里的效果），既不遮挡也不难看。
 *
 * <p><b>兼容性：</b>在 Android 14 及更早版本，系统（DecorView）会先消费掉系统栏
 * inset，下发给页面的 inset 高度为 0，因此本工具在旧系统上等价于「不做任何
 * 处理」，不会造成二次留白。
 */
public final class InsetsHelper {

    /** 记录视图「加 inset 之前」的原始顶部内边距，避免重复调用时层层累加 */
    private static final int TAG_BASE_PAD_TOP = com.bai.bookshelf.R.id.insets_base_pad_top;
    /** 记录视图「加 inset 之前」的原始底部内边距 */
    private static final int TAG_BASE_PAD_BOTTOM = com.bai.bookshelf.R.id.insets_base_pad_bottom;
    /** 记录工具栏「加 inset 之前」的原始固定高度 */
    private static final int TAG_BASE_HEIGHT = com.bai.bookshelf.R.id.insets_base_height;
    /** 最近一次分发的顶部 inset：有的系统会先被 DecorView 消费，不能直接读 root insets */
    private static final int TAG_LAST_TOP = com.bai.bookshelf.R.id.insets_last_top;

    private InsetsHelper() {
    }

    /**
     * 给顶部栏加状态栏内边距，使其内容不被状态栏遮挡。
     *
     * <p>若该视图有<b>固定高度</b>（工具栏场景），会同时把高度加上状态栏高度，
     * 保证标题区本身的高度不变——否则只加内边距会把标题挤扁。
     *
     * <p>本方法幂等：重复调用不会让内边距/高度层层累加。
     */
    public static void applyTopInset(final View view) {
        if (view == null) {
            return;
        }
        // 记录原始值（只在首次调用时记录，后续调用沿用）
        if (view.getTag(TAG_BASE_PAD_TOP) == null) {
            view.setTag(TAG_BASE_PAD_TOP, view.getPaddingTop());
        }
        Integer baseHeight = (Integer) view.getTag(TAG_BASE_HEIGHT);
        if (baseHeight == null) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();
            baseHeight = (lp != null && lp.height > 0) ? lp.height : 0;
            view.setTag(TAG_BASE_HEIGHT, baseHeight);
        }
        final int basePadTop = (Integer) view.getTag(TAG_BASE_PAD_TOP);

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setTag(TAG_LAST_TOP, bars.top);
            v.setPadding(v.getPaddingLeft(), basePadTop + bars.top, v.getPaddingRight(), v.getPaddingBottom());
            // 基准高度每次从 tag 读：工具栏可能在动画中换成紧凑/常规两档
            Integer base = (Integer) v.getTag(TAG_BASE_HEIGHT);
            int baseHNow = base == null ? 0 : base;
            if (baseHNow > 0 && v.getLayoutParams() != null) {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                int want = baseHNow + bars.top;
                if (lp.height != want) {
                    lp.height = want;
                    v.setLayoutParams(lp);
                }
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /**
     * 让视图高度恰好等于状态栏 + 刘海高度（不改内边距，也不读原始高度）。
     *
     * <p>用于状态栏垫片。注意不能用 {@link #applyTopInset(View)} + wrap_content：
     * 空 View 在 FrameLayout 里拿到的是 AT_MOST 测量规格，{@code View.getDefaultSize}
     * 会直接返回父容器高度，垫片会铺满整屏。这里显式按 inset 设高，旧系统
     * （DecorView 已消费 inset）自然为 0。
     */
    public static void applyTopBand(final View view) {
        if (view == null) {
            return;
        }
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp != null && lp.height != bars.top) {
                lp.height = bars.top;
                v.setLayoutParams(lp);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /**
     * 更新顶部栏的「不含系统栏」基准高度（v5.9 打卡页页头 48↔40dp）。
     *
     * <p>动画过程由调用方直接改 {@code layoutParams.height}（不每帧 requestApplyInsets），
     * 动画结束调用本方法一次，让 inset 监听器按新基准重算「基准 + 状态栏」。
     */
    public static void setBaseHeight(View view, int basePx) {
        if (view == null) {
            return;
        }
        view.setTag(TAG_BASE_HEIGHT, Math.max(0, basePx));
        ViewCompat.requestApplyInsets(view);
    }

    /**
     * 最近一次实际分发给该视图的顶部 inset（px）；没分发过返回 0。
     *
     * <p>与 {@link #systemBars(View)} 的区别：后者读 root insets，在 DecorView 已经
     * 消费过状态栏的系统上会多算一段，导致折叠时工具栏反而变高。
     */
    public static int topInset(View view) {
        if (view == null) {
            return 0;
        }
        Object tag = view.getTag(TAG_LAST_TOP);
        return tag instanceof Integer ? Math.max(0, (Integer) tag) : 0;
    }

    /**
     * 给底部栏加导航栏（手势条/虚拟键）内边距，使内容不被系统导航栏遮挡。
     *
     * <p>本方法幂等：重复调用不会让内边距层层累加。
     */
    public static void applyBottomInset(final View view) {
        if (view == null) {
            return;
        }
        if (view.getTag(TAG_BASE_PAD_BOTTOM) == null) {
            view.setTag(TAG_BASE_PAD_BOTTOM, view.getPaddingBottom());
        }
        final int basePadBottom = (Integer) view.getTag(TAG_BASE_PAD_BOTTOM);

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    basePadBottom + bars.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /**
     * 底部按键栏：键盘弹出时用键盘高度、否则用导航栏高度（v4.28）。
     *
     * <p>Android 15（API 35）+ targetSdk 35 起强制 edge-to-edge，窗口不再随键盘
     * 自动缩放（adjustResize 失效），编辑页的固定底栏会被键盘盖住、保存按钮
     * 只剩上半截可点。这里取 systemBars 与 ime 的较大值，让底栏始终浮在键盘上方。
     */
    public static void applyBottomInsetWithIme(final View view) {
        if (view == null) {
            return;
        }
        if (view.getTag(TAG_BASE_PAD_BOTTOM) == null) {
            view.setTag(TAG_BASE_PAD_BOTTOM, view.getPaddingBottom());
        }
        final int basePadBottom = (Integer) view.getTag(TAG_BASE_PAD_BOTTOM);

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
            int bottom = Math.max(bars.bottom, ime.bottom);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                    basePadBottom + bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /**
     * 底部外边距 = dock_reserve + 导航栏 inset（v5.9.2）：给 FAB / 选择模式条用。
     *
     * <p>底部 dock（bottom_nav）已经在 {@link #applyBottomInset(View)} 里避开导航栏，
     * 但悬浮控件只按 {@code @dimen/dock_reserve} 上移。手势条/三键导航实际占 24–48dp，
     * 屏幕内导航的机型上按钮会被压住一截。基准值固定取 dimen 而不是当前外边距：
     * 同一个 View 每次重建都可能带着上一次算过的值。
     */
    public static void applyDockReserveMargin(final View view) {
        if (view == null || !(view.getLayoutParams() instanceof ViewGroup.MarginLayoutParams)) {
            return;
        }
        final int base = view.getResources().getDimensionPixelSize(R.dimen.dock_reserve);
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()).bottom;
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            if (lp.bottomMargin != base + bottom) {
                lp.bottomMargin = base + bottom;
                v.setLayoutParams(lp);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /**
     * 底部内边距 = dock_reserve + 导航栏 inset（v5.9.2）：给列表 / 年视图用。
     *
     * <p>与 {@link #applyDockReserveMargin(View)} 同理，只是列表靠 padding 留白
     * （配 {@code clipToPadding="false"}），保证最后一项能滚到 dock 上沿之上。
     */
    public static void applyDockReservePadding(final View view) {
        if (view == null) {
            return;
        }
        final int base = view.getResources().getDimensionPixelSize(R.dimen.dock_reserve);
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            int bottom = insets.getInsets(WindowInsetsCompat.Type.systemBars()
                    | WindowInsetsCompat.Type.displayCutout()).bottom;
            if (v.getPaddingBottom() != base + bottom) {
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(),
                        v.getPaddingRight(), base + bottom);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /**
     * 自定义全屏底部面板的窗口设置（v4.40）：系统栏透明 + 内容铺到状态栏/导航栏之下。
     *
     * <p>为什么需要：自定义 Dialog 的窗口默认会被系统栏切掉一块，导航栏区域
     * 还会被涂成黑色——面板铺到底部时与手势条之间就会露出一条黑边。
     * 面板内容自己在底部补 {@link #systemBars(View)} 的内边距，就能既贴底又不被手势条压住。
     */
    public static void makeSheetWindowFullScreen(Window window) {
        if (window == null) {
            return;
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false);
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    /**
     * 读取当前窗口的系统栏 inset（供需要自行计算的场景使用）。
     */
    public static Insets systemBars(View view) {
        if (view == null) {
            return Insets.NONE;
        }
        WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(view);
        if (insets == null) {
            return Insets.NONE;
        }
        return insets.getInsets(
                WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
    }

    /**
     * 递归查找布局中第一个带指定 id 的视图（用于 Activity 里给 Fragment 内部的头部栏加 inset）。
     */
    public static View findById(View root, int id) {
        if (root == null) {
            return null;
        }
        if (root.getId() == id) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                View found = findById(vg.getChildAt(i), id);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }
}
