package com.bai.bookshelf.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户设置随 zip 整包备份迁移（v5.9.5 第 1 项）。
 *
 * <p>为什么用白名单而不是整包 dump SharedPreferences：偏好文件里混着三类绝不能随包扩散的东西
 * ——凭据（{@code api_prefs} 全部）、会把用户锁在门外的状态（{@code app_lock_prefs}）、
 * 只对本机有意义的授权与一次性标记（{@code backup_prefs} 的 SAF 树与去重指纹、
 * {@code holiday_cache}、{@code image_migration}、{@code repair_prefs}、
 * {@code update_check.ignored}、{@code ui_prefs.splash_exit_failed}、{@code app_prefs.db_version}）。
 * 所以逐键、逐类型列白名单，导出与恢复都只认这张表。
 *
 * <p>为什么每个值要带类型标记（{@code {"t": 类型, "v": 值}}）：SharedPreferences 是类型化的，
 * 同一个键在别的版本里可能被写成 int 或 string，直接塞裸值会在恢复端 {@code getInt/getLong}
 * 时抛 ClassCastException。类型对不上就跳过该键，不连累整包。
 */
public final class SettingsBackup {

    /** settings 块自身的格式版本；范围外的整块跳过 */
    public static final int VERSION = 1;

    private static final int T_BOOL = 1;
    private static final int T_INT = 2;
    private static final int T_LONG = 3;
    private static final int T_STR = 4;

    private static final class Entry {
        final String file;
        final String key;
        final int type;
        /** int 值的闭区间；为 null 的一端不夹。只有 reading_goal 有下限 */
        final Integer min;
        final Integer max;

        Entry(String file, String key, int type) {
            this(file, key, type, null, null);
        }

        Entry(String file, String key, int type, Integer min, Integer max) {
            this.file = file;
            this.key = key;
            this.type = type;
            this.min = min;
            this.max = max;
        }
    }

    /** 逐键白名单（文件、键、类型、取值区间） */
    private static final Entry[] WHITELIST = {
            new Entry("ui_prefs", "theme_id", T_INT, ThemeManager.THEME_GREEN, ThemeManager.THEME_NIGHT),
            new Entry("ui_prefs", "nav_start_tab", T_INT, 0, 3),
            new Entry("ui_prefs", "reading_goal", T_INT, 1, null),
            new Entry("ui_prefs", "reading_goal_year", T_INT),
            new Entry("ui_prefs", "reading_goal_prompt_year", T_INT),
            new Entry("ui_prefs", "checkin_reminder_hour", T_INT, 0, 23),
            new Entry("ui_prefs", "checkin_reminder_minute", T_INT, 0, 59),
            new Entry("ui_prefs", "shelf_view_mode", T_INT),
            // 书架六维筛选 + 排序（ShelfViewPrefs，键前缀 shelf_f_）。
            // 类型必须与 ShelfViewPrefs 构造里的 getInt/getBoolean/getString 一一对上，
            // 对不上会被 readOne 静默跳过；SettingsBackupWhitelistTest 有一条防漂移断言
            new Entry("ui_prefs", "shelf_f_sort_index", T_INT),
            new Entry("ui_prefs", "shelf_f_sort_reverse", T_BOOL),
            new Entry("ui_prefs", "shelf_f_stock", T_INT),
            new Entry("ui_prefs", "shelf_f_read", T_INT),
            new Entry("ui_prefs", "shelf_f_location", T_STR),
            new Entry("ui_prefs", "shelf_f_tag", T_STR),
            new Entry("ui_prefs", "shelf_f_category", T_STR),
            new Entry("ui_prefs", "shelf_f_country", T_STR),
            new Entry("ui_prefs", "stats_dist_seg", T_INT, 0, 2),
            new Entry("ui_prefs", "wish_sort_mode", T_INT),
            new Entry("ui_prefs", "last_location_id", T_LONG),
            new Entry("ui_prefs", "nav_order", T_STR),
            new Entry("ui_prefs", "nav_hidden", T_STR),
            new Entry("ui_prefs", "recent_searches", T_STR),
            new Entry("ui_prefs", "reading_goal_set", T_BOOL),
            new Entry("ui_prefs", "notify_borrow_due", T_BOOL),
            new Entry("ui_prefs", "notify_borrowed_in", T_BOOL),
            new Entry("ui_prefs", "badge_overdue", T_BOOL),
            new Entry("ui_prefs", "banner_backup_stale", T_BOOL),
            new Entry("ui_prefs", "banner_borrow_due", T_BOOL),
            new Entry("ui_prefs", "notify_checkin_daily", T_BOOL),
            new Entry("ui_prefs", "widget_enabled", T_BOOL),
            new Entry("ui_prefs", "onboarding_next_done", T_BOOL),
            new Entry("ui_prefs", "task_skip_folder", T_BOOL),
            new Entry("ui_prefs", "task_list_dismissed", T_BOOL),
            new Entry("ui_prefs", "precise_mode_hint_seen", T_BOOL),
            new Entry("detail_sections", "info", T_BOOL),
            new Entry("detail_sections", "core", T_BOOL),
            new Entry("detail_sections", "manage", T_BOOL),
            new Entry("detail_sections", "records", T_BOOL),
            new Entry("detail_sections", "borrow_records", T_BOOL),
            new Entry("split_prefs", "history", T_STR),
            new Entry("app_prefs", "first_launch_at", T_LONG),
            new Entry("backup_prefs", "auto_enabled", T_BOOL),
            new Entry("update_check", "start_check", T_BOOL),
            new Entry("update_check", "never", T_BOOL),
    };

