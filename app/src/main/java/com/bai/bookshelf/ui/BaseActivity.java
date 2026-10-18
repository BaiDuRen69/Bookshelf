package com.bai.bookshelf.ui;

import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bai.bookshelf.util.ThemeManager;

/**
 * 所有 Activity 的基类（v2.5 主题换肤）：
 * 在 super.onCreate 之前按用户选择 setTheme，保证 setContentView 及其后
 * inflate 的所有视图（含 Fragment）都跑在选定主题上。
 */
public class BaseActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
    }
}
