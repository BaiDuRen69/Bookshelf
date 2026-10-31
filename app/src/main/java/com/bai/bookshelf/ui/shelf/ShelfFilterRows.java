package com.bai.bookshelf.ui.shelf;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.ui.widget.CalendarPicker;
import com.bai.bookshelf.util.BookSeries;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 书架「更多筛选」里的系列 / 价格 / 日期行与弹窗（v5.7，从 ShelfFragment 抽出）。
 * 筛选状态仍由书架保存，本类只负责行的创建、文案与弹窗交互。
 */
public final class ShelfFilterRows {

    private final Activity activity;
    private final java.util.function.Supplier<List<Book>> allBooks;
    private final ShelfFilterState state;
    private final Runnable onChanged;
    private TextView seriesRow;
    private TextView priceRow;
    private TextView purchaseRow;
    private TextView entryRow;

    public ShelfFilterRows(Activity activity, java.util.function.Supplier<List<Book>> allBooks,
                           ShelfFilterState state, Runnable onChanged) {
        this.activity = activity;
        this.allBooks = allBooks;
        this.state = state;
        this.onChanged = onChanged;
    }

    /** 依次追加系列（有系列时）/ 价格 / 购入日期 / 录入日期四行 */
    public void appendTo(LinearLayout parent) {
        if (!BookSeries.names(allBooks.get()).isEmpty()) {
            seriesRow = buildRow("系列：" + seriesLabel());
            seriesRow.setOnClickListener(v -> showSeriesDialog(seriesRow));
            parent.addView(seriesRow);
        }
        priceRow = buildRow(priceLabel());
        priceRow.setOnClickListener(v -> showPriceDialog(priceRow));
        parent.addView(priceRow);

        purchaseRow = buildRow(dateLabel("购入日期", state.purchaseRangeKeys()));
        purchaseRow.setOnClickListener(v -> showDateDialog(purchaseRow, "购入日期", true));
        parent.addView(purchaseRow);

        entryRow = buildRow(dateLabel("录入日期", state.entryRangeKeys()));
        entryRow.setOnClickListener(v -> showDateDialog(entryRow, "录入日期", false));
        parent.addView(entryRow);
    }

    /** 清除全部筛选后刷新行文案 */
    public void refreshRows() {
        if (seriesRow != null) {
            seriesRow.setText("系列：" + seriesLabel());
        }
        if (priceRow != null) {
            priceRow.setText(priceLabel());
        }
        if (purchaseRow != null) {
            purchaseRow.setText(dateLabel("购入日期", state.purchaseRangeKeys()));
        }
        if (entryRow != null) {
            entryRow.setText(dateLabel("录入日期", state.entryRangeKeys()));
        }
    }

    private TextView buildRow(String text) {
        TextView row = new TextView(activity);
        row.setTextSize(15);
        row.setTextColor(ThemeManager.resolveColor(activity, R.attr.on_surface));
        int dp14 = dp(14);
        row.setPadding(dp14, dp(12), dp14, dp(12));
        row.setBackgroundResource(R.drawable.bg_dialog_row);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        row.setText(text);
        return row;
    }

    private String seriesLabel() {
        String filter = state.series;
        return filter == null || filter.isEmpty() ? "不限" : filter;
    }

    private String priceLabel() {
        Double min = state.priceMin;
        Double max = state.priceMax;
        if (min == null && max == null) {
            return "价格：不限";
        }
        String from = min == null ? "不限" : ShelfFilterDialogs.fmtPrice(min);
        String to = max == null ? "不限" : ShelfFilterDialogs.fmtPrice(max);
        return "价格：" + from + " — " + to + " 元";
    }

    private String dateLabel(String label, int[] range) {
        if (range == null) {
            return label + "：不限";
        }
        return label + "：" + formatKey(range[0]) + " — " + formatKey(range[1]);
    }

    private void showSeriesDialog(TextView row) {
        List<String> names = BookSeries.names(allBooks.get());
        Map<String, Integer> counts = new HashMap<>();
        for (Book book : allBooks.get()) {
            String name = BookSeries.name(book);
            if (!name.isEmpty()) {
                Integer old = counts.get(name);
                counts.put(name, old == null ? 1 : old + 1);
            }
        }
        String[] items = new String[names.size() + 1];
        items[0] = "不限";
        int checked = 0;
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            Integer count = counts.get(name);
            items[i + 1] = name + (count == null ? "" : "（" + count + " 本）");
            if (name.equalsIgnoreCase(state.series)) {
                checked = i + 1;
            }
        }
        new MaterialAlertDialogBuilder(activity)
                .setTitle("按系列筛选")
                .setSingleChoiceItems(items, checked, (dialog, which) -> {
                    state.series = which == 0 ? "" : names.get(which - 1);
                    row.setText("系列：" + seriesLabel());
                    dialog.dismiss();
                    onChanged.run();
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showPriceDialog(TextView row) {
        ShelfFilterDialogs.showPriceDialog(activity, state.priceMin, state.priceMax,
                (min, max) -> {
                    state.priceMin = min;
                    state.priceMax = max;
                    row.setText(priceLabel());
                    onChanged.run();
                });
    }

    private void showDateDialog(TextView row, String label, boolean purchase) {
        int[] current = purchase ? state.purchaseRangeKeys() : state.entryRangeKeys();
        CalendarPicker.showRange(activity, label,
                current == null ? 0 : current[0],
                current == null ? 0 : current[1],
                (startKey, endKey) -> {
                    if (purchase) {
                        state.setPurchaseRange(startKey, endKey);
                    } else {
                        state.setEntryRange(startKey, endKey);
                    }
                    row.setText(dateLabel(label, new int[]{startKey, endKey}));
                    onChanged.run();
                },
                () -> {
                    if (purchase) {
                        state.setPurchaseRange(0, 0);
                    } else {
                        state.setEntryRange(0, 0);
                    }
                    row.setText(label + "：不限");
                    onChanged.run();
                });
    }

    private static String formatKey(int key) {
        return String.format(Locale.ROOT, "%04d-%02d-%02d",
                key / 10000, key / 100 % 100, key % 100);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
