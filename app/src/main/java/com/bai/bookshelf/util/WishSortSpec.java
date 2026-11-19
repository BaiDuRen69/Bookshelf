package com.bai.bookshelf.util;

import androidx.annotation.Nullable;

import com.bai.bookshelf.data.entity.WishBook;
import com.google.gson.Gson;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 想读清单的组合排序配置（v5.9.8 T6）。
 *
 * <p>排序从「单枚举」升级为「键序列 + 每键方向」：勾选多个键时按固定组合优先序
 * （优先级 &gt; 价格 &gt; 书名 &gt; 添加时间）逐级比较，全同按加入时间倒序兜底。
 * 「手动排序」仍是单选独占——manual=true 时忽略键序列。
 *
 * <p>持久化走 JSON 字符串（SharedPreferences）；旧版只存一个 int，
 * 由 {@link #fromLegacy(int, String)} 映射成单键配置，方向与旧行为一致。
 * 纯函数、不碰 Android UI，整个比较逻辑可以在 JVM 单测里跑。
 */
public final class WishSortSpec {

    public static final String K_PRIORITY = "priority";
    public static final String K_PRICE = "price";
    public static final String K_TITLE = "title";
    public static final String K_TIME = "time";

    /** 固定组合优先序：勾了多个键时永远按这个次序逐级比较（与勾选顺序无关） */
    static final String[] PRECEDENCE = {K_PRIORITY, K_PRICE, K_TITLE, K_TIME};

    private static final Gson GSON = new Gson();

    /** 「手动排序」单选独占；为 true 时键序列被清空 */
    public boolean manual;

    /** 选中的键与方向；desc=true = 降序 */
    public final List<KeyDir> keys = new ArrayList<>();

    public static final class KeyDir {
        public String key = "";
        public boolean desc;
    }

    // ==================== 持久化与迁移 ====================

    public String toJson() {
        return GSON.toJson(this);
    }

    /** JSON 坏了 / 为空时回退默认（优先级降序，与旧默认行为一致） */
    public static WishSortSpec parse(@Nullable String json) {
        if (json != null && !json.trim().isEmpty()) {
            try {
                WishSortSpec spec = GSON.fromJson(json, WishSortSpec.class);
                if (spec != null) {
                    spec.sanitize();
                    return spec;
                }
            } catch (Exception ignored) {
                // 落到默认
            }
        }
        return defaultSpec();
    }

    /** 旧版 int 偏好 → 单键配置（方向与旧行为逐一对齐） */
    public static WishSortSpec fromLegacy(int legacyMode, @Nullable String json) {
        if (json != null && !json.trim().isEmpty()) {
            return parse(json); // 新配置已存在时迁移不生效
        }
        WishSortSpec spec = new WishSortSpec();
        if (legacyMode == 3) { // 手动
            spec.manual = true;
            return spec;
        }
        KeyDir k = new KeyDir();
        switch (legacyMode) {
            case 1: // 添加时间：旧代码新建在前 = 倒序
                k.key = K_TIME;
                k.desc = true;
                break;
            case 2: // 书名：Collator 升序
                k.key = K_TITLE;
                k.desc = false;
                break;
            case 0: // 优先级：旧 getAll 是 priority DESC
            default:
                k.key = K_PRIORITY;
                k.desc = true;
                break;
        }
        spec.keys.add(k);
        return spec;
    }

    public static WishSortSpec defaultSpec() {
        WishSortSpec spec = new WishSortSpec();
        KeyDir k = new KeyDir();
        k.key = K_PRIORITY;
        k.desc = true;
        spec.keys.add(k);
        return spec;
    }

    /** 切到手动排序：清空组合配置（验收 A6 第 4 条） */
    public void switchToManual() {
        manual = true;
        keys.clear();
    }

    /** 从手动切回组合排序：给一个非空默认，避免空配置 */
    public void switchFromManual() {
        if (keys.isEmpty()) {
            WishSortSpec def = defaultSpec();
            keys.addAll(def.keys);
        }
        manual = false;
    }

    private void sanitize() {
        if (manual) {
            keys.clear();
            return;
        }
        Map<String, Boolean> seen = new LinkedHashMap<>();
        for (KeyDir k : keys) {
            if (k != null && isKnownKey(k.key) && !seen.containsKey(k.key)) {
                seen.put(k.key, k.desc);
            }
        }
        keys.clear();
        for (Map.Entry<String, Boolean> e : seen.entrySet()) {
            KeyDir k = new KeyDir();
            k.key = e.getKey();
            k.desc = e.getValue();
            keys.add(k);
        }
    }

    private static boolean isKnownKey(String key) {
        return K_PRIORITY.equals(key) || K_PRICE.equals(key)
                || K_TITLE.equals(key) || K_TIME.equals(key);
    }

    // ==================== 展示 ====================

    /** 「优先级↓ 价格↑」式摘要；手动排序由调用方另给文案 */
    public String label() {
        if (keys.isEmpty()) {
            return "默认";
        }
        StringBuilder sb = new StringBuilder();
        for (String candidate : PRECEDENCE) {
            for (KeyDir k : keys) {
                if (k.key.equals(candidate)) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(keyName(k.key)).append(k.desc ? "↓" : "↑");
                }
            }
        }
        return sb.length() == 0 ? "默认" : sb.toString();
    }

    public static String keyName(String key) {
        switch (key) {
            case K_PRICE:
                return "价格";
            case K_TITLE:
                return "书名";
            case K_TIME:
                return "添加时间";
            default:
                return "优先级";
        }
    }

    // ==================== 比较 ====================

    /** 组合比较器：按固定优先序逐键比较，全同按加入时间倒序兜底 */
    public Comparator<WishBook> comparator() {
        final List<KeyDir> applied = new ArrayList<>(keys);
        return (a, b) -> {
            for (KeyDir k : applied) {
                int c = compareBy(a, b, k);
                if (c != 0) {
                    return c;
                }
            }
            return Long.compare(b.createdAt, a.createdAt); // 兜底：新的在前
        };
    }

    private static int compareBy(WishBook a, WishBook b, KeyDir k) {
        switch (k.key) {
            case K_PRIORITY:
                return k.desc
                        ? Integer.compare(b.priority, a.priority)
                        : Integer.compare(a.priority, b.priority);
            case K_PRICE: {
                // 0 = 没填价：无论升降都垫底，不让「没填」冒充 0 元
                boolean az = a.expectedPrice > 0;
                boolean bz = b.expectedPrice > 0;
                if (az != bz) {
                    return az ? -1 : 1;
                }
                if (!az) {
                    return 0;
                }
                int c = Double.compare(a.expectedPrice, b.expectedPrice);
                return k.desc ? -c : c;
            }
            case K_TITLE: {
                Collator collator = Collator.getInstance(Locale.CHINA);
                int c = collator.compare(nz(a.title), nz(b.title));
                return k.desc ? -c : c;
            }
            case K_TIME:
            default:
                return k.desc
                        ? Long.compare(b.createdAt, a.createdAt)
                        : Long.compare(a.createdAt, b.createdAt);
        }
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