    private SettingsBackup() {
    }

    /**
     * 白名单读写走的偏好文件来源。
     *
     * <p>生产代码一律从 {@link Context} 取；JVM 单测给一个内存实现，就能在不碰 Android
     * 的前提下把整张白名单跑一遍（同坑 49 的思路：给依赖开个口，而不是把逻辑抄一遍）。
     */
    interface PrefsSource {
        SharedPreferences prefs(String file);
    }

    private static PrefsSource fromContext(final Context ctx) {
        return file -> ctx.getSharedPreferences(file, Context.MODE_PRIVATE);
    }

    /** 白名单导出（需在后台线程调用）：本机没有的键不写，免得给恢复端造出默认值 */
    public static JsonObject export(Context ctx) {
        return export(fromContext(ctx));
    }

    /** 白名单导出 */
    static JsonObject export(PrefsSource src) {
        JsonObject prefs = new JsonObject();
        for (Entry entry : WHITELIST) {
            JsonElement value = readOne(src, entry);
            if (value == null) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("t", entry.type);
            item.add("v", value);
            JsonObject file;
            if (prefs.has(entry.file)) {
                file = prefs.getAsJsonObject(entry.file);
            } else {
                file = new JsonObject();
                prefs.add(entry.file, file);
            }
            file.add(entry.key, item);
        }
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.add("prefs", prefs);
        return root;
    }

    /**
     * 恢复白名单里的设置（需在后台线程调用，落盘用 commit）。
     *
     * <p>逐键严格校验类型：坏键只跳过自己；数值越界夹到区间端点；
     * {@code first_launch_at} 取两台的较早值（陪伴天数不能被恢复改小）。
     *
     * @return 是否至少写入了一项；格式不认识或全部没写成时返回 false
     */
    public static boolean restore(Context ctx, JsonObject settings) {
        return restore(fromContext(ctx), settings);
    }

    /** 恢复白名单里的设置 */
    static boolean restore(PrefsSource src, JsonObject settings) {
        try {
            if (settings == null || !settings.has("version")
                    || !settings.get("version").isJsonPrimitive()) {
                return false;
            }
            int version;
            try {
                version = settings.get("version").getAsInt();
            } catch (Throwable ignored) {
                return false;
            }
            if (version < 1 || version > VERSION) {
                return false;
            }
            if (!settings.has("prefs") || !settings.get("prefs").isJsonObject()) {
                return false;
            }
            JsonObject prefs = settings.getAsJsonObject("prefs");
            Map<String, SharedPreferences.Editor> editors = new LinkedHashMap<>();
            int applied = 0;
            for (Entry entry : WHITELIST) {
                if (!prefs.has(entry.file)) {
                    continue;
                }
                JsonElement file = prefs.get(entry.file);
                if (!file.isJsonObject()) {
                    continue;
                }
                JsonObject obj = file.getAsJsonObject();
                if (!obj.has(entry.key) || !obj.get(entry.key).isJsonObject()) {
                    continue;
                }
                if (applyOne(src, editors, entry, obj.getAsJsonObject(entry.key))) {
                    applied++;
                }
            }
            if (applied == 0) {
                return false;
            }
            boolean committed = false;
            for (SharedPreferences.Editor editor : editors.values()) {
                // 恢复跑在后台线程，必须在 recreate() 之前落盘，所以用 commit 不用 apply
                committed |= editor.commit();
            }
            return committed;
        } catch (Throwable ignored) {
            // 设置恢复失败绝不能让整包恢复失败
            return false;
        }
    }

