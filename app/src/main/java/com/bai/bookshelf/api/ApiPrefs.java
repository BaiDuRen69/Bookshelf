package com.bai.bookshelf.api;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * ISBN 查询接口的配置存储（SharedPreferences）。
 * 在「我的 → API 设置」里填写，无需改代码即可替换数据源。
 *
 * 已适配腾讯云市场「聚美智数 ISBN 书号查询」（产品 ID 34136）：
 * - 调用地址：https://ap-guangzhou.cloudmarket-apigw.com/service-ed62t9xo/isbn/query
 * - POST 表单提交 isbn；鉴权为云市场 SecretId + SecretKey 签名（见 SignUtil）
 *
 * 同时保留 APPCODE / 无鉴权 两种方式，方便换其他供应商。
 */
public class ApiPrefs {

    /** 鉴权方式 */
    public static final int AUTH_TENCENT = 0;  // 腾讯云市场签名（SecretId + SecretKey）
    public static final int AUTH_APPCODE = 1;  // APPCODE 头（阿里云市场等）
    public static final int AUTH_NONE = 2;     // 无鉴权（直连供应商接口等）

    private static final String FILE = "api_prefs";
    private static final String FILE_SECURE = "api_prefs_secure";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_AUTH_TYPE = "auth_type";
    private static final String KEY_SECRET_ID = "secret_id";
    private static final String KEY_SECRET_KEY = "secret_key";
    private static final String KEY_APPCODE = "appcode";
    private static final String KEY_INSTANCE_ID = "instance_id"; // 云市场已购资源实例 ID（用量查询用，v4.11）
    // v4.13：用量查询（腾讯云 API 3.0 TC3 签名）需要的是账号级 API 密钥（访问管理 CAM），
    // 与云市场网关密钥（x-date 签名调 ISBN 网关）是两套体系，分开存储
    private static final String KEY_USAGE_SECRET_ID = "usage_secret_id";
    private static final String KEY_USAGE_SECRET_KEY = "usage_secret_key";

    private final SharedPreferences sp;

