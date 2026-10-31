package com.bai.bookshelf.ui.stats;

import android.app.Activity;
import android.view.View;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.util.BookFlags;
import com.bai.bookshelf.databinding.ActivityReadingStatsBinding;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 统计页「总」视图：首购至今的相伴时长、总量数字（买/录入/已读/在读/未读）
 * 与每年购买本数的柱状图。
 */
public class StatsTotalView {

    private final Activity activity;
    private final ActivityReadingStatsBinding b;

    public StatsTotalView(Activity activity, ActivityReadingStatsBinding b) {
        this.activity = activity;
        this.b = b;
    }

    public void render(List<Book> books) {
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

        int[] first = StatsText.parseYmd(firstPurchase);
        if (first == null) {
            b.tvTotalDuration.setText("暂无购买记录");
            b.tvTotalRange.setText("录入书籍时填写购买日期后即可统计");
            b.tvTotalCompanion.setVisibility(View.GONE);
            b.chartTotal.setData(new ArrayList<>(), new ArrayList<>());
            renderCompanionLine(); // 无购买记录也展示「相伴」
            return;
        }

        Calendar firstCal = Calendar.getInstance();
        firstCal.clear();
        firstCal.set(first[0], first[1], first[2]);
        long now = System.currentTimeMillis();
        long elapsedMillis = Math.max(0, now - firstCal.getTimeInMillis());

        // v2.9：大字改为「已阅读 X 小时 X 分钟」（首购至今换算，数字放大突出）
        long hours = elapsedMillis / 3600000L;
        long minutes = (elapsedMillis / 60000L) % 60;
        b.tvTotalDuration.setText(StatsText.buildDurationText(hours, minutes));

        // 小字 1：首购至今多少天
        b.tvTotalRange.setText(String.format(Locale.getDefault(),
                "%s至今，已阅读 %d 天",
                StatsText.formatYmd(first[0], first[1] + 1, first[2]),
                elapsedMillis / 86400000L));

        // 小字 2：首次打开 App 至今，与小书房相伴多少天
        renderCompanionLine();

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

    /** 「首次打开 App 至今 · 与小书房相伴了 N 天」（v2.9；first_launch_at 由 BookshelfApp 持久化） */
    private void renderCompanionLine() {
        long firstLaunch = BookshelfApp.firstLaunchAt();
        if (firstLaunch <= 0) {
            b.tvTotalCompanion.setVisibility(View.GONE);
            return;
        }
        b.tvTotalCompanion.setVisibility(View.VISIBLE);
        Calendar firstCal = Calendar.getInstance();
        firstCal.setTimeInMillis(firstLaunch);
        long days = Math.max(0, (System.currentTimeMillis() - firstLaunch) / 86400000L);
        b.tvTotalCompanion.setText(String.format(Locale.getDefault(),
                "%s至今 · 与小书房相伴了 %d 天",
                StatsText.formatYmd(firstCal.get(Calendar.YEAR),
                        firstCal.get(Calendar.MONTH) + 1,
                        firstCal.get(Calendar.DAY_OF_MONTH)), days));
    }
}
