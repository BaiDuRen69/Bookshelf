package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.SharedPreferences;

import com.bai.bookshelf.ui.shelf.ShelfViewPrefs;
import com.google.gson.JsonObject;

import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 设置白名单的 JVM 往返（卡 8 A 组）。
 *
 * <p><b>为什么能脱离 Android 跑</b>：{@link SettingsBackup} 只通过
 * {@code ctx.getSharedPreferences(file, MODE_PRIVATE)} 碰 Android，现在这层收成了
 * {@link SettingsBackup.PrefsSource} 一个口子，测试给一个动态代理版的内存 SharedPreferences
 * （同坑 49：代理而不是实现接口，androidx / android.jar 加方法也不会编译失败）。
 *
 * <p><b>期望表是给改动者看的闸门</b>：白名单加一个键、改一个类型，这里的 44 条必须同步改，
 * 逼着每次都重新回答「这个键该不该随包扩散」。
 */
public class SettingsBackupWhitelistTest {

    /** 与 {@code SettingsBackup.WHITELIST} 逐项对应：文件、键、类型（b/i/l/s）、导出端的值 */
    private static final String[][] EXPECTED = {
            {"ui_prefs", "theme_id", "i", "4"},
            {"ui_prefs", "nav_start_tab", "i", "2"},
            {"ui_prefs", "reading_goal", "i", "60"},
            {"ui_prefs", "reading_goal_year", "i", "2026"},
            {"ui_prefs", "reading_goal_prompt_year", "i", "2025"},
            {"ui_prefs", "checkin_reminder_hour", "i", "9"},
            {"ui_prefs", "checkin_reminder_minute", "i", "30"},
            {"ui_prefs", "shelf_view_mode", "i", "1"},
            {"ui_prefs", "shelf_f_sort_index", "i", "2"},
            {"ui_prefs", "shelf_f_sort_reverse", "b", "true"},
            {"ui_prefs", "shelf_f_stock", "i", "1"},
            {"ui_prefs", "shelf_f_read", "i", "2"},
            {"ui_prefs", "shelf_f_location", "s", "客厅书架"},
            {"ui_prefs", "shelf_f_tag", "s", "科幻"},
            {"ui_prefs", "shelf_f_category", "s", "小说"},
            {"ui_prefs", "shelf_f_country", "s", "中国"},
            {"ui_prefs", "stats_dist_seg", "i", "2"},
            {"ui_prefs", "wish_sort_mode", "i", "3"},
            {"ui_prefs", "last_location_id", "l", "42"},
            {"ui_prefs", "nav_order", "s", "3,1,0,2"},
            {"ui_prefs", "nav_hidden", "s", "2"},
            {"ui_prefs", "recent_searches", "s", "三体,百年孤独"},
            {"ui_prefs", "reading_goal_set", "b", "true"},
            {"ui_prefs", "notify_borrow_due", "b", "true"},
            {"ui_prefs", "notify_borrowed_in", "b", "false"},
            {"ui_prefs", "badge_overdue", "b", "true"},
            {"ui_prefs", "banner_backup_stale", "b", "false"},
            {"ui_prefs", "banner_borrow_due", "b", "true"},
            {"ui_prefs", "notify_checkin_daily", "b", "true"},
            {"ui_prefs", "widget_enabled", "b", "false"},
            {"ui_prefs", "onboarding_next_done", "b", "true"},
            {"ui_prefs", "task_skip_folder", "b", "false"},
            {"ui_prefs", "task_list_dismissed", "b", "true"},
            {"ui_prefs", "precise_mode_hint_seen", "b", "false"},
            {"detail_sections", "info", "b", "true"},
            {"detail_sections", "core", "b", "true"},
            {"detail_sections", "manage", "b", "false"},
            {"detail_sections", "records", "b", "true"},
            {"detail_sections", "borrow_records", "b", "false"},
            {"split_prefs", "history", "s", "9787500000001|2"},
            {"app_prefs", "first_launch_at", "l", "1700000000000"},
            {"backup_prefs", "auto_enabled", "b", "true"},
            {"update_check", "start_check", "b", "false"},
            {"update_check", "never", "b", "false"},
    };

