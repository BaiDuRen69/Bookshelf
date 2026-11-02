package com.bai.bookshelf.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import android.content.Context;
import android.content.res.TypedArray;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.ThemeManager;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * 7 套主题的主色基线（v5.8.28 状态栏紫修复的护栏）。
 *
 * <p>这一轮往 Theme.Bookshelf.Base 里补了 colorPrimary / colorPrimaryVariant /
 * colorSecondary / colorOnPrimary 兜底。硬约束是「七个主题的 colorPrimary 不能变」
 * （按钮、数字、链接色都吃它）。改之前把每套主题解析出来的真实颜色钉在这里：
 * 兜底只应该改变「Base 自己被单独使用时」的结果（splash 的 postSplashScreenTheme
 * 曾指向 Base，就是紫条的来源），不应该改变任何一套具体主题。
 */
@RunWith(AndroidJUnit4.class)
public class ThemeColorBaselineTest {

    /** 主题 id → 该主题自己的 colorPrimary（取自 colors.xml 的 *_primary） */
    private static final int[][] PRIMARY_BASELINE = {
            {ThemeManager.THEME_GREEN, 0xFF2E6E5C},      // primary
            {ThemeManager.THEME_BLUE, 0xFF3A5BA0},       // blue_primary
            {ThemeManager.THEME_RED, 0xFFA34A3A},        // red_primary
            {ThemeManager.THEME_ORANGE, 0xFFC2701E},     // orange_primary
            {ThemeManager.THEME_PURPLE, 0xFF6B4E9E},     // purple_primary
            {ThemeManager.THEME_GRAPHITE, 0xFF4A6B70},   // graphite_primary
            {ThemeManager.THEME_NIGHT, 0xFFC9A66B},      // night_accent
    };

    private Context themed(int themeId) {
        Context ctx = ApplicationProvider.getApplicationContext();
        return new ContextThemeWrapper(ctx, ThemeManager.styleRes(themeId));
    }

    /** 解析 android:statusBarColor（Base 里写的是 ?attr/colorPrimary，会跟着主题走） */
    private int statusBarColor(Context ctx) {
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(android.R.attr.statusBarColor, tv, true);
        return tv.data;
    }

    /** 解析一个 android.* 之外的主题属性（nonTransitiveRClass 下库 attr 不在 app R 里） */
    private int resolveAttr(Context ctx, String name) {
        int attr = ctx.getResources().getIdentifier(name, "attr", ctx.getPackageName());
        assertTrueMsg(attr != 0, "找不到属性 " + name);
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(attr, tv, true);
        if (tv.type == TypedValue.TYPE_STRING) {
            return ctx.getResources().getColor(tv.resourceId, ctx.getTheme());
        }
        return tv.data;
    }

    private static void assertTrueMsg(boolean ok, String msg) {
        org.junit.Assert.assertTrue(msg, ok);
    }

    @Test
    public void 七套主题主色与基线一致() {
        for (int[] row : PRIMARY_BASELINE) {
            assertEquals("主题 " + row[0] + " 的 colorPrimary 被改动了",
                    row[1], ThemeManager.resolveColor(themed(row[0]),
                            ThemeManager.ATTR_COLOR_PRIMARY));
        }
    }

    @Test
    public void 每套主题的状态栏色等于自己的主色() {
        for (int[] row : PRIMARY_BASELINE) {
            assertEquals("主题 " + row[0] + " 的状态栏应与 colorPrimary 同色",
                    row[1], statusBarColor(themed(row[0])));
        }
    }

    @Test
    public void 七套主题的按钮文字色不被兜底改动() {
        for (int[] row : PRIMARY_BASELINE) {
            Context ctx = themed(row[0]);
            int onPrimary = resolveAttr(ctx, "colorOnPrimary");
            System.out.println("[onPrimary] theme=" + row[0]
                    + " argb=" + Integer.toHexString(onPrimary)
                    + " light=" + BaseActivity.isLightColor(onPrimary));
            // 夜读的 onPrimary 是深墨 night_ink，其余六套是白：兜底不能把任何一套改成紫色系
            if (row[0] == ThemeManager.THEME_NIGHT) {
                assertEquals(0xFF1A1510, onPrimary);
            } else {
                assertEquals("主题 " + row[0] + " 的 colorOnPrimary 被兜底改动了",
                        0xFFFFFFFF, onPrimary);
            }
        }
    }

