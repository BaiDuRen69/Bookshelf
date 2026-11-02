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

    /** 解析 android:statusBarColor（v5.8.29 起 Base 里写的是 ?attr/header_bg） */
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

    /** 各主题页头底色（v5.8.29 新增 header_bg）：六套浅色 = 各自主色，夜读 = 暗墨绿 */
    private static final int[] HEADER_BG_BASELINE = {
            0xFF2E6E5C,      // primary
            0xFF3A5BA0,      // blue_primary
            0xFFA34A3A,      // red_primary
            0xFFC2701E,      // orange_primary
            0xFF6B4E9E,      // purple_primary
            0xFF4A6B70,      // graphite_primary
            0xFF26302C,      // night_container（不再是 night_accent 亮金）
    };

    /** 各主题页头前景（header_fg）：六套浅色仍是白，夜读换暗金 */
    private static final int[] HEADER_FG_BASELINE = {
            0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF, 0xFFFFFFFF,
            0xFFD8B77E,  // night_accent_text
    };

    @Test
    public void 每套主题的状态栏色等于自己的页头底色() {
        for (int[] row : PRIMARY_BASELINE) {
            assertEquals("主题 " + row[0] + " 的状态栏应与 header_bg 同色",
                    HEADER_BG_BASELINE[row[0]], statusBarColor(themed(row[0])));
        }
    }

    /**
     * 页头这一层单独成对之后，两个约束都要钉住：
     * ① header_bg / header_fg 解析值等于设计意图；
     * ② 页头文字对比度——夜读是本轮要修的那个（≥4.5:1），六套浅色主题按
     *    「观感与改动前一致」的约束不能动配色，只能守住 WCAG 大字底线 3.0:1。
     *    实测暖橙白字压 #C2701E 只有 3.73:1，是本轮之前就存在的值，改它不属本批。
     * colorPrimary 不参与这两条——它仍是按钮/链接/大数字的取色点。
     */
    @Test
    public void 页头底色与前景色成对且对比达标() {
        for (int[] row : PRIMARY_BASELINE) {
            Context ctx = themed(row[0]);
            int bg = ThemeManager.resolveColor(ctx, R.attr.header_bg);
            int fg = ThemeManager.resolveColor(ctx, R.attr.header_fg);
            assertEquals("主题 " + row[0] + " 的 header_bg 变了", HEADER_BG_BASELINE[row[0]], bg);
            assertEquals("主题 " + row[0] + " 的 header_fg 变了", HEADER_FG_BASELINE[row[0]], fg);
            double ratio = contrast(bg, fg);
            boolean night = row[0] == ThemeManager.THEME_NIGHT;
            System.out.println(String.format("[header] theme=%d bg=#%06X fg=#%06X 对比 %.2f:1 (%s)",
                    row[0], bg & 0xFFFFFF, fg & 0xFFFFFF, ratio, night ? "夜读，门槛 4.5" : "浅色，门槛 3.0"));
            assertTrueMsg(ratio >= (night ? 4.5 : 3.0), "主题 " + row[0] + " 页头文字对比只有 "
                    + String.format("%.2f", ratio) + ":1");
        }
    }

    /** 相对亮度（WCAG）与对比度，和 BaseActivity.isLightColor 用同一套换算 */
    private static double luminance(int color) {
        double[] c = new double[3];
        for (int i = 0; i < 3; i++) {
            double v = ((color >> (16 - 8 * i)) & 0xFF) / 255.0;
            c[i] = v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
        }
        return 0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2];
    }

    private static double contrast(int a, int b) {
        double la = luminance(a);
        double lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
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
            int header = ThemeManager.resolveColor(themed(row[0]), R.attr.header_bg);
            assertEquals("主题 " + row[0] + " 页头不该被判成浅底",
                    false, BaseActivity.isLightColor(header));
        }
        // 算法本身要能识别浅底（第三步把夜读页头换成暗金底之后，若哪天页头真变浅，图标要跟着翻）
        assertEquals(true, BaseActivity.isLightColor(0xFFFFFFFF));
        assertEquals(true, BaseActivity.isLightColor(0xFFF5F0E6));
        assertEquals(false, BaseActivity.isLightColor(0xFF243D36));   // night_primary_dark
        // 夜读页头已从亮金 #C9A66B（Y 0.415）换成暗墨绿 #26302C（Y 0.027）→ 白图标 13.6:1
        System.out.println("[night] header_bg light="
                + BaseActivity.isLightColor(HEADER_BG_BASELINE[ThemeManager.THEME_NIGHT])
                + "，白图标对比 " + String.format("%.2f",
                        contrast(HEADER_BG_BASELINE[ThemeManager.THEME_NIGHT], 0xFFFFFFFF)) + ":1");
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
