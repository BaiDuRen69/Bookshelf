package com.bai.bookshelf.ui.mine;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;

import com.bai.bookshelf.databinding.ActivityApiSettingsBinding;
import com.bai.bookshelf.ui.BaseActivity;

/**
 * API 设置二级页（v4.15 从弹窗改为独立页面）：
 * 只做入口分发——「API 配置」放 ISBN 数据源地址与鉴权凭证，
 * 「用量查询」放实例 ID、账号密钥与调用量查询，两类配置彻底分开。
 */
public class ApiSettingsActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityApiSettingsBinding b = ActivityApiSettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());
        b.rowApiConfig.setOnClickListener(v -> startActivity(
                new Intent(this, ApiConfigActivity.class)));
        b.rowUsageQuery.setOnClickListener(v -> startActivity(
                new Intent(this, UsageQueryActivity.class)));
    }
}