    /** 绝不能随包扩散的东西：凭据、会把人锁在门外的状态、只对本机有意义的授权与标记 */
    private static final String[][] MUST_NOT_EXPORT = {
            {"api_prefs", "secret_key", "s", "sk-SECRET--do-not-backup"},
            {"api_prefs", "secret_id", "s", "sid-SECRET-do-not-backup"},
            {"api_prefs", "appcode", "s", "appcode-SECRET"},
            {"api_prefs", "base_url", "s", "https://SECRET.example.local"},
            {"api_prefs_secure", "secret_key", "s", "enc-SECRET-blob"},
            {"app_lock_prefs", "enabled", "b", "true"},
            {"app_lock_prefs", "last_background_at", "l", "1700000000000"},
            {"holiday_cache", "cn_2026", "s", "holiday-SECRET-payload"},
            {"image_migration", "done", "s", "migration-SECRET-flag"},
            {"repair_prefs", "broken_db_path", "s", "/data-SECRET-broken"},
            {"update_check", "ignored", "s", "v5.9.4-SECRET-ignored"},
            {"backup_prefs", "tree_uri", "s", "content://SECRET/tree"},
            {"backup_prefs", "content_fp", "s", "deadSECRETbeef"},
            {"ui_prefs", "splash_exit_failed", "b", "true"},
            {"app_prefs", "db_version", "i", "25"},
    };

    private static final int T_BOOL = 1;
    private static final int T_INT = 2;
    private static final int T_LONG = 3;
    private static final int T_STR = 4;

    // ==================== A：44 条逐项往返 ====================

    @Test
    public void 白名单正好四十四键且导出端逐项带对类型() {
        assertEquals("期望表本身必须是 44 条", 44, EXPECTED.length);
        Mem mem = seeded();
        JsonObject root = SettingsBackup.export(mem);
        assertEquals(1, root.get("version").getAsInt());
        JsonObject prefs = root.getAsJsonObject("prefs");
        int count = 0;
        for (String[] e : EXPECTED) {
            JsonObject item = itemOf(prefs, e[0], e[1]);
            assertNotNull("白名单少了 " + e[0] + "/" + e[1], item);
            assertEquals(e[0] + "/" + e[1] + " 的类型标记", typeCode(e[2]), item.get("t").getAsInt());
            assertEquals(e[0] + "/" + e[1] + " 的值", e[3], item.get("v").getAsString());
            count++;
        }
        assertEquals("导出条数必须和期望表一致（多写了键说明白名单被悄悄动过）", 44, count);
    }

    @Test
    public void 四十四键导出后恢复到干净机器上逐键不串不错() {
        JsonObject root = SettingsBackup.export(seeded());
        Mem clean = new Mem();
        assertTrue("一条都没写成不该返回 true", SettingsBackup.restore(clean, root));
        for (String[] e : EXPECTED) {
            Object got = clean.raw(e[0], e[1]);
            assertNotNull("恢复后少了 " + e[0] + "/" + e[1], got);
            assertEquals(e[0] + "/" + e[1] + " 恢复成别的值了", value(e), got);
        }
    }

    @Test
    public void 本机没有的键不写进导出也不给恢复端造默认值() {
        JsonObject root = SettingsBackup.export(new Mem());
        assertEquals("空机器不该导出任何偏好文件", 0,
                root.getAsJsonObject("prefs").entrySet().size());
        assertFalse("全是默认值时恢复必须算失败", SettingsBackup.restore(new Mem(), root));
    }

