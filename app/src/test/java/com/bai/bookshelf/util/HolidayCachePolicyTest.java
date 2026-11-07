package com.bai.bookshelf.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 节假日缓存刷新策略：TTL / 失败退避 / 仅 Wi-Fi（纯 JVM）。 */
public class HolidayCachePolicyTest {

    private static final long NOW = 1_000_000_000_000L;

    @Test
    public void 非WiFi不自动请求() {
        assertFalse(HolidayCachePolicy.shouldRefresh(NOW, 0, 0, false));
    }

    @Test
    public void 三十天内不重复请求() {
        assertFalse(HolidayCachePolicy.shouldRefresh(
                NOW, NOW - HolidayCachePolicy.TTL_MS + 1, 0, true));
        assertTrue(HolidayCachePolicy.shouldRefresh(
                NOW, NOW - HolidayCachePolicy.TTL_MS, 0, true));
    }

    @Test
    public void 失败后六小时内不重试() {
        assertFalse(HolidayCachePolicy.shouldRefresh(
                NOW, 0, NOW - HolidayCachePolicy.BACKOFF_MS + 1, true));
        assertTrue(HolidayCachePolicy.shouldRefresh(
                NOW, 0, NOW - HolidayCachePolicy.BACKOFF_MS, true));
    }

    @Test
    public void 首次无缓存且无失败记录时可请求() {
        assertTrue(HolidayCachePolicy.shouldRefresh(NOW, 0, 0, true));
    }
}
