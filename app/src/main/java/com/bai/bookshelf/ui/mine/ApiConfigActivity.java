package com.bai.bookshelf.ui.mine;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.bai.bookshelf.api.ApiPrefs;
import com.bai.bookshelf.databinding.ActivityApiConfigBinding;
import com.bai.bookshelf.ui.BaseActivity;

/**
 * API 配置页（v4.15）：ISBN 数据源地址与鉴权凭证（腾讯云市场签名 / APPCODE / 无鉴权）。
 * 从「我的 → API 设置 → API 配置」进入；用量查询相关字段已拆到 UsageQueryActivity。
 */
public class ApiConfigActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityApiConfigBinding b = ActivityApiConfigBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());

        ApiPrefs prefs = new ApiPrefs(this);

        // 密钥存储状态（4.2）：加密失败会静默降级成明文，页顶这一行是唯一能看见的地方。
        // 必须在 new ApiPrefs 之后读——isEncrypted() 记的就是这次构造的结果。
        boolean encrypted = ApiPrefs.isEncrypted();
        b.tvSecureState.setText(encrypted ? com.bai.bookshelf.R.string.api_secure_on
                : com.bai.bookshelf.R.string.api_secure_off);
        b.tvSecureNote.setVisibility(encrypted ? View.GONE : View.VISIBLE);
        b.tvSecureNote.setText(com.bai.bookshelf.R.string.api_secure_off_note);
        if (!encrypted) {
            b.tvSecureState.setTextColor(com.bai.bookshelf.util.ThemeManager.resolveColor(
                    this, com.google.android.material.R.attr.colorError));
        }

        // 回显已保存的配置
        b.etBaseUrl.setText(prefs.getBaseUrl());
        b.etSecretId.setText(prefs.getSecretId());
        b.etSecretKey.setText(prefs.getSecretKey());
        b.etAppcode.setText(prefs.getAppcode());

        // 鉴权方式下拉（位置与 ApiPrefs.AUTH_* 常量一致）
        b.spAuthType.setAdapter(android.widget.ArrayAdapter.createFromResource(this,
                com.bai.bookshelf.R.array.auth_type_entries,
                android.R.layout.simple_spinner_dropdown_item));
        b.spAuthType.setSelection(prefs.getAuthType());

        // 按鉴权方式显示对应的凭证栏位
        b.spAuthType.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                boolean tencent = position == ApiPrefs.AUTH_TENCENT;
                boolean appcode = position == ApiPrefs.AUTH_APPCODE;
                b.tilSecretId.setVisibility(tencent ? View.VISIBLE : View.GONE);
                b.tilSecretKey.setVisibility(tencent ? View.VISIBLE : View.GONE);
                b.tilAppcode.setVisibility(appcode ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        // 保存：先规整并校验地址，非法时标错并停留本页
        b.btnSave.setOnClickListener(v -> {
            String url = ApiPrefs.normalizeBaseUrl(b.etBaseUrl.getText().toString());
            b.etBaseUrl.setText(url); // 规整后的值回填输入框，用户能看到修正结果
            if (!ApiPrefs.isValidUrl(url)) {
                b.tilBaseUrl.setError("地址无效，需为 https:// 开头的完整地址");
                return;
            }
            b.tilBaseUrl.setError(null);
            prefs.saveApiConfig(url,
                    b.spAuthType.getSelectedItemPosition(),
                    b.etSecretId.getText().toString(),
                    b.etSecretKey.getText().toString(),
                    b.etAppcode.getText().toString());
            Toast.makeText(this, "已保存 API 配置", Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
