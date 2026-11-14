package com.bai.bookshelf.ui.shelf;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 书架视图状态持久化（卡 H）：排序方式 / 倒序 + 六维筛选（藏书状态、阅读状态、地点、标签、
 * 分类、国家/地区）双写进既有的 ui_prefs，键前缀 shelf_f_。
 *
 * <p>语义只有一条：**重启后仍是上次视图**。切 Tab 也保留是既有行为——MainActivity 用
 * show/hide 复用 Fragment 实例，视图根本没销毁，不归这里管。
 *
 * <p>两类维度分开存：
 * <ul>
 *   <li>下标维（stock / read）：内容写死在 arrays.xml，下标稳定，直接存下标；</li>
 *   <li>文案维（location / tag / category / country）：内容来自数据库，条目会增减，
 *       所以存选中文案，回填时按文案找下标。条目改名或被删就是「找不到 → 保持不限」，
 *       绝不会串到相邻条目上。</li>
 * </ul>
 *
 * <p>{@link #applySelection}、{@link #normalize}、{@link #keyFor}、{@link #indexForLabel}
 * 都是不碰 Android 类型的纯函数，JVM 单测直接测它们；实例方法只负责把同一份内存副本落到 SharedPreferences。
 */
public final class ShelfViewPrefs {

    public static final String DIM_STOCK = "stock";
    public static final String DIM_READ = "read";
    public static final String DIM_LOCATION = "location";
    public static final String DIM_TAG = "tag";
    public static final String DIM_CATEGORY = "category";
    public static final String DIM_COUNTRY = "country";

    private static final String PREFIX = "shelf_f_";
    public static final String KEY_SORT_INDEX = PREFIX + "sort_index";
    public static final String KEY_SORT_REVERSE = PREFIX + "sort_reverse";

    /** 存下标的维度；其余受支持维度存文案 */
    private static final List<String> INDEX_DIMS = Arrays.asList(DIM_STOCK, DIM_READ);
    /** 全部受支持维度。来源（channel）与二级 chips 不在卡 H 范围内，读到时按不支持处理 */
    private static final List<String> SUPPORTED_DIMS =
            Arrays.asList(DIM_STOCK, DIM_READ, DIM_LOCATION, DIM_TAG, DIM_CATEGORY, DIM_COUNTRY);

    /** 内存副本里出现的键全集（排序两项 + 六个维度） */
    private static final List<String> ALL_KEYS;

    static {
        List<String> keys = new java.util.ArrayList<>();
        keys.add(KEY_SORT_INDEX);
        keys.add(KEY_SORT_REVERSE);
        for (String dim : SUPPORTED_DIMS) {
            keys.add(keyFor(dim));
        }
        ALL_KEYS = java.util.Collections.unmodifiableList(keys);
    }

    private final SharedPreferences sp;
    private final Map<String, Object> mem;

    public ShelfViewPrefs(Context ctx) {
        sp = ctx.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE);
        Map<String, Object> raw = new HashMap<>();
        raw.put(KEY_SORT_INDEX, sp.getInt(KEY_SORT_INDEX, 0));
        raw.put(KEY_SORT_REVERSE, sp.getBoolean(KEY_SORT_REVERSE, false));
        for (String dim : SUPPORTED_DIMS) {
            if (isIndexDim(dim)) {
                raw.put(keyFor(dim), sp.getInt(keyFor(dim), 0));
            } else {
                raw.put(keyFor(dim), sp.getString(keyFor(dim), ""));
            }
        }
        mem = normalize(raw);
    }

    // ======================= 纯函数（JVM 单测覆盖）=======================

    public static String keyFor(String dim) {
        return PREFIX + dim;
    }

    public static boolean isSupportedDim(String dim) {
        return SUPPORTED_DIMS.contains(dim);
    }

    public static boolean isIndexDim(String dim) {
        return INDEX_DIMS.contains(dim);
    }

    /**
     * 把一次 Spinner 选择记进内存副本。position &lt;= 0 是「不限」，必须**删键**而不是写 0：
     * 否则用户清掉筛选之后重启，旧值又被恢复回来（这正是坑 3.3 里记忆恢复的反向半边）。
     */
    public static void applySelection(Map<String, Object> mem, String dim, int position, String text) {
        if (!isSupportedDim(dim)) {
            return;
        }
        String key = keyFor(dim);
        if (position <= 0) {
            mem.remove(key);
            return;
        }
        if (isIndexDim(dim)) {
            mem.put(key, position);
        } else {
            if (text == null || text.isEmpty()) {
                mem.remove(key);
                return;
            }
            mem.put(key, text);
        }
    }

    /** 读回下标：不限（含缺失）一律 0 */
    public static int positionOf(Map<String, Object> mem, String dim) {
        Object v = mem.get(keyFor(dim));
        return v instanceof Number ? Math.max(0, ((Number) v).intValue()) : 0;
    }

    /** 读回文案：不限（含缺失）一律空串 */
    public static String labelOf(Map<String, Object> mem, String dim) {
        Object v = mem.get(keyFor(dim));
        return v == null ? "" : String.valueOf(v);
    }

    /**
     * 按文案在下拉项里找下标——「按文案恢复」的唯一判据（卡 9）。
     *
     * <p>找不到、文案为空、命中的就是首项「不限」，一律回 0（= 不限），
     * <b>绝不退到相邻条目上</b>：条目改名或被删之后，用户看到的必须是「不限」，
     * 而不是一本没选过的书。恢复选中项的两条路（视图重建后的回填、下拉内容变化后的
     * 重填）都必须走这里，别各写一份。
     */
    public static int indexForLabel(List<String> names, String label) {
        if (names == null || label == null || label.isEmpty()) {
            return 0;
        }
        int idx = names.indexOf(label);
        return idx > 0 ? idx : 0;
    }

    /** 排序方式：负数自愈成 0，越界由调用方的 Spinner 自己兜（文案找不到就不回填） */
    public static int sortIndexOf(Map<String, Object> mem) {
        Object v = mem.get(KEY_SORT_INDEX);
        return v instanceof Number ? Math.max(0, ((Number) v).intValue()) : 0;
    }

    public static boolean sortReversedOf(Map<String, Object> mem) {
        return Boolean.TRUE.equals(mem.get(KEY_SORT_REVERSE));
    }

    /**
     * 把来自 SharedPreferences（或备份、或任何外部）的原始值洗一遍：
     * 只保留认识的键，负下标归 0，null 文案归空串，其余类型丢弃。
     */
    public static Map<String, Object> normalize(Map<String, Object> raw) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (String key : ALL_KEYS) {
            Object v = raw.get(key);
            if (v == null) {
                continue;
            }
            if (KEY_SORT_REVERSE.equals(key)) {
                if (v instanceof Boolean) {
                    out.put(key, v);
                }
            } else if (KEY_SORT_INDEX.equals(key) || isIndexDim(dimOfKey(key))) {
                if (v instanceof Number && ((Number) v).intValue() > 0) {
                    out.put(key, ((Number) v).intValue());
                }
            } else if (v instanceof String && !((String) v).isEmpty()) {
                out.put(key, v);
            }
        }
        return out;
    }

    /** 内存副本快照（只读视图），单测与调试用 */
    public Map<String, Object> snapshot() {
        return new LinkedHashMap<>(mem);
    }

    // ======================= 实例方法 =======================

    public int sortIndex() {
        return sortIndexOf(mem);
    }

    public void setSortIndex(int index) {
        if (index > 0) {
            mem.put(KEY_SORT_INDEX, index);
            sp.edit().putInt(KEY_SORT_INDEX, index).apply();
        } else {
            mem.remove(KEY_SORT_INDEX);
            sp.edit().remove(KEY_SORT_INDEX).apply();
        }
    }

    public boolean sortReversed() {
        return sortReversedOf(mem);
    }

    public void setSortReversed(boolean reversed) {
        mem.put(KEY_SORT_REVERSE, reversed);
        sp.edit().putBoolean(KEY_SORT_REVERSE, reversed).apply();
    }

    /** 该维度的记忆下标（不限 = 0） */
    public int position(String dim) {
        return positionOf(mem, dim);
    }

    /** 该维度的记忆文案（不限 = 空串） */
    public String label(String dim) {
        return labelOf(mem, dim);
    }

    /** Spinner 选中变化：内存 + prefs 双写；「不限」删键。不支持的维度（如来源）不写任何东西 */
    public void select(String dim, int position, String text) {
        if (!isSupportedDim(dim)) {
            return;
        }
        applySelection(mem, dim, position, text);
        SharedPreferences.Editor ed = sp.edit();
        String key = keyFor(dim);
        if (!mem.containsKey(key)) {
            ed.remove(key);
        } else if (isIndexDim(dim)) {
            ed.putInt(key, position);
        } else {
            ed.putString(key, text);
        }
        ed.apply();
    }

    /**
     * 一键清除筛选：六个维度全部回到「不限」（= 删键），排序与视图模式不动。
     *
     * <p>不能靠 Spinner 的选中监听去清：{@code setSelection(0)} 在已经是第 0 项时
     * 根本不会触发事件（AbsSpinner 只在位置真变时才发），漏清的那个维度重启后又会回来。
     */
    public void clearFilters() {
        SharedPreferences.Editor ed = sp.edit();
        for (String dim : SUPPORTED_DIMS) {
            String key = keyFor(dim);
            if (mem.remove(key) != null) {
                ed.remove(key);
            }
        }
        ed.apply();
    }

    private static String dimOfKey(String key) {
        for (String dim : SUPPORTED_DIMS) {
            if (keyFor(dim).equals(key)) {
                return dim;
            }
        }
        return "";
    }
}
