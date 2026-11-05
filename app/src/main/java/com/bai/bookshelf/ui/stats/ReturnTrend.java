package com.bai.bookshelf.ui.stats;

import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.util.DateKeys;

import java.util.ArrayList;
import java.util.List;

/**
 * 归还趋势（v5.9.1 三步）：把已归还的借书记录按自然月数一数，凑不够样本就不画。
 *
 * <p>只做分桶与文案，样本够不够仍用 StatsLend 已产出的归还笔数与全应用统一的
 * MIN_SAMPLE 判断，这里不另立口径。
 */
public final class ReturnTrend {

    /** 只看近 6 个自然月：个人书房借还量小，摊到 12 个月基本全是零柱 */
    public static final int MONTHS = 6;

    public static class Series {
        public final List<String> labels;
        public final List<Integer> values;
        /** 落在窗口内的归还笔数 */
        public final int inWindow;

        Series(List<String> labels, List<Integer> values, int inWindow) {
            this.labels = labels;
            this.values = values;
            this.inWindow = inWindow;
        }
    }

    private ReturnTrend() {
    }

    /** 近 months 个自然月（含当月）的逐月归还笔数；归还日期解析不出来的记录跳过 */
    public static Series of(List<BorrowRecord> records, int todayKey, int months) {
        int[] counts = new int[Math.max(1, months)];
        int current = monthNum(todayKey);
        int hit = 0;
        if (records != null) {
            for (BorrowRecord r : records) {
                if (r == null || r.status != BorrowRecord.STATUS_RETURNED) {
                    continue;
                }
                int key = DateKeys.parseKey(r.returnDate);
                int back = key > 0 ? current - monthNum(key) : -1;
                if (back < 0 || back >= counts.length) {
                    continue;
                }
                counts[counts.length - 1 - back]++;
                hit++;
            }
        }
        List<String> labels = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            int month = monthOf(current - (counts.length - 1 - i));
            labels.add((month) + "月");
            values.add(counts[i]);
        }
        return new Series(labels, values, hit);
    }

    /** 数据不足时的解释：说清是「一笔都没还过」还是「还过但样本太薄不画趋势」 */
    public static String notEnoughText(int returnedTotal, int minSample, int months) {
        if (returnedTotal <= 0) {
            return "数据不足：还没有归还记录，收回借出的书后这里开始累计";
        }
        return "数据不足：已归还 " + returnedTotal + " 笔，少于 " + minSample
                + " 笔不画近 " + months + " 个月趋势";
    }

    /** yyyyMMdd → 连续月序，跨年比较只用它做减法 */
    static int monthNum(int dayKey) {
        return (dayKey / 10000) * 12 + dayKey / 100 % 100;
    }

    /** 连续月序 → 1..12 */
    static int monthOf(int monthNum) {
        int m = monthNum % 12;
        return m == 0 ? 12 : m;
    }
}
