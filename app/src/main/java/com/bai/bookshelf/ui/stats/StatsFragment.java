package com.bai.bookshelf.ui.stats;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.FragmentStatsBinding;
import com.bai.bookshelf.databinding.ItemStatsExtremeBinding;
import com.bai.bookshelf.ui.detail.BookDetailActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.BookText;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ShelfNavigator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 统计页：藏书总览、藏书之最、购书统计入口、作者/出版社/国家分布 Top 8。
 * 数据量小，直接在内存中聚合；分布行统一由 {@link DistRowBuilder} 渲染。
 */
public class StatsFragment extends Fragment {

    private FragmentStatsBinding b;
    private int lastFinishedThisYear;
    private int lastAvgDays;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentStatsBinding.inflate(inflater, container, false);
        // v4.18：Android 15+ 强制 edge-to-edge，给顶部栏加状态栏内边距，
        // 避免「统计」标题被状态栏遮挡（工具栏是固定高度，工具会同时把高度补上）
        InsetsHelper.applyTopInset(b.toolbar);
        // 「购书统计」二级页（v2.8，月/年/总统计）
        b.cardReading.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ReadingStatsActivity.class)));
        // v4.28：0 本时的统一空态（与书架同一套插画 + 主按钮）
        b.cardYearReport.setOnClickListener(v ->
                startActivity(new Intent(requireContext(),
                        com.bai.bookshelf.ui.stats.StudyReportActivity.class)));
        b.tvGoalSet.setOnClickListener(v -> showReadingGoalDialog());
        b.emptyState.tvEmptyTitle.setText("还没有可统计的书");
        b.emptyState.tvEmptyDesc.setText("录入第一本书后，这里会显示收藏、已读与花费统计");
        b.emptyState.btnEmptyCta.setOnClickListener(v ->
                startActivity(new Intent(requireContext(),
                        com.bai.bookshelf.ui.edit.BookEditActivity.class)));
        // 「全部 ›」进入国家分布二级页面（完整列表）
        b.tvCountriesMore.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), CountryActivity.class)));
        // 「全部 ›」进入出版社分布二级页面（完整列表）
        b.tvPublishersMore.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), PublisherActivity.class)));
        // 「全部 ›」进入作者分布二级页面（完整列表）
        b.tvAuthorsMore.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AuthorActivity.class)));
        // v4.3：改为 Room 响应式观察（与 v4.2 书架页同模式）——注册即推送首次数据，
        // 之后 books 表任何变化自动重算，切 Tab 回来无需手动刷新，视图重建也不会空白
        BookshelfApp.get().db().bookDao().getAllLive()
                .observe(getViewLifecycleOwner(), this::onBooksPushed);
        return b.getRoot();
    }

    /** 阅读目标卡（v4.44）：年度目标存 SharedPreferences，按年重置 */
    private void bindReadingGoal(int finished, int avgDays) {
        if (b == null) {
            return;
        }
        int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        android.content.SharedPreferences prefs =
                requireContext().getSharedPreferences("ui_prefs", 0);
        int goalYear = prefs.getInt("reading_goal_year", 0);
        int goal = prefs.getInt("reading_goal", 24);
        if (goalYear != year) {
            goal = 24;
            prefs.edit().putInt("reading_goal_year", year)
                    .putInt("reading_goal", goal).apply();
        }
        if (goal < 1) {
            goal = 1;
        }
        b.tvGoalSummary.setText("今年读完 " + finished + " 本 · 目标 " + goal + " 本");
        b.pbGoal.setProgress(Math.min(100, finished * 100 / goal));
        b.tvGoalStreak.setText(avgDays > 0
                ? "平均 " + avgDays + " 天读完一本"
                : "把状态改成「在读 / 已读」，这里会自动记录开始与读完日期");
    }

    private void showReadingGoalDialog() {
        com.bai.bookshelf.databinding.DialogInputBinding input =
                com.bai.bookshelf.databinding.DialogInputBinding.inflate(getLayoutInflater());
        input.inputLayout.setHint("今年想读完几本？");
        input.edit.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        int year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
        int goal = requireContext().getSharedPreferences("ui_prefs", 0)
                .getInt("reading_goal", 24);
        input.edit.setText(String.valueOf(goal));
        input.edit.setSelection(input.edit.getText().length());
        new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                .setTitle("年度阅读目标")
                .setView(input.getRoot())
                .setPositiveButton("保存", (d, w) -> {
                    int value;
                    try {
                        value = Integer.parseInt(input.edit.getText().toString().trim());
                    } catch (Exception e) {
                        value = goal;
                    }
                    if (value < 1) {
                        value = 1;
                    }
                    requireContext().getSharedPreferences("ui_prefs", 0).edit()
                            .putInt("reading_goal_year", year)
                            .putInt("reading_goal", value).apply();
                    bindReadingGoal(lastFinishedThisYear, lastAvgDays);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** Room 推送回调（主线程）：books 已是最新快照，交给后台聚合 */
    private void onBooksPushed(java.util.List<Book> pushed) {
        computeFrom(pushed);
    }

    /** 后台线程聚合数据，回主线程渲染（books 由 LiveData 推送给出） */
    private void computeFrom(java.util.List<Book> raw) {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Book> books = BookSanitizer.normalizeAll(raw);

            // ---- 总览 ----
            int total = books.size();
            double totalPrice = 0;
            double totalSpent = 0;   // 总花费（购入价求和）
            double totalWords = 0;   // 总字数（解析「28万字」「320千字」等写法）
            int readDone = 0, reading = 0, readNone = 0;
            int finishedThisYear = 0;
            long readDaysSum = 0;
            int readDaysCount = 0;
            final int currentYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR);
            // ---- 之最 ----
            Book mostExpensive = null, mostPages = null, earliest = null, mostWords = null, mostPrintings = null;
            // ---- 分布 ----
            Map<String, Integer> authorCount = new HashMap<>();
            Map<String, Integer> publisherCount = new HashMap<>();
            Map<String, Integer> countryCount = new HashMap<>();

            for (Book book : books) {
                totalPrice += book.listPrice;
                totalSpent += book.purchasePrice;
                totalWords += BookSanitizer.parseWords(book.wordCount);
                if (!book.readingFinishDate.isEmpty()) {
                    int finishKey = com.bai.bookshelf.util.BorrowReminder
                            .parseKey(book.readingFinishDate);
                    if (finishKey / 10000 == currentYear) {
                        finishedThisYear++;
                    }
                    int startKey = com.bai.bookshelf.util.BorrowReminder
                            .parseKey(book.readingStartDate);
                    if (startKey > 0 && finishKey >= startKey) {
                        readDaysSum += com.bai.bookshelf.util.BorrowReminder
                                .daysBetween(startKey, finishKey) + 1;
                        readDaysCount++;
                    }
                }
                if (book.readStatus == Book.READ_DONE) {
                    readDone++;
                } else if (book.readStatus == Book.READING) {
                    reading++;
                } else {
                    readNone++;
                }
                if (mostExpensive == null || book.listPrice > mostExpensive.listPrice) {
                    mostExpensive = book;
                }
                if (mostPages == null || book.pages > mostPages.pages) {
                    mostPages = book;
                }
                // 字数最多（wordCount 是字符串，先解析再比较）
                double words = BookSanitizer.parseWords(book.wordCount);
                if (words > 0 && (mostWords == null || words > BookSanitizer.parseWords(mostWords.wordCount))) {
                    mostWords = book;
                }
                // 出版日期是字符串，直接按字典序比较（yyyy 开头时等价于时间序）
                if (!book.publishDate.isEmpty()
                        && (earliest == null || book.publishDate.compareTo(earliest.publishDate) < 0)) {
                    earliest = book;
                }
                // 印次最多：printing 格式 yyyy-mm|N，取 | 后的数字
                int printCount = BookSanitizer.parsePrintingCount(book.printing);
                if (printCount > 0 && (mostPrintings == null
                        || printCount > BookSanitizer.parsePrintingCount(mostPrintings.printing))) {
                    mostPrintings = book;
                }
                if (!book.author.isEmpty()) {
                    authorCount.merge(book.author, 1, Integer::sum);
                }
                if (!book.publisher.isEmpty()) {
                    publisherCount.merge(book.publisher, 1, Integer::sum);
                }
                if (book.country != null && !book.country.isEmpty()) {
                    countryCount.merge(book.country, 1, Integer::sum);
                }
            }

            final int fFinished = finishedThisYear;
            final int fAvgDays = readDaysCount > 0
                    ? (int) Math.round((double) readDaysSum / readDaysCount) : 0;
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                lastFinishedThisYear = fFinished;
                lastAvgDays = fAvgDays;
                bindReadingGoal(fFinished, fAvgDays);
            });

            // 组装文案（v4.25：不在这里拼长文本，交给 hero 数字与分布条）
            final int fTotal = total;
            final int fReadDone = readDone;
            final int fReading = reading;
            final int fReadNone = readNone;
            final double fTotalPrice = totalPrice;
            final double fTotalSpent = totalSpent;
            final double fTotalWords = totalWords;
            final Book fMostExpensive = mostExpensive;
            final Book fMostPages = mostPages;
            final Book fMostWords = mostWords;
            final Book fEarliest = earliest;
            final Book fMostPrintings = mostPrintings;

            // v4.31：最近录入的 4 本，用于 hero 封面拼贴
            List<Book> recent = new ArrayList<>(books);
            recent.sort((x, y) -> Long.compare(y.createdAt, x.createdAt));
            final List<Book> fRecent = recent.size() > 4 ? recent.subList(0, 4) : recent;

            List<Map.Entry<String, Integer>> topAuthors = topN(authorCount, 8);
            List<Map.Entry<String, Integer>> topPublishers = topN(publisherCount, 8);
            List<Map.Entry<String, Integer>> topCountries = topN(countryCount, 8);

            app.ui(() -> {
                if (b == null) {
                    return;
                }
                // v4.28：0 本时收成一张空态卡，避免五张卡都在说「暂无数据」
                boolean empty = fTotal == 0;
                b.emptyStateContainer.setVisibility(empty ? View.VISIBLE : View.GONE);
                b.cardStatsHero.setVisibility(empty ? View.GONE : View.VISIBLE);
                b.cardReading.setVisibility(empty ? View.GONE : View.VISIBLE);
                b.cardAuthors.setVisibility(empty ? View.GONE : View.VISIBLE);
                b.cardPublishers.setVisibility(empty ? View.GONE : View.VISIBLE);
                b.cardCountries.setVisibility(empty ? View.GONE : View.VISIBLE);
                b.cardYearReport.setVisibility(empty ? View.GONE : View.VISIBLE);

                // v4.30：藏书总数 600ms count-up，数字不再是一次性跳出
                android.animation.ValueAnimator countUp =
                        android.animation.ValueAnimator.ofInt(0, fTotal);
                countUp.setDuration(600);
                countUp.addUpdateListener(a ->
                        b.tvStatsTotal.setText(String.valueOf(a.getAnimatedValue())));
                countUp.start();
                b.tvStatsReadDone.setText(String.valueOf(fReadDone));
                b.tvStatsReading.setText(String.valueOf(fReading));
                b.tvStatsUnread.setText(String.valueOf(fReadNone));
                b.tvStatsPrice.setText(String.format(Locale.getDefault(), "¥%.2f", fTotalPrice));
                b.tvStatsSpent.setText(String.format(Locale.getDefault(), "¥%.2f", fTotalSpent));
                b.tvStatsWords.setText(String.format(Locale.getDefault(),
                        "%.1f 万字", fTotalWords / 10000.0));
                buildExtremes(fMostExpensive, fMostPages, fMostWords,
                        fEarliest, fMostPrintings);
                bindCoverFan(fRecent);
                DistRowBuilder rows = distRows();
                rows.fill(b.containerAuthors, topAuthors, DistRowBuilder.emptyHint("作者", null));
                rows.fill(b.containerPublishers, topPublishers, DistRowBuilder.emptyHint("出版社", null));
                rows.fill(b.containerCountries, topCountries, DistRowBuilder.emptyHint("国家/地区", null));
            });
        });
    }

    /** hero 封面拼贴：最近 4 本书的封面扇形叠放（v4.31） */
    private void bindCoverFan(List<Book> recent) {
        ImageView[] views = {b.ivFan1, b.ivFan2, b.ivFan3, b.ivFan4};
        for (int i = 0; i < views.length; i++) {
            if (i >= recent.size()) {
                views[i].setVisibility(View.GONE);
                continue;
            }
            views[i].setVisibility(View.VISIBLE);
            com.bai.bookshelf.util.CoverLoader.load(views[i], recent.get(i));
        }
    }

    /** 藏书之最：三张带封面的 mini 卡 + 最早出版/印次最多的补充行（v4.25） */
    private void buildExtremes(Book mostExpensive, Book mostPages, Book mostWords,
                               Book earliest, Book mostPrintings) {
        b.llExtremes.removeAllViews();
        List<Extreme> items = new ArrayList<>();
        if (mostExpensive != null && mostExpensive.listPrice > 0) {
            items.add(new Extreme("最贵", mostExpensive,
                    String.format(Locale.getDefault(), "¥%.2f", mostExpensive.listPrice)));
        }
        if (mostPages != null && mostPages.pages > 0) {
            items.add(new Extreme("最厚", mostPages, mostPages.pages + " 页"));
        }
        if (mostWords != null) {
            items.add(new Extreme("字数最多", mostWords,
                    String.format(Locale.getDefault(), "%.1f 万字",
                            BookSanitizer.parseWords(mostWords.wordCount) / 10000.0)));
        }

        boolean hasExtremes = !items.isEmpty();
        b.tvExtremesTitle.setVisibility(hasExtremes ? View.VISIBLE : View.GONE);
        b.llExtremes.setVisibility(hasExtremes ? View.VISIBLE : View.GONE);
        if (!hasExtremes) {
            b.tvExtremesExtra.setVisibility(View.GONE);
            return;
        }

        for (int i = 0; i < items.size() && i < 3; i++) {
            Extreme item = items.get(i);
            ItemStatsExtremeBinding card =
                    ItemStatsExtremeBinding.inflate(getLayoutInflater(), b.llExtremes, false);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            if (i > 0) {
                lp.setMarginStart(dp(8));
            }
            card.getRoot().setLayoutParams(lp);
            card.tvLabel.setText(item.label);
            card.tvTitle.setText(BookText.bareTitle(item.book.title));
            card.tvMetric.setText(item.metric);
            loadCover(card.ivCover, item.book);
            card.getRoot().setOnClickListener(v -> {
                Intent intent = new Intent(requireContext(), BookDetailActivity.class);
                intent.putExtra(BookDetailActivity.EXTRA_BOOK, item.book);
                startActivity(intent);
            });
            b.llExtremes.addView(card.getRoot());
        }
        // 不足 3 张时补占位，保持三列等宽，避免单张卡被拉成整屏
        for (int i = items.size(); i < 3; i++) {
            View spacer = new View(requireContext());
            spacer.setLayoutParams(new LinearLayout.LayoutParams(0, 1, 1f));
            b.llExtremes.addView(spacer);
        }

        StringBuilder extra = new StringBuilder();
        if (earliest != null && !earliest.publishDate.isEmpty()) {
            extra.append("最早出版：").append(BookText.bareTitle(earliest.title))
                    .append(" · ").append(earliest.publishDate);
        }
        if (mostPrintings != null) {
            if (extra.length() > 0) {
                extra.append("\n");
            }
            extra.append("印次最多：").append(BookText.bareTitle(mostPrintings.title))
                    .append(" · 第 ").append(BookSanitizer.parsePrintingCount(mostPrintings.printing))
                    .append(" 次印刷");
        }
        b.tvExtremesExtra.setText(extra.toString().trim());
        b.tvExtremesExtra.setVisibility(extra.length() > 0 ? View.VISIBLE : View.GONE);
    }

    /** mini 卡封面：统一走 CoverLoader */
    private void loadCover(ImageView iv, Book book) {
        com.bai.bookshelf.util.CoverLoader.load(iv, book);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static final class Extreme {
        final String label;
        final Book book;
        final String metric;

        Extreme(String label, Book book, String metric) {
            this.label = label;
            this.book = book;
            this.metric = metric;
        }
    }

    /** 分布行渲染器（v3.0 抽出公共组件，点击行跳书架搜索） */
    private DistRowBuilder distRows() {
        return new DistRowBuilder(requireContext(), name -> ShelfNavigator.search(requireActivity(), name));
    }

    /** 计数 Map → 按数量倒序的前 N 名 */
    private List<Map.Entry<String, Integer>> topN(Map<String, Integer> map, int n) {
        List<Map.Entry<String, Integer>> list = new ArrayList<>(map.entrySet());
        list.sort((x, y) -> y.getValue() - x.getValue());
        return list.size() > n ? list.subList(0, n) : list;
    }
}
