package com.bai.bookshelf.ui.widget;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.ThemeManager;

import java.util.List;

/**
 * 详情页词条式信息行（v5.1，从 BookDetailActivity 抽出）：
 * label 固定 72dp，value 占满右侧；复用时只改文字/显隐，避免每次 reload 重新 inflate。
 */
public final class InfoCell extends LinearLayout {

    public final TextView label;
    public final TextView value;

    public InfoCell(Context ctx) {
        super(ctx);
        float density = ctx.getResources().getDisplayMetrics().density;
        int padV = Math.round(7 * density);
        setOrientation(HORIZONTAL);
        setPadding(0, padV, 0, padV);
        label = new TextView(ctx);
        label.setLayoutParams(new LayoutParams(Math.round(72 * density), LayoutParams.WRAP_CONTENT));
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setLetterSpacing(0.04f);
        label.setTextColor(ThemeManager.resolveColor(ctx, R.attr.on_surface_variant));
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                ctx.getResources().getDimensionPixelSize(R.dimen.text_body_small));
        value = new TextView(ctx);
        value.setLayoutParams(new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
        value.setLineSpacing(0, 1.35f);
        value.setTextColor(ThemeManager.resolveColor(ctx, R.attr.on_surface));
        value.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                ctx.getResources().getDimensionPixelSize(R.dimen.text_body));
        addView(label);
        addView(value);
    }

    /** 追加一条非空信息；label/value 任一为空则跳过 */
    public static void add(List<String[]> items, String label, String value) {
        if (value != null && !value.trim().isEmpty()) {
            items.add(new String[]{label, value.trim()});
        }
    }

    /** 复用容器里的 InfoCell，按需增删并回填文字 */
    public static void fill(LinearLayout container, List<String[]> items) {
        int size = items.size();
        while (container.getChildCount() < size) {
            container.addView(new InfoCell(container.getContext()));
        }
        for (int i = 0; i < container.getChildCount(); i++) {
            InfoCell row = (InfoCell) container.getChildAt(i);
            if (i < size) {
                row.setVisibility(View.VISIBLE);
                row.label.setText(items.get(i)[0]);
                row.value.setText(items.get(i)[1]);
            } else {
                row.setVisibility(View.GONE);
            }
        }
    }
}
