package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.gson.JsonObject;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.HashMap;
import java.util.Map;

/**
 * 设置随 zip 迁移（v5.9.5 第 1 项）：白名单导出/恢复、{@code first_launch_at} 的 min 合并，
 * 以及「凭据绝不进包」这条底线。
 *
 * <p>测的是真偏好文件，所以每项都先记下原值、finally 按原值写回（原来没有的键删掉），
 * 跑完设备的设置和跑之前一样。
 */
@RunWith(AndroidJUnit4.class)
public class SettingsBackupInstrumentedTest {

    private static final String UI = "ui_prefs";
    private static final String APP = "app_prefs";
    private static final String API = "api_prefs";

    /** 导出→复位→恢复：设置要能原样回来 */
    @Test
    public void 导出后复位再恢复能回到原值() {
        Context ctx = ApplicationProvider.getApplicationContext();
        Map<String, String> saved = snapshot(ctx, UI, "theme_id", "reading_goal",
                "checkin_reminder_hour", "checkin_reminder_minute");
        saved.putAll(snapshot(ctx, APP, "first_launch_at"));
        try {
            SharedPreferences ui = ctx.getSharedPreferences(UI, Context.MODE_PRIVATE);
            ui.edit().putInt("theme_id", ThemeManager.THEME_NIGHT)
                    .putInt("reading_goal", 30)
                    .putInt("checkin_reminder_hour", 21)
                    .putInt("checkin_reminder_minute", 30).commit();
            ctx.getSharedPreferences(APP, Context.MODE_PRIVATE).edit()
                    .putLong("first_launch_at", 1000L).commit();

            JsonObject exported = SettingsBackup.export(ctx);

            // 模拟「换了台机器」：只删这几个被测键，别的设置一律不动
            ui.edit().remove("theme_id").remove("reading_goal")
                    .remove("checkin_reminder_hour").remove("checkin_reminder_minute").commit();
            ctx.getSharedPreferences(APP, Context.MODE_PRIVATE).edit()
                    .remove("first_launch_at").commit();
            assertFalse("清空没生效，后面的断言没有意义", ui.contains("theme_id"));

            assertTrue("恢复一项都没写成", SettingsBackup.restore(ctx, exported));
            assertEquals(ThemeManager.THEME_NIGHT, ui.getInt("theme_id", -1));
            assertEquals(30, ui.getInt("reading_goal", -1));
            assertEquals(21, ui.getInt("checkin_reminder_hour", -1));
            assertEquals(30, ui.getInt("checkin_reminder_minute", -1));
            assertEquals(1000L,
                    ctx.getSharedPreferences(APP, Context.MODE_PRIVATE).getLong("first_launch_at", -1));
        } finally {
            restoreAll(ctx, saved);
        }
    }

    /** 陪伴天数取两台的较早值：改小要接受，本机更早就不动 */
    @Test
    public void first_launch_at_用min合并() {
        Context ctx = ApplicationProvider.getApplicationContext();
        Map<String, String> saved = snapshot(ctx, APP, "first_launch_at");
        try {
            JsonObject fromBackup = SettingsBackup.export(ctx);
            SharedPreferences app = ctx.getSharedPreferences(APP, Context.MODE_PRIVATE);
            app.edit().putLong("first_launch_at", 2000L).commit();
            SettingsBackup.restore(ctx, withLaunch(fromBackup, 1000L));
            assertEquals("备份更早却没接受，天数被本机挡住了", 1000L, app.getLong("first_launch_at", -1));

            app.edit().putLong("first_launch_at", 500L).commit();
            SettingsBackup.restore(ctx, withLaunch(fromBackup, 1000L));
            assertEquals("本机开书日更早，恢复不能把它改晚", 500L, app.getLong("first_launch_at", -1));
        } finally {
            restoreAll(ctx, saved);
        }
    }

    /** 底线：API 密钥与凭据绝不进包 */
    @Test
    public void api密钥不出现在导出里() {
        Context ctx = ApplicationProvider.getApplicationContext();
        SharedPreferences api = ctx.getSharedPreferences(API, Context.MODE_PRIVATE);
        String probeKey = "__settings_backup_probe__";
        String secret = "sk-TEST-NEVER-BACKUP-0123456789";
        try {
            api.edit().putString(probeKey, secret).commit();
            String json = SettingsBackup.export(ctx).toString();
            assertFalse("导出里出现了 api_prefs", json.contains(API));
            assertFalse("导出里泄露了密钥值", json.contains(secret));
        } finally {
            api.edit().remove(probeKey).commit();
        }
    }