    @Test
    public void 基线主题不再解析出Material紫() {
        Context base = new ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(), R.style.Theme_Bookshelf_Base);
        int purple = 0xFF6750A4;
        assertNotEquals("Base 缺 colorPrimary 兜底时，状态栏会解析成 Material 基线紫",
                purple, statusBarColor(base));
        assertNotEquals(purple,
                ThemeManager.resolveColor(base, ThemeManager.ATTR_COLOR_PRIMARY));
        assertEquals("Base 兜底主色应取墨绿 primary", 0xFF2E6E5C,
                ThemeManager.resolveColor(base, ThemeManager.ATTR_COLOR_PRIMARY));
        assertEquals("Base 兜底 colorOnPrimary 应为白", 0xFFFFFFFF,
                resolveAttr(base, "colorOnPrimary"));
        assertEquals("Base 兜底 colorSecondary 应为黄铜 accent", 0xFFC08A3E,
                ThemeManager.resolveColor(base, ThemeManager.ATTR_COLOR_SECONDARY));
    }

    /**
     * 启动主题同样不能解析出紫：31+ 它 parent 的是 Base（本轮补的兜底就是给它用的），
     * 31 以下 parent 的是 Theme.SplashScreen → android:Theme.DeviceDefault
     * （基线主色正是 #6750A4）。MainActivity 在 super.onCreate 之前就要
     * installSplashScreen()，那段时间窗口挂的是本主题，两条链都必须有主色。
     */
    @Test
    public void 启动主题也解析不出Material紫() {
        Context splash = new ContextThemeWrapper(
                ApplicationProvider.getApplicationContext(), R.style.Theme_Bookshelf_Splash);
        int purple = 0xFF6750A4;
        assertNotEquals("splash 主题没兜底时，installSplashScreen 期间取到系统基线紫",
                purple, ThemeManager.resolveColor(splash, ThemeManager.ATTR_COLOR_PRIMARY));
        assertEquals(0xFF2E6E5C,
                ThemeManager.resolveColor(splash, ThemeManager.ATTR_COLOR_PRIMARY));
        assertEquals("postSplashScreenTheme 必须指回具体主题（墨绿），不能指 Base",
                R.style.Theme_Bookshelf, splashThemeAfterPost(splash));
    }

    /** 解析 postSplashScreenTheme 指向的 style（splash 退场后 Activity 会挂上它） */
    private int splashThemeAfterPost(Context ctx) {
        int attr = ctx.getResources().getIdentifier(
                "postSplashScreenTheme", "attr", ctx.getPackageName());
        assertTrueMsg(attr != 0, "找不到属性 postSplashScreenTheme");
        TypedValue tv = new TypedValue();
        ctx.getTheme().resolveAttribute(attr, tv, true);
        // 指向 style 的属性 deflate 之后 id 落在 data 上（resourceId 只给未展开的引用）
        return tv.resourceId != 0 ? tv.resourceId : tv.data;
    }

    @Test
    public void 状态栏图标明暗按页头亮度决定() {
        // 七套主题的页头（= colorPrimary）相对亮度实测值：
        //   墨绿 .125 / 黛蓝 .108 / 赭红 .129 / 暖橙 .234 / 紫罗兰 .110 / 石墨 .132 / 夜读 .415
        // 阈值 0.55 之下全部判为「深底 → 白图标」，所以本轮不会有任何主题的图标翻色。
        for (int[] row : PRIMARY_BASELINE) {
            int header = ThemeManager.resolveColor(themed(row[0]),
                    ThemeManager.ATTR_COLOR_PRIMARY);
            assertEquals("主题 " + row[0] + " 页头不该被判成浅底",
                    false, BaseActivity.isLightColor(header));
        }
        // 算法本身要能识别浅底（第三步把夜读页头换成暗金底之后，若哪天页头真变浅，图标要跟着翻）
        assertEquals(true, BaseActivity.isLightColor(0xFFFFFFFF));
        assertEquals(true, BaseActivity.isLightColor(0xFFF5F0E6));
        assertEquals(false, BaseActivity.isLightColor(0xFF243D36));   // night_primary_dark
        // 夜读亮金页头配白图标只有约 2.3:1，靠第三步换暗页头解决（换完 Y≈0.036 → 12:1）
        System.out.println("[night] Y-based light=" + BaseActivity.isLightColor(0xFFC9A66B)
                + "（阈值 0.55，夜读亮金 0.415 仍判深底）");
    }

    /**
     * {@code ThemeManager.refreshWindowBackground} 靠 windowBackground 取页面底色，
     * 这里把它钉成各主题自己的 paper：夜读必须是炭黑 #151311，
     * 否则 splash 退场后页面留白会退回默认主题的米白（真机实测每行左右各约 33px）。
     */
    @Test
    public void 窗口背景等于各主题页面底色() {
        int[] paper = ThemeManager.paperColors();
        for (int[] row : PRIMARY_BASELINE) {
            android.util.TypedValue tv = new android.util.TypedValue();
            boolean ok = themed(row[0]).getTheme()
                    .resolveAttribute(android.R.attr.windowBackground, tv, true);
            assertTrueMsg(ok, "主题 " + row[0] + " 解析不到 windowBackground");
            assertEquals("主题 " + row[0] + " 的窗口背景不是自己的页面底色",
                    paper[row[0]], tv.data);
        }
    }
}
