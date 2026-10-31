package com.bai.bookshelf.ui.shelf;

import com.bai.bookshelf.util.Typo;
import android.content.Context;
import android.content.res.ColorStateList;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.ThemeManager;

import java.util.Arrays;
import java.util.List;

/**
 * 紧凑版 spinner 适配器，下拉弹层中当前选中项高亮（加粗 + 主题色 + 浅底色）。
 * 闭合态用小字号样式（spinner_item），下拉项在系统默认样式上定制。
 */
final class HighlightAdapter<T> extends ArrayAdapter<T> {

    private int highlight = 0; // 当前选中项下标（下拉弹层里高亮它）

    HighlightAdapter(Context context, int arrayRes) {
        super(context, R.layout.spinner_item,
                Arrays.asList((T[]) context.getResources().getStringArray(arrayRes)));
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }

    HighlightAdapter(Context context, List<T> items) {
        super(context, R.layout.spinner_item, items);
        setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
    }

    void setHighlight(int position) {
        highlight = position;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        TextView tv = (TextView) super.getView(position, convertView, parent);
        Context context = getContext();
        boolean active = position > 0;
        int primary = ThemeManager.resolveColor(context, ThemeManager.ATTR_COLOR_PRIMARY);
        tv.setBackgroundResource(active
                ? R.drawable.bg_filter_pill_selected : R.drawable.bg_filter_pill);
        tv.setTextColor(active ? primary
                : ThemeManager.resolveColor(context, R.attr.on_surface));
        // 滤镜胶囊的细线下箭头（选中态跟主色，未选中跟次级文字色）
        tv.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chevron_down_line, 0);
        tv.setCompoundDrawableTintList(ColorStateList.valueOf(active ? primary
                : ThemeManager.resolveColor(context, R.attr.on_surface_variant)));
        tv.setCompoundDrawablePadding((int) context.getResources().getDisplayMetrics().density);
        return tv;
    }

    @NonNull
    @Override
    public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        TextView tv = (TextView) super.getDropDownView(position, convertView, parent);
        int pad = (int) (12 * getContext().getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        Typo.text(tv, R.dimen.text_body_medium);
        if (position == highlight) {
            // 选中项：加粗 + 主题色文字 + 浅主题底色
            tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            tv.setTextColor(ThemeManager.resolveColor(
                    getContext(), ThemeManager.ATTR_COLOR_PRIMARY));
            tv.setBackgroundColor(ThemeManager.primaryAlpha(getContext(), 0x14));
        } else {
            tv.setTypeface(android.graphics.Typeface.DEFAULT);
            tv.setTextColor(ThemeManager.resolveColor(getContext(), R.attr.on_surface));
            tv.setBackgroundColor(ThemeManager.resolveColor(getContext(), R.attr.surface));
        }
        return tv;
    }
}