    /**
     * 设置指纹（需在后台线程调用）：与 {@link #export} 共用同一张白名单与取数路径，
     * 规范串做 SHA-256。只改设置不改库时，自动备份靠它才不会被当成「内容没变」跳过。
     */
    public static String fingerprint(Context ctx) {
        return fingerprint(fromContext(ctx));
    }

    /** 设置指纹 */
    static String fingerprint(PrefsSource src) {
        StringBuilder sb = new StringBuilder(512);
        for (Entry entry : WHITELIST) {
            sb.append(entry.file).append('|').append(entry.key).append('|')
                    .append(entry.type).append('|');
            JsonElement value = readOne(src, entry);
            if (value == null) {
                sb.append('-');
            } else if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String text = value.getAsString();
                sb.append(text.length()).append(':').append(text);
            } else {
                sb.append(value);
            }
            sb.append('\n');
        }
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return BackupChecksum.hex(md.digest(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Throwable ignored) {
            // 抛出去让 BackupFingerprint 按「内容变了」处理，宁可多备份一次
            throw new IllegalStateException("SHA-256 unavailable", ignored);
        }
    }

    /** 读一个键并按类型包成 JSON；键不存在、类型不符或读失败返回 null */
    private static JsonElement readOne(PrefsSource src, Entry entry) {
        try {
            SharedPreferences sp = src.prefs(entry.file);
            if (!sp.contains(entry.key)) {
                return null;
            }
            switch (entry.type) {
                case T_BOOL:
                    return new com.google.gson.JsonPrimitive(sp.getBoolean(entry.key, false));
                case T_INT:
                    return new com.google.gson.JsonPrimitive(sp.getInt(entry.key, 0));
                case T_LONG:
                    return new com.google.gson.JsonPrimitive(sp.getLong(entry.key, 0L));
                case T_STR:
                    return new com.google.gson.JsonPrimitive(sp.getString(entry.key, ""));
                default:
                    return null;
            }
        } catch (Throwable ignored) {
            // 本机这个键的类型和历史不一致：跳过它，别拖累其余设置
            return null;
        }
    }

    /** 校验并写入一个键；成功返回 true（值与本机相同也算写入，交给 commit 落盘） */
    private static boolean applyOne(PrefsSource src, Map<String, SharedPreferences.Editor> editors,
                                    Entry entry, JsonObject item) {
        try {
            if (!item.has("t") || !item.has("v") || !item.get("t").isJsonPrimitive()) {
                return false;
            }
            if (item.get("t").getAsInt() != entry.type) {
                return false; // 类型标记对不上：宁可不动本机
            }
            JsonElement value = item.get("v");
            SharedPreferences.Editor editor = editorFor(src, editors, entry.file);
            switch (entry.type) {
                case T_BOOL:
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                        return false;
                    }
                    editor.putBoolean(entry.key, value.getAsBoolean());
                    return true;
                case T_INT: {
                    if (!isNumeric(value)) {
                        return false;
                    }
                    int raw = value.getAsInt();
                    if (entry.min != null) {
                        raw = Math.max(entry.min, raw);
                    }
                    if (entry.max != null) {
                        raw = Math.min(entry.max, raw);
                    }
                    editor.putInt(entry.key, raw);
                    return true;
                }
                case T_LONG: {
                    if (!isNumeric(value)) {
                        return false;
                    }
                    long restored = value.getAsLong();
                    if ("first_launch_at".equals(entry.key)) {
                        // min 合并：新装恢复旧备份能拿到真实首日，老装恢复也不会把天数改小
                        SharedPreferences sp = src.prefs(entry.file);
                        long current = sp.getLong(entry.key, 0L);
                        if (restored <= 0 || (current > 0 && restored >= current)) {
                            return false;
                        }
                    }
                    editor.putLong(entry.key, restored);
                    return true;
                }
                case T_STR:
                    if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
                        return false;
                    }
                    editor.putString(entry.key, value.getAsString());
                    return true;
                default:
                    return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean isNumeric(JsonElement value) {
        return value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber();
    }

    private static SharedPreferences.Editor editorFor(PrefsSource src,
                                                      Map<String, SharedPreferences.Editor> editors,
                                                      String file) {
        SharedPreferences.Editor editor = editors.get(file);
        if (editor == null) {
            editor = src.prefs(file).edit();
            editors.put(file, editor);
        }
        return editor;
    }
}