    /** 坏类型、越界值、不认识的版本：只跳过，不抛 */
    @Test
    public void 非法内容不抛异常只做夹取() {
        Context ctx = ApplicationProvider.getApplicationContext();
        Map<String, String> saved = snapshot(ctx, UI, "theme_id", "reading_goal");
        try {
            // theme_id 写成字符串：整键跳过
            JsonObject bad = block(1, "{\"theme_id\":{\"t\":4,\"v\":\"night\"}}");
            SettingsBackup.restore(ctx, bad);

            // 越界：99 要夹到主题上限
            JsonObject out = block(1, "{\"theme_id\":{\"t\":2,\"v\":99},"
                    + "\"reading_goal\":{\"t\":2,\"v\":0}}");
            assertTrue(SettingsBackup.restore(ctx, out));
            SharedPreferences ui = ctx.getSharedPreferences(UI, Context.MODE_PRIVATE);
            assertEquals(ThemeManager.THEME_NIGHT, ui.getInt("theme_id", -1));
            assertEquals("目标至少 1 本", 1, ui.getInt("reading_goal", -1));

            // 版本号在范围外：整块跳过，本机一个字节都不动
            assertFalse(SettingsBackup.restore(ctx, block(99, "{\"theme_id\":{\"t\":2,\"v\":0}}")));
            assertFalse(SettingsBackup.restore(ctx, new JsonObject()));
            assertFalse(SettingsBackup.restore(ctx, null));
        } finally {
            restoreAll(ctx, saved);
        }
    }

    /** 拼一个 {"version":v,"prefs":{"ui_prefs":{...}}} 块 */
    private static JsonObject block(int version, String uiPrefs) {
        JsonObject o = new JsonObject();
        o.addProperty("version", version);
        JsonObject prefs = new JsonObject();
        prefs.add(UI, com.google.gson.JsonParser.parseString(uiPrefs));
        o.add("prefs", prefs);
        return o;
    }

    /** 把导出块里的 app_prefs.first_launch_at 换成指定值 */
    private static JsonObject withLaunch(JsonObject settings, long value) {
        JsonObject copy = settings.deepCopy();
        JsonObject prefs = copy.getAsJsonObject("prefs");
        JsonObject app = prefs.getAsJsonObject(APP);
        JsonObject item = new JsonObject();
        item.addProperty("t", 3);
        item.addProperty("v", value);
        app.add("first_launch_at", item);
        return copy;
    }

    /** 记下这些键的当前值（类型 + 文本），返回 "file|key" → "t:v"；不含的键表示本机没有 */
    private static Map<String, String> snapshot(Context ctx, String file, String... keys) {
        SharedPreferences sp = ctx.getSharedPreferences(file, Context.MODE_PRIVATE);
        Map<String, String> out = new HashMap<>();
        for (String key : keys) {
            if (!sp.contains(key)) {
                continue;
            }
            Object value = sp.getAll().get(key);
            String tag;
            if (value instanceof Integer) {
                tag = "i:" + value;
            } else if (value instanceof Long) {
                tag = "l:" + value;
            } else if (value instanceof Boolean) {
                tag = "b:" + value;
            } else if (value instanceof Float) {
                tag = "f:" + value;
            } else if (value instanceof String) {
                tag = "s:" + value;
            } else {
                tag = "u:"; // 集合或读不出类型：按「本机原本没有」处理
            }
            out.put(file + "|" + key, tag);
        }
        return out;
    }

    /** 按 snapshot 的记法原样写回；原本没有的键删掉 */
    private static void restoreAll(Context ctx, Map<String, String> saved) {
        Map<String, SharedPreferences.Editor> editors = new HashMap<>();
        for (Map.Entry<String, String> e : saved.entrySet()) {
            String[] fileKey = e.getKey().split("\\|", 2);
            SharedPreferences.Editor ed = editors.get(fileKey[0]);
            if (ed == null) {
                ed = ctx.getSharedPreferences(fileKey[0], Context.MODE_PRIVATE).edit();
                editors.put(fileKey[0], ed);
            }
            String tag = e.getValue();
            char kind = tag.charAt(0);
            String body = tag.substring(2);
            switch (kind) {
                case 'i':
                    ed.putInt(fileKey[1], Integer.parseInt(body));
                    break;
                case 'l':
                    ed.putLong(fileKey[1], Long.parseLong(body));
                    break;
                case 'b':
                    ed.putBoolean(fileKey[1], Boolean.parseBoolean(body));
                    break;
                case 'f':
                    ed.putFloat(fileKey[1], Float.parseFloat(body));
                    break;
                case 's':
                    ed.putString(fileKey[1], body);
                    break;
                default:
                    ed.remove(fileKey[1]);
            }
        }
        for (SharedPreferences.Editor ed : editors.values()) {
            ed.commit();
        }
    }
}
