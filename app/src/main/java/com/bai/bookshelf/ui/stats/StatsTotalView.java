package com.bai.bookshelf.ui.stats;

import android.app.Activity;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.util.BookFlags;
import com.bai.bookshelf.util.StatsHoldings;
import com.bai.bookshelf.databinding.ActivityReadingStatsBinding;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 统计页「总」视图：累计花费、总量数字（买/录入/已读/在读/未读）
 * 与每年购买本数的柱状图。
 */
public class StatsTotalView {

    private final Activity activity;
    private final ActivityReadingStatsBinding b;

    public StatsTotalView(Activity activity, ActivityReadingStatsBinding b) {
        this.activity = activity;
        this.b = b;
    }

    public void render(List<Book> books, StatsHoldings.Holdings hold) {
        int bought = 0;
        int done = 0;
        int reading = 0;
        int none = 0;
        int dropped = 0;
        String firstPurchase = null;
        int entered = 0;
        for (Book book : books) {
            if (BookFlags.countsAsOwned(book)) {
                entered++;
            }
            // v5.7：借阅来源的书不参与购买/价格统计
            if (!BookFlags.isBorrowSource(book)
                    && book.purchaseDate != null && !book.purchaseDate.trim().isEmpty()) {
                bought++;
                if (firstPurchase == null
                        || book.purchaseDate.trim().compareTo(firstPurchase) < 0) {
                    firstPurchase = book.purchaseDate.trim();
                }
            }
            if (book.readStatus == Book.READ_DONE) {
                done++;
            } else if (book.readStatus == Book.READING) {
                reading++;
            } else if (book.readStatus == Book.READ_DROPPED) {
                dropped++;
            } else {
                none++;
            }
        }
        // v2.9：数字单独大字展示（标签在布局里），这里只填数字
        b.tvTotalBought.setText(String.valueOf(bought));
        b.tvTotalEntered.setText(String.valueOf(entered));
        b.tvTotalDone.setText(String.valueOf(done));
        b.tvTotalReading.setText(String.valueOf(reading));
        b.tvTotalNone.setText(String.valueOf(none));
        b.tvTotalDropped.setText(String.valueOf(dropped));

        // v5.9.5：大字改为累计花费（自购实付按册累计），
        // 原先这里按「首购日→今天的日历时间」换算成小时分钟，口径是错的
        b.tvTotalCost.setText(hold.paid > 0 ? StatsText.formatMoney(hold.paid) : "暂无花费");

        int[] first = StatsText.parseYmd(firstPurchase);
        if (first == null) {
            b.tvTotalRange.setText(hold.paid > 0
                    ? "购买日期未填，无法按年统计"
                    : "录入购买价格后即可统计");
            b.chartTotal.setData(new ArrayList<>(), new ArrayList<>());
            return;
        }

        b.tvTotalRange.setText(String.format(Locale.getDefault(), "%s首购至今 · 已购 %d 种",
                StatsText.formatYmd(first[0], first[1] + 1, first[2]), bought));

        // 每年购买本数
        TreeMap<Integer, Integer> yearly = new TreeMap<>();
        for (Book book : books) {
            if (BookFlags.isBorrowSource(book)) {
                continue;
            }
            int[] pd = StatsText.parseYmd(book.purchaseDate);
            if (pd != null) {
                yearly.merge(pd[0], 1, Integer::sum);
            }
        }
        List<String> labels = new ArrayList<>();
        List<Integer> values = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : yearly.entrySet()) {
            labels.add(String.valueOf(e.getKey()));
            values.add(e.getValue());
        }
        b.chartTotal.setUnit("种");
        b.chartTotal.setData(labels, values);
        // v4.21：切换页签时清掉高亮
        b.chartTotal.setHighlightedIndex(-1);
    }
}
