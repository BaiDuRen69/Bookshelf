package com.bai.bookshelf.util;

import android.content.Context;
import android.provider.Settings;
import android.view.animation.PathInterpolator;

/** 新动效统一从这份令牌取值；是否播放由系统的「动画程序时长缩放」决定 */
public final class Motion {

    /** 按钮文案、勾选、点状反馈 */
    public static final long FAST = 120L;
    /** Tab、chip、空态入场 */
    public static final long BASE = 180L;
    /** 页面 / 分区入场 */
    public static final long SLOW = 240L;
    /** 位移、缩放 */
    public static final PathInterpolator STANDARD = new PathInterpolator(0.2f, 0f, 0f, 1f);
    /** 内容出现 */
    public static final PathInterpolator DECEL = new PathInterpolator(0.05f, 0.7f, 0.1f, 1f);

    private Motion() {
    }

    /** 缩放到 0 时视为关闭；读不到设置按默认开启处理 */
    public static boolean enabled(Context context) {
        if (context == null) {
            return true;
        }
        try {
            String raw = Settings.Global.getString(context.getContentResolver(),
                    Settings.Global.ANIMATOR_DURATION_SCALE);
            if (raw == null || raw.isEmpty()) {
                return true;
            }
            return Float.parseFloat(raw) > 0f;
        } catch (RuntimeException e) {
            return true;
        }
    }
}
