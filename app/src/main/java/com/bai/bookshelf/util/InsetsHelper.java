package com.bai.bookshelf.util;

import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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
        final int baseH = baseHeight;

        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(v.getPaddingLeft(), basePadTop + bars.top, v.getPaddingRight(), v.getPaddingBottom());
            if (baseH > 0 && v.getLayoutParams() != null) {
                ViewGroup.LayoutParams lp = v.getLayoutParams();
                int want = baseH + bars.top;
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
