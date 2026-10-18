package com.bai.bookshelf.ui.mine;

import android.os.Bundle;

import androidx.annotation.Nullable;

import com.bai.bookshelf.BuildConfig;
import com.bai.bookshelf.R;
import com.bai.bookshelf.databinding.ActivityAboutBinding;
import com.bai.bookshelf.ui.BaseActivity;

/**
 * 关于与免责声明二级页（v2.5）：
 * 版本号动态读取 BuildConfig；作者、完整免责声明与「AI 辅助开发」说明固定在页面中。
 */
public class AboutActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityAboutBinding b = ActivityAboutBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());
        b.tvVersion.setText("版本 v" + BuildConfig.VERSION_NAME);
    }
}
