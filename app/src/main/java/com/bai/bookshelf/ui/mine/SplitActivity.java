package com.bai.bookshelf.ui.mine;

import androidx.appcompat.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import com.bai.bookshelf.ui.BaseActivity;
import androidx.core.content.ContextCompat;

import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.databinding.ActivitySplitBinding;
import com.bai.bookshelf.util.PriceSplitter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 套装书籍购入价分摊：输入实付总额与每本书的定价，按比例分摊。
 * 历史记录保存在 SharedPreferences（split_prefs/history，JSON），只保留最近 10 次。
 */
public class SplitActivity extends BaseActivity {

    private static final String PREFS = "split_prefs";
    private static final String KEY_HISTORY = "history";
    private static final int MAX_HISTORY = 10;

    // v4.53：从系列页进入时带上书目与 ids，分摊结果可写回购入价
    public static final String EXTRA_BOOK_IDS = "split_book_ids";
    public static final String EXTRA_BOOK_NAMES = "split_book_names";
    public static final String EXTRA_BOOK_PRICES = "split_book_prices";
    public static final String EXTRA_SERIES_NAME = "split_series_name";

    private ActivitySplitBinding b;
    private long[] targetIds;
    private String[] targetNames;
    private List<PriceSplitter.AllocResult> lastResults;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySplitBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());

        targetIds = getIntent().getLongArrayExtra(EXTRA_BOOK_IDS);
        targetNames = getIntent().getStringArrayExtra(EXTRA_BOOK_NAMES);
        double[] targetPrices = getIntent().getDoubleArrayExtra(EXTRA_BOOK_PRICES);
        String seriesName = getIntent().getStringExtra(EXTRA_SERIES_NAME);
        if (targetIds != null && targetNames != null && targetIds.length != targetNames.length) {
            targetIds = null; // 数据不齐时宁可不写回，也不猜对应关系
        }

        if (targetNames != null && targetNames.length > 0) {
            for (int i = 0; i < targetNames.length; i++) {
                double price = targetPrices != null && i < targetPrices.length ? targetPrices[i] : 0;
                addBookRow(targetNames[i], price > 0 ? fmt(price) : "");
            }
            if (seriesName != null && !seriesName.trim().isEmpty()) {
                b.toolbar.setTitle(seriesName.trim() + " · 套装分摊");
            }
        } else {
            // 默认两行（套装至少两本）
            addBookRow("A 书", "10");
            addBookRow("B 书", "90");
            b.etTotalPaid.setText("10"); // 示例默认值，方便直接体验：A 分摊 1 元、B 分摊 9 元
        }

        b.btnAddBook.setOnClickListener(v -> addBookRow("", ""));
        b.btnCompute.setOnClickListener(v -> compute());
        b.btnApply.setOnClickListener(v -> applyToBooks());
        renderHistory();
    }

    /** 添加一条书籍输入行 */
    private void addBookRow(String name, String price) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_split_book, b.containerBooks, false);
        EditText etName = row.findViewById(R.id.et_book_name);
        EditText etPrice = row.findViewById(R.id.et_price);
        etName.setText(name);
        etPrice.setText(price);
        row.findViewById(R.id.btn_remove).setOnClickListener(v -> {
            // 至少保留一行
            if (b.containerBooks.getChildCount() > 1) {
                b.containerBooks.removeView(row);
                updateApplyButton();
            } else {
                Toast.makeText(this, "至少保留一本书", Toast.LENGTH_SHORT).show();
            }
        });
        b.containerBooks.addView(row);
        updateApplyButton();
    }

    /** 执行分摊计算并渲染结果 + 写入历史 */
    private void compute() {
        double totalPaid = parseDouble(b.etTotalPaid.getText().toString(), -1);
        List<PriceSplitter.BookInput> inputs = new ArrayList<>();
        for (int i = 0; i < b.containerBooks.getChildCount(); i++) {
            View row = b.containerBooks.getChildAt(i);
            String name = ((EditText) row.findViewById(R.id.et_book_name)).getText().toString().trim();
            double price = parseDouble(((EditText) row.findViewById(R.id.et_price)).getText().toString(), -1);
            if (name.isEmpty()) {
                name = "第 " + (i + 1) + " 本";
            }
            inputs.add(new PriceSplitter.BookInput(i + 1, name, price));
        }

        List<PriceSplitter.AllocResult> results;
        try {
            results = PriceSplitter.split(totalPaid, inputs);
        } catch (PriceSplitter.SplitException e) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("无法分摊")
                    .setMessage(e.getMessage())
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }

        // 渲染结果
        b.tvResultLabel.setVisibility(View.VISIBLE);
        b.containerResult.removeAllViews();
        for (PriceSplitter.AllocResult r : results) {
            TextView tv = new TextView(this);
            tv.setText(String.format(Locale.getDefault(), "《%s》 定价 %.2f → 分摊 %.2f 元（%.1f%%）",
                    r.bookName, r.price, r.allocatedPrice, r.ratio * 100));
            tv.setTextSize(14);
            tv.setPadding(0, dp(6), 0, dp(6));
            b.containerResult.addView(tv);
        }

        saveHistory(totalPaid, results);
        renderHistory();
        lastResults = results;
        updateApplyButton();
    }

    // ======================= 写回系列藏书（v4.53） =======================

    /** 只有从系列页进入、且书目行未被增删改名时，才允许写回 */
    private boolean rowsUnchanged() {
        if (targetIds == null || targetNames == null
                || b.containerBooks.getChildCount() != targetIds.length) {
            return false;
        }
        for (int i = 0; i < targetNames.length; i++) {
            View row = b.containerBooks.getChildAt(i);
            String name = ((EditText) row.findViewById(R.id.et_book_name))
                    .getText().toString().trim();
            if (!name.equals(targetNames[i])) {
                return false;
            }
        }
        return true;
    }

    private void updateApplyButton() {
        boolean canApply = targetIds != null && lastResults != null && rowsUnchanged();
        b.btnApply.setVisibility(canApply ? View.VISIBLE : View.GONE);
    }

    private void applyToBooks() {
        if (targetIds == null || lastResults == null) {
            return;
        }
        if (!rowsUnchanged()) {
            updateApplyButton();
            Toast.makeText(this, "书目行已调整，无法写回；请返回系列页重新分摊",
                    Toast.LENGTH_LONG).show();
            return;
        }
        final int count = targetIds.length;
        new MaterialAlertDialogBuilder(this)
                .setTitle("写入购入价")
                .setMessage("把分摊结果写入这 " + count + " 本书的实付价？原有购入价会被覆盖。")
                .setPositiveButton("写入", (d, w) -> doApplyToBooks())
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void doApplyToBooks() {
        final List<PriceSplitter.AllocResult> results = new ArrayList<>(lastResults);
        final long[] ids = targetIds.clone();
        Toast.makeText(this, "正在写入…", Toast.LENGTH_SHORT).show();
        BookshelfApp app = BookshelfApp.get();
        app.io(() -> {
            long now = System.currentTimeMillis();
            int changed = 0;
            for (int i = 0; i < ids.length && i < results.size(); i++) {
                Book book = app.db().bookDao().getById(ids[i]);
                if (book == null) {
                    continue;
                }
                book.purchasePrice = results.get(i).allocatedPrice;
                book.updatedAt = now;
                book.dirty = true;
                app.db().bookDao().update(book);
                changed++;
            }
            final int fChanged = changed;
            app.ui(() -> {
                Toast.makeText(this, "已写入 " + fChanged + " 本书的购入价",
                        Toast.LENGTH_SHORT).show();
                finish();
            });
        });
    }

    /** 写入历史（头部插入，只保留最近 10 次） */
    private void saveHistory(double totalPaid, List<PriceSplitter.AllocResult> results) {
        try {
            JSONArray history = loadHistory();
            JSONObject record = new JSONObject();
            record.put("time", System.currentTimeMillis());
            record.put("totalPaid", totalPaid);
            JSONArray items = new JSONArray();
            for (PriceSplitter.AllocResult r : results) {
                JSONObject item = new JSONObject();
                item.put("name", r.bookName);
                item.put("price", r.price);
                item.put("alloc", r.allocatedPrice);
                item.put("ratio", r.ratio);
                items.put(item);
            }
            record.put("items", items);

            JSONArray next = new JSONArray();
            next.put(record);
            for (int i = 0; i < history.length() && i < MAX_HISTORY - 1; i++) {
                next.put(history.getJSONObject(i));
            }
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_HISTORY, next.toString()).apply();
        } catch (Exception ignored) {
            // 历史写不进去不影响主功能
        }
    }

    private JSONArray loadHistory() {
        try {
            return new JSONArray(getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getString(KEY_HISTORY, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    /** 渲染历史记录列表 */
    private void renderHistory() {
        JSONArray history = loadHistory();
        b.tvHistoryLabel.setVisibility(history.length() > 0 ? View.VISIBLE : View.GONE);
        b.containerHistory.removeAllViews();
        try {
            for (int i = 0; i < history.length(); i++) {
                JSONObject record = history.getJSONObject(i);
                double totalPaid = record.getDouble("totalPaid");
                String time = new java.text.SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                        .format(new java.util.Date(record.getLong("time")));
                StringBuilder sb = new StringBuilder();
                sb.append(time).append(" · 实付 ").append(fmt(totalPaid)).append(" 元\n");
                JSONArray items = record.getJSONArray("items");
                for (int j = 0; j < items.length(); j++) {
                    JSONObject item = items.getJSONObject(j);
                    sb.append("　《").append(item.getString("name")).append("》 ")
                            .append(fmt(item.getDouble("alloc"))).append(" 元");
                    if (j < items.length() - 1) {
                        sb.append("\n");
                    }
                }
                TextView tv = new TextView(this);
                tv.setText(sb.toString());
                tv.setTextSize(13);
                tv.setTextColor(com.bai.bookshelf.util.ThemeManager.resolveColor(this, R.attr.on_surface));
                tv.setBackgroundResource(R.drawable.bg_chip);
                tv.setPadding(dp(10), dp(8), dp(10), dp(8));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(6);
                tv.setLayoutParams(lp);
                b.containerHistory.addView(tv);
            }
        } catch (Exception ignored) {
        }
    }

    /** 金额展示：去掉多余的 .00 */
    private static String fmt(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) {
            return String.valueOf((long) v);
        }
        return String.format(Locale.getDefault(), "%.2f", v);
    }

    private double parseDouble(String s, double fallback) {
        try {
            return Double.parseDouble(s.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
