package com.bai.bookshelf.ui.edit;

import android.app.Activity;

import com.bai.bookshelf.databinding.ActivityBookEditBinding;
import com.google.android.material.chip.Chip;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Locale;

/**
 * 编辑页套装分摊快捷填充（v5.7，从 BookEditActivity 抽出）：
 * 读取最近一次分摊结果，在实付价格下方生成可点击填入的 Chip。
 */
public final class SplitHint {

    private SplitHint() {
    }

    public static void show(Activity activity, ActivityBookEditBinding b) {
        try {
            android.content.SharedPreferences sp =
                    activity.getSharedPreferences("split_prefs", Activity.MODE_PRIVATE);
            JSONArray history = new JSONArray(sp.getString("history", "[]"));
            if (history.length() == 0) {
                return;
            }
            JSONArray items = history.getJSONObject(0).getJSONArray("items");
            b.containerSplitHint.setVisibility(android.view.View.VISIBLE);
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String name = item.getString("name");
                double alloc = item.getDouble("alloc");
                Chip chip = new Chip(activity);
                chip.setText("《" + name + "》¥" + fmtMoney(alloc));
                chip.setCheckable(false);
                chip.setOnClickListener(v -> b.etPurchasePrice.setText(fmtMoney(alloc)));
                b.chipGroupSplit.addView(chip);
            }
        } catch (Exception ignored) {
            // 历史数据异常就不显示提示，不影响录入
        }
    }

    /** 金额展示：去掉多余的 .00 */
    private static String fmtMoney(double value) {
        if (value == Math.floor(value) && !Double.isInfinite(value)) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.getDefault(), "%.2f", value);
    }
}
