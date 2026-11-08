package com.bai.bookshelf.util;

import java.util.LinkedHashSet;
import java.util.Set;

/** 底栏 Tab 的顺序 / 隐藏配置（纯逻辑，方便 JVM 单测）。 */
public final class NavOrder {

    public static final int TAB_SHELF = 0;
    public static final int TAB_STATS = 1;
    public static final int TAB_CHECKIN = 2;
    /** 设置入口，永远不允许隐藏 */
    public static final int TAB_MINE = 3;
    public static final int TAB_COUNT = 4;

    public static final int[] DEFAULT = {TAB_SHELF, TAB_STATS, TAB_CHECKIN, TAB_MINE};

    private NavOrder() {
    }

    /** "2,0,1,3" → [2,0,1,3]；空串/非法输入退化为默认顺序 */
    public static int[] parse(String csv) {
        if (csv == null || csv.trim().isEmpty()) {
            return DEFAULT.clone();
        }
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            try {
                int v = Integer.parseInt(part.trim());
                if (v >= 0 && v < TAB_COUNT) {
                    seen.add(v);
                }
            } catch (NumberFormatException ignored) {
                // 坏值跳过，后面按默认顺序补齐
            }
        }
        return normalize(toArray(seen));
    }

    public static String join(int[] order) {
        int[] normalized = normalize(order);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < normalized.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(normalized[i]);
        }
        return sb.toString();
    }

    public static Set<Integer> parseSet(String csv) {
        Set<Integer> out = new LinkedHashSet<>();
        if (csv == null || csv.trim().isEmpty()) {
            return out;
        }
        for (String part : csv.split(",")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // 坏值直接丢弃
            }
        }
        return out;
    }

    /** 按 0..3 升序输出，保证同一集合的字符串稳定（配置比对用） */
    public static String joinSet(Set<Integer> hidden) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (int tab = 0; tab < TAB_COUNT; tab++) {
            if (hidden != null && hidden.contains(tab)) {
                if (!first) {
                    sb.append(',');
                }
                sb.append(tab);
                first = false;
            }
        }
        return sb.toString();
    }

    /** 去重、丢弃越界、补齐缺失项，保证长度 4；异常输入退化为默认顺序 */
    public static int[] normalize(int[] raw) {
        if (raw == null) {
            return DEFAULT.clone();
        }
        LinkedHashSet<Integer> seen = new LinkedHashSet<>();
        for (int v : raw) {
            if (v >= 0 && v < TAB_COUNT) {
                seen.add(v);
            }
        }
        for (int v : DEFAULT) {
            seen.add(v);
        }
        return toArray(seen);
    }

    /** 去重、只保留 0..2；「我的」永远不能被隐藏 */
    public static Set<Integer> normalizeHidden(Set<Integer> raw) {
        Set<Integer> out = new LinkedHashSet<>();
        if (raw != null) {
            for (int v : raw) {
                if (v >= 0 && v < TAB_MINE) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    public static boolean visible(int tab, Set<Integer> hidden) {
        return tab >= 0 && tab < TAB_COUNT && (hidden == null || !hidden.contains(tab));
    }

    public static int firstVisible(int[] order, Set<Integer> hidden) {
        for (int tab : normalize(order)) {
            if (visible(tab, hidden)) {
                return tab;
            }
        }
        // 「我的」不会被隐藏，走到这里说明配置异常；仍返回它，保证一定有页面
        return TAB_MINE;
    }

    /** 启动页不能指向被隐藏的 Tab：指到了就退到第一个可见页 */
    public static int ensureVisible(int tab, Set<Integer> hidden) {
        return visible(tab, hidden) ? tab : firstVisible(DEFAULT, hidden);
    }

    public static String label(int tab) {
        switch (tab) {
            case TAB_SHELF:
                return "书架";
            case TAB_STATS:
                return "统计";
            case TAB_CHECKIN:
                return "打卡";
            case TAB_MINE:
                return "我的";
            default:
                return "";
        }
    }

    private static int[] toArray(Set<Integer> set) {
        int[] out = new int[set.size()];
        int n = 0;
        for (int v : set) {
            out[n++] = v;
        }
        return out;
    }
}
