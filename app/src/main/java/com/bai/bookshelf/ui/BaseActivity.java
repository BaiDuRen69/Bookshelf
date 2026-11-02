package com.bai.bookshelf.ui;

import android.os.Bundle;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ThemeManager;

/**
 * 所有 Activity 的基类（v2.5 主题换肤）：
 * 在 super.onCreate 之前按用户选择 setTheme，保证 setContentView 及其后
 * inflate 的所有视图（含 Fragment）都跑在选定主题上。
 *
 * <p>v4.18：统一处理系统栏 inset。本应用 targetSdk = 36，在 Android 15+
 * 强制 edge-to-edge，内容会画到状态栏/导航栏之下；而顶部的 MaterialToolbar
 * 只有在自身被标记为 fitsSystemWindows 时才会自动避让。为避免逐页遗漏，
 * 这里在 onContentChanged 阶段统一给带 {@code @+id/toolbar} 的顶部栏
 * 加状态栏内边距。
 */
public class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
    }

    /**
     * v4.54：应用锁统一拦截。所有业务页面都继承本类，因此无论从最近任务、
     * 通知还是应用内跳转进入，都会先被锁屏页挡在前面。
     */
    @Override
    protected void onStart() {
        super.onStart();
        if (this instanceof com.bai.bookshelf.ui.lock.AppLockActivity) {
            return;
        }
        com.bai.bookshelf.util.AppLock.beforeActivityStart(this);
        if (com.bai.bookshelf.util.AppLock.isLocked(this)) {
            android.content.Intent intent = new android.content.Intent(this,
                    com.bai.bookshelf.ui.lock.AppLockActivity.class);
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP
                    | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
        }
    }

    @Override
    public void onContentChanged() {
        super.onContentChanged();
        ThemeManager.refreshWindowBackground(this);
        applyToolbarInset();
        applySystemBarColors();
        applyStatusBarIcons();
    }

    /**
     * v5.8.28：状态栏 / 导航栏颜色**在代码里按当前主题算**，不再只靠主题 XML 解析。
     *
     * <p>原因：core-splashscreen 在启动页退场时会把 Activity 主题重设成
     * {@code postSplashScreenTheme}，切主题走 {@code recreate()} 也会重跑这条链，
     * 于是 XML 里 {@code ?attr/colorPrimary} 有几率解析成基线主题的色（Material 紫
     * #6750A4），表现为「状态栏紫一条」。这里在内容变化后主动覆写一次，绕开缓存。
     *
     * <p>页头色目前取 colorPrimary（第三步引入 {@code ?attr/header_bg} 后换成它）。
     */
    private void applySystemBarColors() {
        try {
            int header = ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY);
            getWindow().setStatusBarColor(header);
            getWindow().setNavigationBarColor(ThemeManager.resolveColor(this, R.attr.surface));
        } catch (RuntimeException ignored) {
            // API 35+ 上系统会忽略 setStatusBarColor（强制 edge-to-edge），属正常，不能因此崩
        }
    }

    /**
     * 状态栏 / 导航栏图标的明暗**按各自底色亮度决定**。
     *
     * <p>原先写死白图标（页头在六套浅色主题下都是深主色，炭黑夜读也需要白字），
     * 但夜读的 colorPrimary 是亮金 #C9A66B：白字压在亮金上对比度只有约 1.9:1，
     * 暖橙 #C2701E 同样偏亮。现在统一走 {@link #isLightColor(int)}。
     */
    private void applyStatusBarIcons() {
        int header = ThemeManager.resolveColor(this, ThemeManager.ATTR_COLOR_PRIMARY);
        androidx.core.view.WindowInsetsControllerCompat controller =
                androidx.core.view.WindowCompat.getInsetsController(
                        getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(isLightColor(header));
        controller.setAppearanceLightNavigationBars(
                isLightColor(ThemeManager.resolveColor(this, R.attr.surface)));
    }

    /**
     * 相对亮度（WCAG 系数）>0.55 视为浅底，配深色图标。
     *
     * <p>包内可见是为了让 {@code ThemeColorBaselineTest} 直接对七套主题的页头色断言，
     * 不靠肉眼看截图。
     */
    static boolean isLightColor(int color) {
        double r = ((color >> 16) & 0xFF) / 255.0;
        double g = ((color >> 8) & 0xFF) / 255.0;
        double b = (color & 0xFF) / 255.0;
        return 0.2126 * Math.pow(r, 2.2) + 0.7152 * Math.pow(g, 2.2)
                + 0.0722 * Math.pow(b, 2.2) > 0.55;
    }

    /**
     * 给页面顶部栏加状态栏内边距，使标题不被状态栏遮挡。
     *
     * <p>v4.19 修复：原先只在 {@code android.R.id.content} 的<b>直接子层</b>里找
     * {@code R.id.toolbar}。但 content 的直接子层其实是 Activity 的根布局
     * （如 activity_book_edit.xml 的 LinearLayout），工具栏是它的<b>孙节点</b>，
     * 于是二级页的工具栏从来没被处理过——标题直接压在状态栏底下（实测
     * 「录入书籍」标题 y=32~94px，状态栏 0~63px，完全重叠）。
     *
     * <p>现在改为在 Activity 根布局里递归查找。
     * {@link InsetsHelper#applyTopInset(View)} 是幂等的（用 tag 记住原始内边距），
     * 即使 Fragment 自己也调过一次，重复调用也不会让内边距层层累加。
     */
    private void applyToolbarInset() {
        View root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        View toolbar = InsetsHelper.findById(root, R.id.toolbar);
        if (toolbar != null) {
            InsetsHelper.applyTopInset(toolbar);
        }
    }
}
