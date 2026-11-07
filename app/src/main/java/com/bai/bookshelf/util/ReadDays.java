package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.CheckIn;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阅读天数的双轨口径（v5.9.1）。
 *
 * <p>两轨并存、互不替换：
 * <ul>
 *   <li><b>模糊</b>（历史默认）——用户填起止，天数按区间算，含首尾，再扣掉借在外的日子
 *       （{@link ReadingRecords#days(ReadingRecords.Record, List)}，v5.8.33 的口径原样保留）；</li>
 *   <li><b>精确</b>——「哪几天读过」只有 check_ins 说了算，天数就是这份日集合里不同日子的个数。</li>
 * </ul>
 *
 * <p>精确轨<b>不</b>做外借扣减：日集合本身就是事实，打过卡的那天就是读了，
 * 再去扣一个区间只会把真实数字改小。反过来，模糊轨的扣减逻辑一个字没动。
 *
 * <p>这里只吃已经取出来的日集合，不碰 DAO：查哪一轮的日子由调用方
 * （{@code CheckInDao.getByBookSeq} / {@code getDayKeys}）负责，保持纯 JVM 可测。
 */
public final class ReadDays {

    private ReadDays() {
    }

    /** 这一场是不是精确口径 */
    public static boolean isPrecise(ReadingRecords.Record r) {
        return r != null && r.mode == ReadingRecords.MODE_PRECISE;
    }

    /** 精确口径的天数：日集合里不同日子的个数；空集合就是 0 天，不偷偷退回区间口径 */
    public static int precise(Collection<Integer> dayKeys) {
        if (dayKeys == null || dayKeys.isEmpty()) {
            return 0;
        }
        Set<Integer> distinct = new HashSet<>(dayKeys);
        return distinct.size();
    }

    /**
     * 某一轮的日集合（去重 + 升序）：从 check_ins 行里挑出属于这个 seq 的日子。
     *
     * <p>只按 seq 过滤，不按 bookId——传进来的行必须已经是同一本书
     * （{@code CheckInDao.getByBookSeq} 就是这么查的），这里再滤一次只会掩盖调用错误。
     */
    public static List<Integer> keysOf(List<CheckIn> rows, int seq) {
        List<Integer> out = new ArrayList<>();
        if (rows == null) {
            return out;
        }
        Set<Integer> seen = new HashSet<>();
        for (CheckIn c : rows) {
            if (c != null && c.seq == seq && seen.add(c.dayKey)) {
                out.add(c.dayKey);
            }
        }
        Collections.sort(out);
        return out;
    }

    /** 全量打卡行按 书 → 轮次 → 日集合 分组（统计日历一次取全后用，避免逐本逐轮查） */
    public static Map<Long, Map<Integer, List<Integer>>> byBook(List<CheckIn> rows) {
        Map<Long, Map<Integer, List<Integer>>> out = new HashMap<>();
        if (rows == null) {
            return out;
        }
        Map<Long, List<CheckIn>> grouped = new HashMap<>();
        for (CheckIn c : rows) {
            if (c == null) {
                continue;
            }
            List<CheckIn> list = grouped.get(c.bookId);
            if (list == null) {
                list = new ArrayList<>();
                grouped.put(c.bookId, list);
            }
            list.add(c);
        }
        for (Map.Entry<Long, List<CheckIn>> e : grouped.entrySet()) {
            Set<Integer> seqs = new HashSet<>();
            for (CheckIn c : e.getValue()) {
                seqs.add(c.seq);
            }
            Map<Integer, List<Integer>> bySeq = new HashMap<>();
            for (int seq : seqs) {
                bySeq.put(seq, keysOf(e.getValue(), seq));
            }
            out.put(e.getKey(), bySeq);
        }
        return out;
    }
}
