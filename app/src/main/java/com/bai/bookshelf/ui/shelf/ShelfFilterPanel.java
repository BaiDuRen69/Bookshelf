package com.bai.bookshelf.ui.shelf;

import com.bai.bookshelf.util.Typo;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.FragmentShelfBinding;
import com.bai.bookshelf.util.ThemeManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * 书架的筛选面板（v5.6 从 ShelfFragment 抽出）：
 * 高频 4 个胶囊 + 「更多」面板（地点/渠道/国家 + 日期/价格/系列）、
 * 统一圆角单选弹窗、更多角标与横向滚动渐变。
 */
public class ShelfFilterPanel {

    private final FragmentShelfBinding b;
    private final ShelfFilterState state;
    private final Runnable clearAllFilters;
    private final java.util.function.IntConsumer onChannelPicked;
    private final Runnable onFilterChanged;
    private final java.util.function.Consumer<LinearLayout> appendExtraRows;

    public ShelfFilterPanel(FragmentShelfBinding b, ShelfFilterState state,
                            Runnable clearAllFilters,
                            java.util.function.IntConsumer onChannelPicked,
                            Runnable onFilterChanged,
                            java.util.function.Consumer<LinearLayout> appendExtraRows) {
        this.b = b;
        this.state = state;
        this.clearAllFilters = clearAllFilters;
        this.onChannelPicked = onChannelPicked;
        this.onFilterChanged = onFilterChanged;
        this.appendExtraRows = appendExtraRows;
    }

    /** 高频 4 个胶囊 + 更多面板；筛选弹层统一走主题圆角弹窗 */
    public void setup() {
        bindPill(b.spCategory, "分类");
        bindPill(b.spTag, "标签");
        bindPill(b.spStock, "状态");
        bindPill(b.spReadFilter, "阅读");
        b.btnMoreFilters.setOnClickListener(v -> showMore());
        b.hsvFilters.setOnScrollChangeListener((v, sx, sy, osx, osy) -> updateFade());
        b.hsvFilters.post(this::updateFade);
        updateMoreFilterPill();
    }

    /** 胶囊点击改为统一单选弹窗，阻止 Spinner 自带的系统方角弹层 */
    private void bindPill(Spinner spinner, String label) {
        spinner.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                showChoice(spinner, label, null);
            }
            return true;
        });
    }

    public void updateFade() {
        if (b == null) {
            return;
        }
        b.vFilterFade.setVisibility(
                b.hsvFilters.canScrollHorizontally(1) ? View.VISIBLE : View.GONE);
    }

    /** 更多筛选：地点 / 渠道 / 国家 + 日期/价格/系列 */
    private void showMore() {
        Context ctx = b.getRoot().getContext();
        LinearLayout root = new LinearLayout(ctx);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, dp(4), pad, dp(4));
        buildRow(root, "地点", b.spLocation);
        buildRow(root, "渠道", b.spChannel);
        buildRow(root, "国家/地区", b.spCountry);
        appendExtraRows.accept(root);
        new MaterialAlertDialogBuilder(ctx)
                .setTitle("更多筛选")
                .setView(root)
                // v4.40：左下角一键清空全部筛选（快捷胶囊 + 地点/渠道/国家/价格/两个日期）
                .setNeutralButton("清除全部筛选", (d, w) -> clearAllFilters.run())
                .setNegativeButton("完成", null)
                .show();
    }

    private TextView buildRow(LinearLayout parent, String label, Spinner spinner) {
        Context ctx = b.getRoot().getContext();
        TextView row = new TextView(ctx);
        row.setText(rowText(label, spinner));
        Typo.text(row, R.dimen.text_body);
        row.setTextColor(ThemeManager.resolveColor(ctx, R.attr.on_surface));
        row.setPadding(dp(14), dp(12), dp(14), dp(12));
        row.setBackgroundResource(R.drawable.bg_dialog_row);
        row.setClickable(true);
        row.setFocusable(true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        row.setLayoutParams(lp);
        row.setOnClickListener(v -> showChoice(spinner, label, row));
        parent.addView(row);
        return row;
    }

    private String rowText(String label, Spinner spinner) {
        boolean none = spinner.getSelectedItemPosition() <= 0;
        Object item = spinner.getSelectedItem();
        String value = none || item == null ? "不限" : item.toString();
        return label + "：" + value;
    }

    /** 统一圆角单选弹窗：用于所有筛选胶囊与更多筛选 */
    private void showChoice(Spinner spinner, String label, TextView row) {
        Context ctx = b.getRoot().getContext();
        android.widget.Adapter adapter = spinner.getAdapter();
        if (adapter == null || adapter.getCount() == 0) {
            Toast.makeText(ctx, "筛选数据还在加载…", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] items = new String[adapter.getCount()];
        for (int i = 0; i < adapter.getCount(); i++) {
            Object item = adapter.getItem(i);
            items[i] = item == null ? "" : item.toString();
        }
        if (items.length > 0) {
            // 首项在胶囊关闭态兼作栏目名（分类/标签/…），弹窗里显示为「不限」
            items[0] = "不限";
        }
        new MaterialAlertDialogBuilder(ctx)
                .setTitle(label)
                .setSingleChoiceItems(items, Math.max(0, spinner.getSelectedItemPosition()),
                        (dialog, which) -> {
                            dialog.dismiss();
                            spinner.setSelection(which);
                            // 兜底：隐藏的额外 Spinner 未完成 layout 时可能不派发 onItemSelected
                            if (spinner == b.spChannel) {
                                onChannelPicked.accept(which);
                            }
                            if (row != null) {
                                row.setText(rowText(label, spinner));
                            }
                            onFilterChanged.run();
                        })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    /** 更多胶囊：有额外筛选时显示角标数量并切成选中态 */
    public void updateMoreFilterPill() {
        if (b == null) {
            return;
        }
        int pos = b.spLocation.getSelectedItemPosition();
        int chPos = b.spChannel.getSelectedItemPosition();
        int countryPos = b.spCountry.getSelectedItemPosition();
        int active = (pos > 0 ? 1 : 0)
                + (chPos > 0 ? 1 : 0)
                + (countryPos > 0 ? 1 : 0)
                + (state.hasSeries() ? 1 : 0)
                + (state.hasPurchaseRange() ? 1 : 0)
                + (state.hasEntryRange() ? 1 : 0)
                + (state.hasPrice() ? 1 : 0);
        boolean on = active > 0;
        Context ctx = b.getRoot().getContext();
        b.tvMoreFilters.setText("更多");
        b.tvMoreFilters.setBackgroundResource(on
                ? R.drawable.bg_filter_pill_selected : R.drawable.bg_filter_pill);
        int textColor = on
                ? ThemeManager.resolveColor(ctx, ThemeManager.ATTR_COLOR_PRIMARY)
                : ThemeManager.resolveColor(ctx, R.attr.on_surface);
        b.tvMoreFilters.setTextColor(textColor);
        b.tvMoreFilters.setCompoundDrawableTintList(ColorStateList.valueOf(on ? textColor
                : ThemeManager.resolveColor(ctx, R.attr.on_surface_variant)));
        b.tvMoreBadge.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            b.tvMoreBadge.setText(String.valueOf(active));
        }
    }

    private int dp(int v) {
        return (int) (v * b.getRoot().getResources().getDisplayMetrics().density);
    }
}
