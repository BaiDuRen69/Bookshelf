package com.bai.bookshelf.ui.shelf;

import android.content.Context;
import android.text.InputType;
import android.widget.LinearLayout;
import android.widget.Toast;

import com.bai.bookshelf.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.math.BigDecimal;

/**
 * 书架筛选弹窗（v5.4，从 ShelfFragment 抽出）：
 * 目前只承载价格区间；状态由调用方保存，应用/清除通过回调返回。
 */
public final class ShelfFilterDialogs {

    public interface PriceCallback {
        void onApply(Double min, Double max);
    }

    private ShelfFilterDialogs() {
    }

    public static String fmtPrice(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }

    /** 价格区间弹窗：最低价 / 最高价两个输入框，留空 = 不限；清除回调 (null, null) */
    public static void showPriceDialog(Context ctx, Double priceMin, Double priceMax,
                                       PriceCallback callback) {
        int dp8 = dp(ctx, 8);
        int dp20 = dp(ctx, 20);
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp20, dp(ctx, 4), dp20, dp(ctx, 4));

        TextInputLayout minTil = new TextInputLayout(ctx);
        minTil.setHint("最低价（元）");
        TextInputEditText minEdit = new TextInputEditText(ctx);
        minEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        minEdit.setSingleLine(true);
        if (priceMin != null) {
            minEdit.setText(fmtPrice(priceMin));
        }
        minTil.addView(minEdit);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp1.topMargin = dp8;
        root.addView(minTil, lp1);

        TextInputLayout maxTil = new TextInputLayout(ctx);
        maxTil.setHint("最高价（元）");
        TextInputEditText maxEdit = new TextInputEditText(ctx);
        maxEdit.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        maxEdit.setSingleLine(true);
        if (priceMax != null) {
            maxEdit.setText(fmtPrice(priceMax));
        }
        maxTil.addView(maxEdit);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp2.topMargin = dp8;
        root.addView(maxTil, lp2);

        new MaterialAlertDialogBuilder(ctx)
                .setTitle("价格（按定价）")
                .setView(root)
                .setNeutralButton("清除", (d, w) -> callback.onApply(null, null))
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton("应用", (d, w) -> {
                    Double min = parsePrice(minEdit.getText().toString());
                    Double max = parsePrice(maxEdit.getText().toString());
                    if (min != null && max != null && min > max) {
                        Toast.makeText(ctx, "最低价高于最高价", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    callback.onApply(min, max);
                })
                .show();
    }

    private static Double parsePrice(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int dp(Context ctx, int value) {
        return Math.round(value * ctx.getResources().getDisplayMetrics().density);
    }
}
