package com.bai.bookshelf.ui.mine;

import android.app.AlertDialog;
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
import com.bai.bookshelf.databinding.ActivitySplitBinding;
import com.bai.bookshelf.util.PriceSplitter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 套装书籍购入价分摊：输入实付总额与每本书的定价，按比例分摊。
 * 历史记录保存在 SharedPreferences（split_prefs/history，JSON），只保留最近 10 次。
 */
public class SplitActivity extends BaseActivity {

    private static final String PREFS = "split_prefs";
    private static final String KEY_HISTORY = "history";
    private static final int MAX_HISTORY = 10;

    private ActivitySplitBinding b;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySplitBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());

        // 默认两行（套装至少两本）
        addBookRow("A 书", "10");
        addBookRow("B 书", "90");
        b.etTotalPaid.setText("10"); // 示例默认值，方便直接体验：A 分摊 1 元、B 分摊 9 元

        b.btnAddBook.setOnClickListener(v -> addBookRow("", ""));
        b.btnCompute.setOnClickListener(v -> compute());
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
            } else {
                Toast.makeText(this, "至少保留一本书", Toast.LENGTH_SHORT).show();
            }
        });
        b.containerBooks.addView(row);
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
            new AlertDialog.Builder(this)
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
                tv.setTextColor(ContextCompat.getColor(this, R.color.text_main));
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
