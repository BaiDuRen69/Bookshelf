package com.bai.bookshelf.ui;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bai.bookshelf.R;
import com.bai.bookshelf.util.InsetsHelper;
import com.bai.bookshelf.util.ThemeManager;

/**
 * 所有 Activity 的基类（v2.5 主题换肤）：
 * 在 super.onCreate 之前按用户选择 setTheme，保证 setContentView 及其后
 * inflate 的所有视图（含 Fragment）都跑在选定主题上。
 *
 * <p>v4.18：统一处理系统栏 inset。本应用 targetSdk = 36，在 Android 15+
 * 强制 edge-to-edge，内容会画到状态栏/导航栏之下；而顶部的 MaterialToolbar
 * 只有在自身被标记为 fitsSystemWindows 时才会自动避让。为避免逐页遗漏，
 * 这里在 onContentChanged 阶段统一给带 {@code @+id/toolbar} 的顶部栏
 * 加状态栏内边距。
 */
public class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
    }

    @Override
    public void onContentChanged() {
        super.onContentChanged();
        applyToolbarInset();
    }

    /**
     * 给页面顶部栏加状态栏内边距，使标题不被状态栏遮挡。
     *
     * <p>注意：只在 <b>Activity 自身的布局</b>里找工具栏。MainActivity 的三个 Tab
     * 是 Fragment，它们的工具栏由各自的 Fragment 在 onCreateView 里处理
     * （Fragment 视图创建时机晚于 Activity 的 onContentChanged，这里找不到）。
     */
    private void applyToolbarInset() {
        View root = findViewById(android.R.id.content);
        if (root == null) {
            return;
        }
        // 直接子层查找（不递归进 Fragment 容器），避免与 Fragment 内的处理重复
        ViewGroup parent = root instanceof ViewGroup ? (ViewGroup) root : null;
        if (parent == null) {
            return;
        }
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child.getId() == R.id.toolbar) {
                InsetsHelper.applyTopInset(child);
                return;
            }
        }
    }
}
