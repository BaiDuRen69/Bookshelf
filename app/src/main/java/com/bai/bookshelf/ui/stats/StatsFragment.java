package com.bai.bookshelf.ui.stats;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.FragmentStatsBinding;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.BookText;
import com.bai.bookshelf.util.ShelfNavigator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 统计页：藏书总览、藏书之最、我的阅读入口、作者/出版社/国家分布 Top 8。
 * 数据量小，直接在内存中聚合；分布行统一由 {@link DistRowBuilder} 渲染。
 */
public class StatsFragment extends Fragment {

    private FragmentStatsBinding b;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        b = FragmentStatsBinding.inflate(inflater, container, false);
        // 「我的阅读」二级页（v2.8，月/年/总统计）
        b.cardReading.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), ReadingStatsActivity.class)));
        // 「全部 ›」进入国家分布二级页面（完整列表）
        b.tvCountriesMore.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), CountryActivity.class)));
        // 「全部 ›」进入出版社分布二级页面（完整列表）
        b.tvPublishersMore.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), PublisherActivity.class)));
        // 「全部 ›」进入作者分布二级页面（完整列表）
        b.tvAuthorsMore.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), AuthorActivity.class)));
        return b.getRoot();
    }

    @Override
    public void onResume() {
        super.onResume();
        onVisible();
    }

    @Override
    public void onHiddenChanged(boolean hidden) {
        super.onHiddenChanged(hidden);
        // v4.1：MainActivity 切 Tab 改为 show/hide 复用实例，切回统计页时
        // 由这里重新聚合（数据可能在书架页被增删改过）
        if (!hidden) {
            onVisible();
        }
    }

    private void onVisible() {
        compute();
    }

    /** 后台线程聚合数据，回主线程渲染 */
    private void compute() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Book> books = BookSanitizer.normalizeAll(app.db().bookDao().getAll());

            // ---- 总览 ----
            int total = books.size();
            double totalPrice = 0;
            double totalSpent = 0;   // 总花费（购入价求和）
            double totalWords = 0;   // 总字数（解析「28万字」「320千字」等写法）
            int readDone = 0, reading = 0, readNone = 0;
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

            // 组装文案
            String overview = String.format(Locale.getDefault(),
                    "藏书 %d 册\n总定价 ¥%.2f\n总花费 ¥%.2f\n总字数约 %.1f 万字\n已读 %d · 在读 %d · 未读 %d",
                    total, totalPrice, totalSpent, totalWords / 10000.0,
                    readDone, reading, readNone);

            StringBuilder extremes = new StringBuilder();
            if (total == 0) {
                extremes.append("暂无藏书");
            } else {
                if (mostExpensive != null && mostExpensive.listPrice > 0) {
                    extremes.append("最贵：《").append(bareTitle(mostExpensive.title)).append("》 ¥")
                            .append(String.format(Locale.getDefault(), "%.2f", mostExpensive.listPrice)).append("\n");
                }
                if (mostPages != null && mostPages.pages > 0) {
                    extremes.append("最厚：《").append(bareTitle(mostPages.title)).append("》 ")
                            .append(mostPages.pages).append(" 页\n");
                }
                if (mostWords != null) {
                    // 统一换算成「万字」展示（原始写法可能是 320千字/320000）
                    extremes.append("字数最多：《").append(bareTitle(mostWords.title)).append("》 约 ")
                            .append(String.format(Locale.getDefault(), "%.1f",
                                    BookSanitizer.parseWords(mostWords.wordCount) / 10000.0))
                            .append(" 万字\n");
                }
                if (earliest != null) {
                    extremes.append("最早出版：《").append(bareTitle(earliest.title)).append("》 ")
                            .append(earliest.publishDate);
                }
                if (mostPrintings != null) {
                    extremes.append("\n印次最多：《").append(bareTitle(mostPrintings.title)).append("》 ")
                            .append("第 ").append(BookSanitizer.parsePrintingCount(mostPrintings.printing)).append(" 次印刷");
                }
            }

            List<Map.Entry<String, Integer>> topAuthors = topN(authorCount, 8);
            List<Map.Entry<String, Integer>> topPublishers = topN(publisherCount, 8);
            List<Map.Entry<String, Integer>> topCountries = topN(countryCount, 8);

            app.ui(() -> {
                if (b == null) {
                    return;
                }
                b.tvOverview.setText(overview);
                b.tvExtremes.setText(extremes.toString().trim());
                DistRowBuilder rows = distRows();
                rows.fill(b.containerAuthors, topAuthors, DistRowBuilder.emptyHint("作者", null));
                rows.fill(b.containerPublishers, topPublishers, DistRowBuilder.emptyHint("出版社", null));
                rows.fill(b.containerCountries, topCountries, DistRowBuilder.emptyHint("国家/地区", null));
            });
        });
    }

    /** 去书名号（统一走 util/BookText，全串替换所有《》字符） */
    private static String bareTitle(String title) {
        return BookText.bareTitle(title);
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