    @Test
    public void 本机某键类型写歪时只跳过它自己其余四十四键照旧() {
        Mem mem = seeded();
        // 历史版本把 nav_order 写成过 int：读端会抛 ClassCastException，只该丢掉这一个键
        mem.put("ui_prefs", "nav_order", 7);
        JsonObject prefs = SettingsBackup.export(mem).getAsJsonObject("prefs");
        assertFalse("类型不符的键不能带进包",
                prefs.getAsJsonObject("ui_prefs").has("nav_order"));
        for (String[] e : EXPECTED) {
            if ("nav_order".equals(e[1])) {
                continue;
            }
            assertNotNull("其余键不该被连累：" + e[0] + "/" + e[1], itemOf(prefs, e[0], e[1]));
        }
    }

    // ==================== 隐私闸门 ====================

    @Test
    public void 凭据与应用锁的偏好一个字节都不进导出() {
        Mem mem = seeded();
        for (String[] e : MUST_NOT_EXPORT) {
            mem.put(e[0], e[1], value(e));
        }
        JsonObject prefs = SettingsBackup.export(mem).getAsJsonObject("prefs");
        String dumped = prefs.toString();
        for (String[] e : MUST_NOT_EXPORT) {
            if (whitelistFile(e[0])) {
                // 白名单里确实有这几个文件（ui_prefs / app_prefs / backup_prefs / update_check），
                // 那就逐个键确认：带出去的仍然只有白名单那一个
                JsonObject file = prefs.getAsJsonObject(e[0]);
                assertFalse(e[0] + "/" + e[1] + " 不该出现",
                        file != null && file.has(e[1]));
                continue;
            }
            assertFalse("整个偏好文件都不该出现：" + e[0], prefs.has(e[0]));
            if (e[3].contains("SECRET")) {
                assertFalse("敏感值不该以任何形式出现：" + e[0] + "/" + e[1],
                        dumped.contains(e[3]));
            }
        }
        assertFalse("导出里不该有 SECRET 这个哨兵", dumped.contains("SECRET"));
    }

    @Test
    public void 只改设置不改库时设置指纹也能看出来() {
        Mem a = seeded();
        Mem b = seeded();
        assertEquals("同样的设置必须算出同一个指纹",
                SettingsBackup.fingerprint(a), SettingsBackup.fingerprint(b));
        b.put("ui_prefs", "theme_id", 5);
        assertFalse("只改一个设置就该被指纹看出来",
                SettingsBackup.fingerprint(a).equals(SettingsBackup.fingerprint(b)));
        Mem empty = new Mem();
        assertEquals("空机器也要能算出 64 位十六进制", 64,
                SettingsBackup.fingerprint(empty).length());
    }

    // ==================== 书架筛选八键：防漂移 ====================

