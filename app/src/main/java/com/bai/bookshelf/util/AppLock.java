package com.bai.bookshelf.util;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.biometric.BiometricManager;

/**
 * 应用锁（v4.54）：可选开关，用手机的指纹 / 面容或锁屏密码验证。
 *
 * <p>不保存额外的 PIN，避免「忘记应用密码」导致数据打不开：
 * 验证一律交给系统 Keyguard，应用只记录「本次前台会话是否已解锁」。
 *
 * <p>锁定规则：
 * <ul>
 *   <li>进程冷启动时若已开启，立即要求解锁；</li>
 *   <li>App 退到后台超过 {@link #GRACE_MS} 再回来，要求解锁；</li>
 *   <li>拍封面的系统相机 / 相册 / 文件选择器等短暂跳出，60 秒内回来不打扰。</li>
 * </ul>
 */
public final class AppLock {

    private static final String PREFS = "app_lock_prefs";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_LAST_BG = "last_background_at";

    public static final long GRACE_MS = 60_000L;

    /** 本次进程内是否已解锁（冷启动/后台超时后为 false） */
    private static boolean unlocked = false;
    /** 进程启动后还没经过任何一次前台检查 */
    private static boolean coldStart = true;

    private AppLock() {
    }

    /** Application.onCreate 调用：新进程一律从「锁定」开始 */
    public static void onProcessStart() {
        unlocked = false;
        coldStart = true;
    }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, false);
    }

    /** 开启/关闭应用锁；开启后本次会话不立即锁，离开超过 60 秒或下次冷启动生效 */
    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit()
                .putBoolean(KEY_ENABLED, enabled)
                .putLong(KEY_LAST_BG, 0)
                .apply();
        unlocked = true;
        coldStart = false;
    }

    /**
     * 每个 Activity.onStart 之前调用：处理「从后台回来是否超过免打扰时间」。
     * 处理完清掉后台时间戳，避免用户在应用内继续操作时被误锁。
     */
    public static void beforeActivityStart(Context ctx) {
        if (!isEnabled(ctx) || coldStart) {
            unlocked = false;
            prefs(ctx).edit().putLong(KEY_LAST_BG, 0).apply();
            return;
        }
        long last = prefs(ctx).getLong(KEY_LAST_BG, 0);
        if (last > 0) {
            if (System.currentTimeMillis() - last >= GRACE_MS) {
                unlocked = false;
            }
            prefs(ctx).edit().putLong(KEY_LAST_BG, 0).apply();
        }
    }

    /** App 的所有 Activity 都 stopped 时调用（用于记录后台起始时间） */
    public static void onAppBackgrounded(Context ctx) {
        if (!isEnabled(ctx)) {
            return;
        }
        prefs(ctx).edit().putLong(KEY_LAST_BG, System.currentTimeMillis()).apply();
    }

    public static boolean isLocked(Context ctx) {
        return isEnabled(ctx) && !unlocked;
    }

    public static void markUnlocked(Context ctx) {
        unlocked = true;
        coldStart = false;
        prefs(ctx).edit().putLong(KEY_LAST_BG, 0).apply();
    }

    /** 手机是否设置了锁屏密码 / 图案（Keyguard 兜底） */
    public static boolean hasDeviceCredential(Context ctx) {
        KeyguardManager km = (KeyguardManager) ctx.getSystemService(Context.KEYGUARD_SERVICE);
        return km != null && km.isDeviceSecure();
    }

    /** 是否录入了可用的指纹 / 面容 */
    public static boolean hasBiometric(Context ctx) {
        try {
            return BiometricManager.from(ctx)
                    .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                    == BiometricManager.BIOMETRIC_SUCCESS;
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean canAuthenticate(Context ctx) {
        return hasBiometric(ctx) || hasDeviceCredential(ctx);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
