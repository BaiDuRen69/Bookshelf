package com.bai.bookshelf.ui.stats;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.ItemPurchaseDetailRowBinding;
import com.bai.bookshelf.databinding.SheetPurchaseDetailBinding;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;

import java.util.List;

/**
 * 「全部记录」底部半屏面板（v4.21）。
 *
 * <p>参照微信读书「阅读时长」页：柱状图<b>右上角</b>有一个「全部记录」按钮，
 * 点它从页面<b>底部升起</b>半屏面板，里面按时间顺序列出该周期的完整记录
 * （有记录的显示数值条，没记录的置灰显示「—」）。
 *
 * <p>本应用把微信读书的「阅读时长」换成了「购买书籍数量」。
 */
public class PurchaseDetailSheet {

    /** 一条记录 */
    public static class Row {
        /** 左侧标签，如「10月15日」 */
        public final String label;
        /** 数值；0 表示当天没有购买记录（置灰显示） */
        public final int count;
        /** 副标题（可选），如「周一」；为空则不显示 */
        @Nullable
        public final String sub;

        public Row(String label, int count, @Nullable String sub) {
            this.label = label;
            this.count = count;
            this.sub = sub;
        }
    }

    /** 行点击回调（可选）：参数为该行在列表中的下标 */
    public interface OnRowClickListener {
        void onRowClick(int index);
    }

    private PurchaseDetailSheet() {
    }

    /**
     * 显示底部面板。
     *
     * @param context  上下文（需是 Activity）
     * @param title    面板标题，如「2026年10月 全部记录」
     * @param summary  副标题（汇总说明），可为 null
     * @param rows     明细行
     * @param listener 行点击回调，可为 null
     * @return 创建好的 dialog（调用方如需提前关闭可持有）
     */
    public static BottomSheetDialog show(Context context, String title, @Nullable String summary,
                                        List<Row> rows, @Nullable OnRowClickListener listener) {
        SheetPurchaseDetailBinding b = SheetPurchaseDetailBinding.inflate(
                LayoutInflater.from(context));
        BottomSheetDialog dialog = new BottomSheetDialog(context);
        dialog.setContentView(b.getRoot());

        b.sheetTitle.setText(title);
        if (summary != null && !summary.isEmpty()) {
            b.sheetSummary.setText(summary);
            b.sheetSummary.setVisibility(View.VISIBLE);
        } else {
            b.sheetSummary.setVisibility(View.GONE);
        }

        int primary = ThemeManager.resolveColor(context, ThemeManager.ATTR_COLOR_PRIMARY);
        int textMain = resolveColor(context, R.color.text_main);
        int textSub = resolveColor(context, R.color.text_sub);

        // 找出最大值用于归一化权重条
        int max = 0;
        for (Row r : rows) {
            max = Math.max(max, r.count);
        }

        b.llSheetList.removeAllViews();
        for (int i = 0; i < rows.size(); i++) {
            Row r = rows.get(i);
            ItemPurchaseDetailRowBinding row =
                    ItemPurchaseDetailRowBinding.inflate(LayoutInflater.from(context));
            row.tvRowLabel.setText(r.label);
            // 副标题（星期等）单独一列，弱化显示
            if (r.sub != null && !r.sub.isEmpty()) {
                row.tvRowSub.setText(r.sub);
                row.tvRowSub.setVisibility(View.VISIBLE);
            } else {
                row.tvRowSub.setVisibility(View.GONE);
            }

            if (r.count > 0) {
                row.tvRowValue.setText(context.getString(R.string.stats_value_books, r.count));
                row.tvRowValue.setTextColor(textMain);
                row.tvRowLabel.setTextColor(textMain);
                row.vRowBar.setVisibility(View.VISIBLE);
                row.vRowBarBg.setVisibility(View.VISIBLE);
                // 权重条：主色段权重 = 本数，底槽段权重 = 最大值 - 本数，
                // 两者按权重瓜分槽宽，于是「4 本」的条正好是「2 本」的两倍长。
                // ⚠ 底槽权重不能置 0 —— LinearLayout 里唯一有 weight 的子 View
                // 会独占全部宽度，导致所有柱子一样长。
                LinearLayout.LayoutParams barLp = (LinearLayout.LayoutParams)
                        row.vRowBar.getLayoutParams();
                LinearLayout.LayoutParams bgLp = (LinearLayout.LayoutParams)
                        row.vRowBarBg.getLayoutParams();
                barLp.weight = r.count;
                bgLp.weight = Math.max(0, max - r.count);
                row.vRowBar.setLayoutParams(barLp);
                row.vRowBarBg.setLayoutParams(bgLp);
                tintDrawable(row.vRowBar.getBackground(), primary);
                tintDrawable(row.vRowBarBg.getBackground(),
                        resolveColor(context, R.color.surface_dim));
            } else {
                // 没有购买记录：整行置灰
                row.tvRowValue.setText("—");
                row.tvRowValue.setTextColor(textSub);
                row.tvRowLabel.setTextColor(textSub);
                row.tvRowSub.setTextColor(textSub);
                row.vRowBar.setVisibility(View.INVISIBLE);
                row.vRowBarBg.setVisibility(View.INVISIBLE);
            }

            final int index = i;
            if (listener != null) {
                // ⚠ 行的根布局必须在 XML 里写 android:clickable="true"，
                // 否则 LinearLayout 默认不可点击，setOnClickListener 后
                // uiautomator 仍报 click=false（实测行内 DOWN/UP 能到但 click 不触发）。
                row.getRoot().setOnClickListener(v -> listener.onRowClick(index));
            }

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            b.llSheetList.addView(row.getRoot(), lp);
        }

        b.sheetClose.setOnClickListener(v -> dialog.dismiss());

        dialog.show();

        // 半屏默认停在「半展开」状态：面板高度不超过屏幕 70%，内容超出可滚动
        // （微信读书的明细面板大约占屏幕 2/3，下方留一点背景可见）
        View sheet = dialog.findViewById(com.google.android.material.R.id.design_bottom_sheet);
        if (sheet != null) {
            BottomSheetBehavior<View> behavior = BottomSheetBehavior.from(sheet);
            int screenH = dialog.getWindow() != null
                    && dialog.getWindow().getDecorView() != null
                    ? dialog.getWindow().getDecorView().getHeight() : 0;
            if (screenH > 0) {
                behavior.setMaxHeight((int) (screenH * 0.70f));
                behavior.setPeekHeight((int) (screenH * 0.55f));
            }
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }

        return dialog;
    }

    /** 给 drawable 染色（权重条跟随主题主色） */
    private static void tintDrawable(@Nullable Drawable d, int color) {
        if (d != null) {
            d.setTint(color);
        }
    }

    private static int resolveColor(Context context, int resId) {
        return androidx.core.content.ContextCompat.getColor(context, resId);
    }

    /**
     * 生成汇总文案，如「4 天有购买记录 · 合计 10 本」。
     *
     * @param unitLabel 统计维度：0=按天（天），1=按月（月），2=按年（年）
     */
    public static String buildSummary(Context context, List<Row> rows, int unitLabel) {
        int active = 0;
        int total = 0;
        for (Row r : rows) {
            if (r.count > 0) {
                active++;
                total += r.count;
            }
        }
        String unit = unitLabel == 1 ? "个月" : (unitLabel == 2 ? "年" : "天");
        return context.getString(R.string.stats_sheet_summary, active, unit, total);
    }
}
