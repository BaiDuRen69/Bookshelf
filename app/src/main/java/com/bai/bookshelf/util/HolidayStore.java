package com.bai.bookshelf.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 节假日年度表：内存 → SharedPreferences → 离线兜底，Wi-Fi 下后台刷新。
 *
 * <p>不写数据库、不新增权限（INTERNET / ACCESS_NETWORK_STATE 已在 manifest）；
 * 同一年并发请求用 {@link #IN_FLIGHT} 合并，失败后由 {@link HolidayCachePolicy} 退避。
 */
public final class HolidayStore {

    public interface Callback {
        void onLoaded(int year, HolidayCalendar calendar);
    }

    private static final String PREFS = "holiday_cache";
    private static final String URL = "https://timor.tech/api/holiday/year/";
    private static final Map<Integer, HolidayCalendar> MEMORY = new ConcurrentHashMap<>();
    private static final Set<Integer> IN_FLIGHT = Collections.synchronizedSet(new HashSet<>());
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();

    private HolidayStore() {
    }

    /** 主线程可用：内存 → 兜底；不读盘、不联网 */
    public static HolidayCalendar get(Context ctx, int year) {
        HolidayCalendar c = MEMORY.get(year);
        if (c != null) {
            return c;
        }
        c = HolidayCalendar.ofFallback(HolidayCn.yearNames(
                year, new LunarCalendar(new LunarCalendarIcu())));
        MEMORY.put(year, c);
        return c;
    }

    /** io 线程用：内存 → 磁盘缓存 → 兜底 */
    public static HolidayCalendar load(Context ctx, int year) {
        HolidayCalendar c = MEMORY.get(year);
        if (c != null) {
            return c;
        }
        SharedPreferences p = prefs(ctx);
        String json = p.getString("json_" + year, null);
        if (json != null) {
            try {
                Map<Integer, String> map = HolidayJson.parseYear(json, year);
                if (!map.isEmpty()) {
                    c = HolidayCalendar.ofApi(map);
                    MEMORY.put(year, c);
                    return c;
                }
            } catch (Exception ignored) {
                // 缓存坏了就走兜底；下次刷新会覆盖
            }
        }
        c = HolidayCalendar.ofFallback(HolidayCn.yearNames(
                year, new LunarCalendar(new LunarCalendarIcu())));
        MEMORY.put(year, c);
        return c;
    }

    /** 主线程调用；只在 Wi-Fi 且过了 TTL / 退避期时真正发请求，结果回主线程 */
    public static void refreshIfNeeded(Context ctx, int year, @Nullable Callback cb) {
        if (ctx == null || year < 2000 || year > 2100) {
            return;
        }
        final Context app = ctx.getApplicationContext();
        SharedPreferences p = prefs(app);
        long fetchedAt = p.getLong("at_" + year, 0);
        long failedAt = p.getLong("fail_" + year, 0);
        if (!HolidayCachePolicy.shouldRefresh(
                System.currentTimeMillis(), fetchedAt, failedAt, isWifi(app))) {
            return;
        }
        if (!IN_FLIGHT.add(year)) {
            return;
        }

        Request request = new Request.Builder()
                .url(URL + year)
                .header("Accept", "application/json")
                .build();
        CLIENT.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                p.edit().putLong("fail_" + year, System.currentTimeMillis()).apply();
                IN_FLIGHT.remove(year);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    if (!response.isSuccessful() || response.body() == null) {
                        throw new IOException("http " + response.code());
                    }
                    String body = response.body().string();
                    Map<Integer, String> map = HolidayJson.parseYear(body, year);
                    if (map.isEmpty()) {
                        throw new IOException("empty holiday");
                    }
                    p.edit()
                            .putString("json_" + year, body)
                            .putLong("at_" + year, System.currentTimeMillis())
                            .remove("fail_" + year)
                            .apply();
                    HolidayCalendar cal = HolidayCalendar.ofApi(map);
                    MEMORY.put(year, cal);
                    MAIN.post(() -> {
                        if (cb != null) {
                            cb.onLoaded(year, cal);
                        }
                    });
                } catch (Exception e) {
                    p.edit().putLong("fail_" + year, System.currentTimeMillis()).apply();
                } finally {
                    IN_FLIGHT.remove(year);
                    response.close();
                }
            }
        });
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean isWifi(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager)
                ctx.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        Network n = cm == null ? null : cm.getActiveNetwork();
        NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
        return caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
    }
}