    public ApiPrefs(Context ctx) {
        // v4.0（评审 #4）：SecretId/SecretKey/Appcode 是会产生费用的云市场凭证，
        // 改用 AndroidX Security 的 EncryptedSharedPreferences（AES256-GCM，密钥由
        // AndroidKeyStore 保护）。极端情况下（如设备 KeyStore 损坏）初始化会抛异常，
        // 此时降级回普通明文 prefs 保证功能可用——可用性优先于加密。
        SharedPreferences secure = null;
        try {
            androidx.security.crypto.MasterKey masterKey =
                    new androidx.security.crypto.MasterKey.Builder(ctx)
                            .setKeyScheme(androidx.security.crypto.MasterKey.KeyScheme.AES256_GCM)
                            .build();
            secure = androidx.security.crypto.EncryptedSharedPreferences.create(
                    ctx, FILE_SECURE, masterKey,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    androidx.security.crypto.EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception ignored) {
        }
        if (secure != null) {
            migrateLegacyToSecure(ctx, secure);
            sp = secure;
        } else {
            sp = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        }
    }

    /** 一次性迁移：旧明文 api_prefs 有配置且加密文件为空时整体搬入，随后清掉明文文件 */
    private static void migrateLegacyToSecure(Context ctx, SharedPreferences secure) {
        SharedPreferences old = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        if (!secure.getAll().isEmpty() || old.getAll().isEmpty()) {
            return; // 已迁移过 / 旧文件本来就没配置
        }
        secure.edit()
                .putString(KEY_BASE_URL, old.getString(KEY_BASE_URL, ""))
                .putInt(KEY_AUTH_TYPE, old.getInt(KEY_AUTH_TYPE, AUTH_TENCENT))
                .putString(KEY_SECRET_ID, old.getString(KEY_SECRET_ID, ""))
                .putString(KEY_SECRET_KEY, old.getString(KEY_SECRET_KEY, ""))
                .putString(KEY_APPCODE, old.getString(KEY_APPCODE, ""))
                .apply();
        old.edit().clear().apply(); // 明文密钥不再保留在磁盘上
    }

    public String getBaseUrl() {
        return normalizeBaseUrl(sp.getString(KEY_BASE_URL, ""));
    }

    /**
     * 规整用户输入的请求地址（v3.4）：
     * 修复「http&https://」这类手滑前缀、去掉首尾引号与空白，无协议头时补 https://。
     * 在读取与保存两侧都调用，已保存的脏数据也会被自动修复。
     */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        // 去掉首尾成对引号（从文档复制时容易带上）
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            s = s.substring(1, s.length() - 1).trim();
        }
        // 修复 http&https:// / https&http:// 等把两个协议写串的前缀
        s = s.replaceFirst("(?i)^(https?)&https?://", "https://");
        // 无协议头时补 https://（云市场接口均为 https）
        if (!s.isEmpty() && !s.matches("(?i)^https?://.*")) {
            s = "https://" + s;
        }
        return s;
    }

    /** 地址能否被网络层解析（用 okhttp 的解析器判断，与 Retrofit 行为一致） */
    public static boolean isValidUrl(String url) {
        if (url == null || url.isEmpty()) return false;
        okhttp3.HttpUrl parsed = okhttp3.HttpUrl.parse(url);
        return parsed != null && !parsed.host().isEmpty();
    }

    public int getAuthType() {
        return sp.getInt(KEY_AUTH_TYPE, AUTH_TENCENT);
    }

    public String getSecretId() {
        return sp.getString(KEY_SECRET_ID, "");
    }

    public String getSecretKey() {
        return sp.getString(KEY_SECRET_KEY, "");
    }

    public String getAppcode() {
        return sp.getString(KEY_APPCODE, "");
    }

    /** 云市场已购资源的实例 ID（GetUsagePlanUsageAmount 用量查询用，v4.11） */
    public String getInstanceId() {
        return sp.getString(KEY_INSTANCE_ID, "");
    }

    /** 腾讯云账号 SecretId（访问管理·API 密钥管理，用量查询 TC3 签名用，v4.13） */
    public String getUsageSecretId() {
        return sp.getString(KEY_USAGE_SECRET_ID, "");
    }

    /** 腾讯云账号 SecretKey（用量查询 TC3 签名用，v4.13） */
    public String getUsageSecretKey() {
        return sp.getString(KEY_USAGE_SECRET_KEY, "");
    }

    /** 保存 ISBN 数据源配置（API 配置页，v4.15 起与用量查询分开保存） */
    public void saveApiConfig(String baseUrl, int authType, String secretId,
                              String secretKey, String appcode) {
        sp.edit()
                .putString(KEY_BASE_URL, normalizeBaseUrl(baseUrl))
                .putInt(KEY_AUTH_TYPE, authType)
                .putString(KEY_SECRET_ID, secretId == null ? "" : secretId.trim())
                .putString(KEY_SECRET_KEY, secretKey == null ? "" : secretKey.trim())
                .putString(KEY_APPCODE, appcode == null ? "" : appcode.trim())
                .apply();
    }

    /** 保存用量查询配置（用量查询页：实例 ID + 腾讯云账号密钥，v4.15） */
    public void saveUsageConfig(String instanceId, String usageSecretId, String usageSecretKey) {
        sp.edit()
                .putString(KEY_INSTANCE_ID, instanceId == null ? "" : instanceId.trim())
                .putString(KEY_USAGE_SECRET_ID, usageSecretId == null ? "" : usageSecretId.trim())
                .putString(KEY_USAGE_SECRET_KEY, usageSecretKey == null ? "" : usageSecretKey.trim())
                .apply();
    }

    /** 是否已配置可用（未配置时扫码后直接进入手动录入） */
    public boolean isConfigured() {
        if (getBaseUrl().isEmpty()) {
            return false;
        }
        switch (getAuthType()) {
            case AUTH_TENCENT:
                return !getSecretId().isEmpty() && !getSecretKey().isEmpty();
            case AUTH_APPCODE:
                return !getAppcode().isEmpty();
            default:
                return true; // 无鉴权只需地址
        }
    }

    /** GET 方式时拼出完整请求地址（支持 {isbn} 占位符，否则自动追加 ?isbn=） */
    public String buildGetUrl(String isbn) {
        String base = getBaseUrl();
        if (base.contains("{isbn}")) {
            return base.replace("{isbn}", isbn);
        }
        return base + (base.contains("?") ? "&" : "?") + "isbn=" + isbn;
    }
}
