package com.bai.bookshelf.ui.mine;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.bai.bookshelf.api.ApiPrefs;
import com.bai.bookshelf.api.MarketUsage;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.databinding.ActivityUsageQueryBinding;
import com.bai.bookshelf.ui.BaseActivity;

/**
 * 用量查询页（v4.15）：云市场已购 ISBN 服务的调用量查询。
 * 凭证为腾讯云账号级 API 密钥（访问管理），与「API 配置」页的云市场网关密钥是两套体系；
 * TC3-HMAC-SHA256 签名调用 GetUsagePlanUsageAmount，见 MarketUsage。
 */
public class UsageQueryActivity extends BaseActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ActivityUsageQueryBinding b = ActivityUsageQueryBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());
        b.toolbar.setNavigationOnClickListener(v -> finish());

        ApiPrefs prefs = new ApiPrefs(this);

        // 回显已保存的配置
        b.etInstanceId.setText(prefs.getInstanceId());
        b.etUsageSecretId.setText(prefs.getUsageSecretId());
        b.etUsageSecretKey.setText(prefs.getUsageSecretKey());

        // 保存配置（不触发查询）
        b.btnSave.setOnClickListener(v -> {
            prefs.saveUsageConfig(b.etInstanceId.getText().toString(),
                    b.etUsageSecretId.getText().toString(),
                    b.etUsageSecretKey.getText().toString());
            Toast.makeText(this, "已保存用量查询配置", Toast.LENGTH_SHORT).show();
        });

        // 查询使用量：TC3 签名调 GetUsagePlanUsageAmount（先保存再查询，保证下次进来是最新值）
        // 注意：网关密钥（云市场已购服务里那对）在 CAM 中不存在，调账号级接口会报
        // AuthFailure.SecretIdNotFound，所以这里用独立的账号密钥（访问管理 → API 密钥管理）
        b.btnQueryUsage.setOnClickListener(v -> {
            String sid = b.etUsageSecretId.getText().toString().trim();
            String skey = b.etUsageSecretKey.getText().toString().trim();
            String instanceId = b.etInstanceId.getText().toString().trim();
            if (sid.isEmpty() || skey.isEmpty()) {
                b.tilUsageSecretId.setError("请填写腾讯云账号密钥（访问管理 → API 密钥管理），非云市场网关密钥");
                return;
            }
            b.tilUsageSecretId.setError(null);
            if (instanceId.isEmpty()) {
                b.tilInstanceId.setError("请填写实例 ID（云市场已购资源列表中查看）");
                return;
            }
            b.tilInstanceId.setError(null);
            prefs.saveUsageConfig(instanceId, sid, skey);
            b.tvUsageResult.setVisibility(View.VISIBLE);
            b.tvUsageResult.setText("查询中…");
            b.btnQueryUsage.setEnabled(false);
            BookshelfApp app = BookshelfApp.get();
            app.bg(() -> {
                String result;
                try {
                    MarketUsage.Usage usage = MarketUsage.query(sid, skey, instanceId);
                    result = "总调用量：" + usage.max + " 次\n已使用：" + usage.used
                            + " 次\n剩余：" + usage.remaining + " 次";
                } catch (Exception e) {
                    String msg = e.getMessage() == null ? "未知错误" : e.getMessage();
                    if (msg.contains("SecretId")) {
                        // 网关密钥会触发 CAM 的 SecretIdNotFound，给出明确指引
                        msg += "\n（请使用「腾讯云账号密钥」：访问管理 → API 密钥管理，而非云市场网关密钥）";
                    }
                    result = "查询失败：" + msg;
                }
                String finalResult = result;
                app.ui(() -> {
                    b.tvUsageResult.setText(finalResult);
                    b.btnQueryUsage.setEnabled(true);
                });
            });
        });
    }
}
