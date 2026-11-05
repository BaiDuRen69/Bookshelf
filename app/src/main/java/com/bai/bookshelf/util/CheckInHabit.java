package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.CheckIn;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阅读习惯的事实源选择（第 ④ 步，纯 JVM 可单测）。
 *
 * <p>某本书累计打卡 ≥ 3 次时，以「有打卡的自然日」为准不再用阅读记录的起止区间
 * 铺日子——后者会把「在读且没填结束日期」的书一直铺到今天，算出「连着 1627 天」
 * 这种废话。样本不足时回退区间算法，并在洞察文案末尾标「按区间估算」。
 */
public final class CheckInHabit {

    public static final int MIN_CHECK_INS_PER_BOOK = 3;

    private CheckInHabit() {
    }

    /** 是否存在一本书的打卡记录数 ≥ 3 */
    public static boolean useCheckIns(List<CheckIn> all) {
        if (all == null || all.isEmpty()) {
            return false;
        }
        Map<Long, Integer> byBook = new HashMap<>();
        for (CheckIn c : all) {
            if (c == null) {
                continue;
            }
            Integer old = byBook.get(c.bookId);
            int n = old == null ? 1 : old + 1;
            if (n >= MIN_CHECK_INS_PER_BOOK) {
                return true;
            }
            byBook.put(c.bookId, n);
        }
        return false;
    }

    /** 有打卡的自然日去重（一天多本算一天） */
    public static Collection<Integer> dayKeys(List<CheckIn> all) {
        Set<Integer> out = new HashSet<>();
        if (all != null) {
            for (CheckIn c : all) {
                if (c != null) {
                    out.add(c.dayKey);
                }
            }
        }
        return out;
    }

    /** 洞察文案里的口径说明；回退时末尾必须出现「按区间估算」 */
    public static String sourceNote(boolean fromCheckIns) {
        return fromCheckIns
                ? "（按每日打卡的自然日统计）"
                : "（按阅读记录的起止区间推算，在读且没填结束日期的书算每天在读 · 按区间估算）";
    }
}
