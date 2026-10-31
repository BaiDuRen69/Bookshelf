package com.bai.bookshelf.ui.detail;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;

/**
 * 详情页分区折叠（v5.7）：书目信息 / 藏书信息 / 我的书房 / 阅读记录。
 * 折叠状态存全局偏好，换一本书打开时沿用上一次的状态。
 */
public final class DetailSectionCollapse {

    public static final String INFO = "info";
    public static final String CORE = "core";
    public static final String MANAGE = "manage";
    public static final String RECORDS = "records";
    public static final String BORROW_RECORDS = "borrow_records";

    private static final String PREFS = "detail_sections";

    private DetailSectionCollapse() {
    }

    public static void bind(Context context, View toggle, View content, String key, String title) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        apply(toggle, content, prefs.getBoolean(key, false), title);
        toggle.setOnClickListener(v -> {
            boolean collapse = content.getVisibility() == View.VISIBLE;
            prefs.edit().putBoolean(key, collapse).apply();
            apply(toggle, content, collapse, title);
        });
    }

    private static void apply(View toggle, View content, boolean collapsed, String title) {
        content.setVisibility(collapsed ? View.GONE : View.VISIBLE);
        // chevron 指向右：收起时朝下，展开时朝上
        toggle.setRotation(collapsed ? 90f : 270f);
        toggle.setContentDescription((collapsed ? "展开" : "收起") + title);
    }
}
