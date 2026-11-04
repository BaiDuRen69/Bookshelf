package com.bai.bookshelf.util;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.view.View;

import androidx.core.splashscreen.SplashScreen;
import androidx.core.splashscreen.SplashScreenViewProvider;

/**
 * 启动页退场（v5.8.32）：30 及以下自己淡出字标，31 及以上交给系统退场。
 *
 * <p>为什么要按版本分岔：core-splashscreen 1.0.1 在 API 31+ 走
 * {@code SplashScreenViewProvider$ViewImpl31}，它的 {@code getIconView()} 内部对平台
 * 视图做了非空断言（字节码里是 {@code Intrinsics.checkNotNull}）。平台
 * handOverSplashScreenView 之后该字段可能已经是 null，异常在 getter 里就抛了，
 * 调用方的 {@code if (icon != null)} 根本来不及生效，于是冷启动直接崩在监听器里
 * （Android 16 实测：Xiaomi 24122RKC7C）。31+ 不注册监听器即可，系统会自己收尾。
 *
 * <p>退场路径上任何异常都不允许冒到主线程：兜底 remove 并把次数记进
 * ui_prefs.splash_exit_failed，方便真机验收时直接读计数确认没在偷偷失败。
 */
public final class SplashExit {

    private static final String PREFS = "ui_prefs";
    private static final String KEY_FAILED = "splash_exit_failed";

    private SplashExit() {
    }

    /** 必须在 {@code super.onCreate()} 之前调用（compat 要求早于任何 setContentView） */
    public static void install(Activity activity) {
        try {
            SplashScreen splash = SplashScreen.installSplashScreen(activity);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                return;
            }
            splash.setOnExitAnimationListener(provider -> fadeOut(activity, provider));
        } catch (Throwable ignored) {
            // 启动页退场失败不该影响进入主界面
        }
    }

    private static void fadeOut(Activity activity, SplashScreenViewProvider provider) {
        try {
            View icon = provider.getIconView();
            if (icon != null) {
                icon.animate().alpha(0f).scaleX(1.08f).scaleY(1.08f)
                        .setDuration(180)
                        .withEndAction(provider::remove)
                        .start();
                return;
            }
            provider.remove();
        } catch (Throwable t) {
            provider.remove();
            countFailure(activity);
        }
    }

    /** 计数是「静默兜底有没有被触发」的唯一证据，所以 release 也要记 */
    private static void countFailure(Activity activity) {
        try {
            SharedPreferences p = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            p.edit().putInt(KEY_FAILED, p.getInt(KEY_FAILED, 0) + 1).apply();
        } catch (Throwable ignored) {
        }
    }
}
