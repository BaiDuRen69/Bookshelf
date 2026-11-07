package com.bai.bookshelf.util;

/**
 * 节假日年度表何时该重新请求（纯 JVM，可单测）。
 *
 * <p>只在 Wi-Fi 下自动请求；成功一次管 30 天；失败后 6 小时内不再打接口，
 * 避免弱网反复请求把电量和流量耗在这上面。
 */
public final class HolidayCachePolicy {

    public static final long TTL_MS = 30L * 24 * 60 * 60 * 1000;
    public static final long BACKOFF_MS = 6L * 60 * 60 * 1000;

    private HolidayCachePolicy() {
    }

    public static boolean shouldRefresh(long now, long fetchedAt,
                                        long failedAt, boolean wifi) {
        if (!wifi) {
            return false;
        }
        if (fetchedAt > 0 && now - fetchedAt < TTL_MS) {
            return false;
        }
        return failedAt <= 0 || now - failedAt >= BACKOFF_MS;
    }
}