    /**
     * 键名从 {@link ShelfViewPrefs} 的常量取，不在这里抄字符串：
     * 白名单是 v5.9.5 定的，ShelfViewPrefs 的持久化晚两天落地，当时就漏了这 8 个键。
     * 改名或加维度时这条会红，而不是等用户换机丢了筛选才发现。
     */
    @Test
    public void 书架筛选八键都在白名单里且类型与读写端一致() {
        String[][] shelfKeys = {
                {ShelfViewPrefs.KEY_SORT_INDEX, "i"},
                {ShelfViewPrefs.KEY_SORT_REVERSE, "b"},
                {ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_STOCK), "i"},
                {ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_READ), "i"},
                {ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_LOCATION), "s"},
                {ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_TAG), "s"},
                {ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_CATEGORY), "s"},
                {ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_COUNTRY), "s"},
        };
        assertEquals("六维 + 排序两项 = 8 个键", 8, shelfKeys.length);
        Mem mem = new Mem();
        for (String[] k : shelfKeys) {
            mem.put("ui_prefs", k[0], sample(k[1]));
            boolean inExpected = false;
            for (String[] e : EXPECTED) {
                if ("ui_prefs".equals(e[0]) && k[0].equals(e[1])) {
                    inExpected = true;
                    assertEquals(k[0] + " 在期望表里的类型", k[1], e[2]);
                }
            }
            assertTrue("期望表漏了书架筛选键 " + k[0], inExpected);
        }
        JsonObject prefs = SettingsBackup.export(mem).getAsJsonObject("prefs");
        for (String[] k : shelfKeys) {
            JsonObject item = itemOf(prefs, "ui_prefs", k[0]);
            assertNotNull("白名单漏了书架筛选键 " + k[0], item);
            assertEquals(k[0] + " 的类型标记", typeCode(k[1]), item.get("t").getAsInt());
        }
        // 恢复到干净机器上：八个键一个都不能少，值也不能串
        Mem clean = new Mem();
        assertTrue(SettingsBackup.restore(clean, SettingsBackup.export(mem)));
        for (String[] k : shelfKeys) {
            assertEquals(k[0] + " 恢复后的值", sample(k[1]), clean.raw("ui_prefs", k[0]));
        }
    }

    /**
     * 缺口 C 的连带后果：只改筛选不写 Room 库，库文件时间戳不变，
     * 自动备份全靠设置指纹才看得见——否则整包被当成「内容没变」跳过。
     */
    @Test
    public void 只改书架筛选不动库设置指纹也会变() {
        Mem mem = seeded();
        String before = SettingsBackup.fingerprint(mem);
        String key = ShelfViewPrefs.keyFor(ShelfViewPrefs.DIM_CATEGORY);
        mem.put("ui_prefs", key, "诗歌");
        String changed = SettingsBackup.fingerprint(mem);
        assertFalse("改筛选必须让自动备份看得见", before.equals(changed));
        // 清成「不限」在 ShelfViewPrefs 里是删键，不是写 0：同样必须被指纹看出来
        mem.prefs("ui_prefs").edit().remove(key).commit();
        assertFalse("清掉筛选也必须让自动备份看得见",
                changed.equals(SettingsBackup.fingerprint(mem)));
    }

    private static Object sample(String type) {
        switch (type) {
            case "b":
                return Boolean.TRUE;
            case "i":
                return Integer.valueOf(2);
            default:
                return "样例";
        }
    }

    // ==================== 工具 ====================

    private static Mem seeded() {
        Mem mem = new Mem();
        for (String[] e : EXPECTED) {
            mem.put(e[0], e[1], value(e));
        }
        return mem;
    }

    /** 白名单已经覆盖到的文件：这些要按「键」断言，不能按「文件」断言 */
    private static boolean whitelistFile(String file) {
        for (String[] e : EXPECTED) {
            if (e[0].equals(file)) {
                return true;
            }
        }
        return false;
    }

    private static Object value(String[] e) {
        switch (e[2]) {
            case "b":
                return Boolean.parseBoolean(e[3]);
            case "i":
                return Integer.parseInt(e[3]);
            case "l":
                return Long.parseLong(e[3]);
            default:
                return e[3];
        }
    }

    private static int typeCode(String t) {
        switch (t) {
            case "b":
                return T_BOOL;
            case "i":
                return T_INT;
            case "l":
                return T_LONG;
            default:
                return T_STR;
        }
    }

    private static JsonObject itemOf(JsonObject prefs, String file, String key) {
        if (!prefs.has(file)) {
            return null;
        }
        JsonObject obj = prefs.getAsJsonObject(file);
        if (!obj.has(key)) {
            return null;
        }
        return obj.getAsJsonObject(key);
    }

    /** 内存版偏好：读写都走 Map，类型不符时像真机一样抛 ClassCastException */
    private static final class Mem implements SettingsBackup.PrefsSource {

        private final Map<String, Map<String, Object>> files = new LinkedHashMap<>();

        void put(String file, String key, Object value) {
            data(file).put(key, value);
        }

        Object raw(String file, String key) {
            return data(file).get(key);
        }

        private Map<String, Object> data(String file) {
            Map<String, Object> one = files.get(file);
            if (one == null) {
                one = new LinkedHashMap<>();
                files.put(file, one);
            }
            return one;
        }

        @Override
        public SharedPreferences prefs(String file) {
            return (SharedPreferences) Proxy.newProxyInstance(
                    SharedPreferences.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.class},
                    (p, method, args) -> dispatch(p, file, method.getName(), args));
        }

        private Object dispatch(Object self, String file, String name, Object[] args) {
            Map<String, Object> data = data(file);
            switch (name) {
                case "getAll":
                    return new LinkedHashMap<>(data);
                case "contains":
                    return data.containsKey((String) args[0]);
                case "getString":
                    return string(data, (String) args[0], (String) args[1]);
                case "getInt":
                    return integer(data, (String) args[0], (Integer) args[1]);
                case "getLong":
                    return aLong(data, (String) args[0], (Long) args[1]);
                case "getBoolean":
                    return aBoolean(data, (String) args[0], (Boolean) args[1]);
                case "getFloat":
                    return Float.valueOf(0f);
                case "getStringSet":
                    return null;
                case "edit":
                    return editor(file);
                case "toString":
                    return "MemPrefs:" + file;
                case "hashCode":
                    return System.identityHashCode(self);
                case "equals":
                    return self == args[0];
                default:
                    return null; // 注册监听等：单测里没人用，返回无害默认值
            }
        }

        private static String string(Map<String, Object> data, String key, String def) {
            Object v = data.get(key);
            if (v == null) {
                return def;
            }
            if (!(v instanceof String)) {
                throw new ClassCastException(key + " 不是 String");
            }
            return (String) v;
        }

        private static Integer integer(Map<String, Object> data, String key, Integer def) {
            Object v = data.get(key);
            if (v == null) {
                return def;
            }
            if (!(v instanceof Integer)) {
                throw new ClassCastException(key + " 不是 Integer");
            }
            return (Integer) v;
        }

        private static Long aLong(Map<String, Object> data, String key, Long def) {
            Object v = data.get(key);
            if (v == null) {
                return def;
            }
            if (!(v instanceof Long)) {
                throw new ClassCastException(key + " 不是 Long");
            }
            return (Long) v;
        }

        private static Boolean aBoolean(Map<String, Object> data, String key, Boolean def) {
            Object v = data.get(key);
            if (v == null) {
                return def;
            }
            if (!(v instanceof Boolean)) {
                throw new ClassCastException(key + " 不是 Boolean");
            }
            return (Boolean) v;
        }

        private SharedPreferences.Editor editor(String file) {
            final Map<String, Object> data = data(file);
            final Map<String, Object> pending = new LinkedHashMap<>();
            final List<String> removed = new ArrayList<>();
            SharedPreferences.Editor ed = (SharedPreferences.Editor) Proxy.newProxyInstance(
                    SharedPreferences.Editor.class.getClassLoader(),
                    new Class<?>[]{SharedPreferences.Editor.class},
                    (p, method, args) -> {
                        String name = method.getName();
                        if (name.startsWith("put")) {
                            pending.put((String) args[0], args[1]);
                            return p;
                        }
                        switch (name) {
                            case "remove":
                                removed.add((String) args[0]);
                                return p;
                            case "clear":
                                data.clear();
                                return p;
                            case "commit":
                            case "apply":
                                for (Map.Entry<String, Object> en : pending.entrySet()) {
                                    data.put(en.getKey(), en.getValue());
                                }
                                for (String key : removed) {
                                    data.remove(key);
                                }
                                pending.clear();
                                removed.clear();
                                return Boolean.TRUE;
                            case "toString":
                                return "MemEditor:" + file;
                            case "hashCode":
                                return System.identityHashCode(p);
                            case "equals":
                                return p == args[0];
                            default:
                                return null;
                        }
                    });
            return ed;
        }
    }
}
