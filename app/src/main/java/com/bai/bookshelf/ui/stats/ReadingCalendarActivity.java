package com.bai.bookshelf.ui.stats;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivityReadingCalendarBinding;
import com.bai.bookshelf.ui.BaseActivity;
import com.bai.bookshelf.util.BookSanitizer;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ReadingCalendar;

import java.util.List;
import java.util.Map;

/**
 * 阅读日历二级页（v5.7）：全库阅读记录按天汇总，颜色越深表示当天读过的书越多。
 */
public class ReadingCalendarActivity extends BaseActivity {

    private ActivityReadingCalendarBinding b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityReadingCalendarBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());
        InsetsHelper.applyTopInset(b.toolbar);
        InsetsHelper.applyBottomInset(b.calendarPanel.llDayDetail);

        // 二级页不需要底部面板的拖拽把手与关闭按钮
        b.calendarPanel.sheetHandle.setVisibility(View.GONE);
        b.calendarPanel.sheetClose.setVisibility(View.GONE);
        // 日历滚动区占满剩余高度，底部当天详情固定
        ViewGroup.LayoutParams lp = b.calendarPanel.calendarScroll.getLayoutParams();
        if (lp instanceof LinearLayout.LayoutParams) {
            LinearLayout.LayoutParams llp = (LinearLayout.LayoutParams) lp;
            llp.height = 0;
            llp.weight = 1;
            b.calendarPanel.calendarScroll.setLayoutParams(llp);
        }

        load();
    }

    private void load() {
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            List<Book> books = BookSanitizer.normalizeAll(app.db().bookDao().getAll());
            Map<String, List<String>> titles = ReadingCalendar.titlesByDay(books);
            Map<String, Integer> counts = new java.util.LinkedHashMap<>();
            int max = 0;
            for (Map.Entry<String, List<String>> e : titles.entrySet()) {
                counts.put(e.getKey(), e.getValue().size());
                max = Math.max(max, e.getValue().size());
            }
            final Map<String, Integer> fCounts = counts;
            final Map<String, List<String>> fTitles = titles;
            final int fMax = max;
            app.ui(() -> {
                if (b == null) {
                    return;
                }
                int[] ymd = ReadingCalendar.latestYmd(fCounts);
                String summary = getString(R.string.reading_calendar_all_summary,
                        fCounts.size(), fMax);
                PurchaseCalendarSheet.bindPage(this, b.calendarPanel,
                        getString(R.string.reading_calendar_title),
                        summary, fCounts, fTitles,
                        new PurchaseCalendarSheet.Start(false, ymd[0], ymd[1]),
                        PurchaseCalendarSheet.readingCountLabels());
            });
        });
    }
}
