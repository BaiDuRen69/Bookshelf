package com.bai.bookshelf.api;

import android.content.Context;

import androidx.annotation.NonNull;

import com.bai.bookshelf.data.entity.Book;
import com.google.gson.JsonObject;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Call;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.gson.GsonConverterFactory;

/**
 * ISBN 查询服务：按 ApiPrefs 配置选择鉴权方式与请求方法，发请求并解析结果。
 * Retrofit 的 enqueue 回调在主线程执行，调用方无需再切线程。
 */
public class IsbnQueryService {

    /** 查询结果回调：book 为 null 表示失败，errorMsg 为失败原因 */
    public interface Callback {
        void onResult(Book book, String errorMsg);
    }

    private final IsbnApi api;
    private final ApiPrefs prefs;

    public IsbnQueryService(Context ctx) {
        this.prefs = new ApiPrefs(ctx);
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS);
        // v4.0（评审 #14）：请求日志仅测试版挂载，正式版不输出网络日志
        if (com.bai.bookshelf.BuildConfig.DEBUG) {
            HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
            logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
            builder.addInterceptor(logging);
        }
        OkHttpClient client = builder.build();
        // baseUrl 仅作占位，实际请求走 @Url 全路径
        Retrofit retrofit = new Retrofit.Builder()
                .baseUrl("https://placeholder.local/")
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build();
        api = retrofit.create(IsbnApi.class);
    }

    public boolean isConfigured() {
        return prefs.isConfigured();
    }

    /** 按 ISBN 查询图书信息（异步），按配置选择鉴权方式 */
    public void query(String isbn, Callback callback) {
        // 兜底检查：地址无法解析时直接报明确错误，避免请求落到 Retrofit 占位域名上
        // （@Url 解析失败会回退到 baseUrl，报「Unable to resolve host placeholder.local」这种让人摸不着头脑的错）
        String base = prefs.getBaseUrl();
        if (!ApiPrefs.isValidUrl(base)) {
            callback.onResult(null, "请求地址无效（需为 http:// 或 https:// 开头的完整地址），请在「我的 → API 设置」中修正");
            return;
        }

        Call<JsonObject> call;
        Map<String, String> headers = new HashMap<>();

        switch (prefs.getAuthType()) {
            case ApiPrefs.AUTH_TENCENT: {
                // 腾讯云市场：POST 表单 + SecretId/SecretKey 签名头
                headers.put("Authorization",
                        SignUtil.tencentMarketAuth(prefs.getSecretId(), prefs.getSecretKey()));
                headers.put("request-id", UUID.randomUUID().toString());
                headers.put("X-Requested-With", "XMLHttpRequest");
                call = api.postForm(base, isbn, headers);
                break;
            }
            case ApiPrefs.AUTH_APPCODE: {
                // APPCODE 头方式（阿里云市场等）：GET
                headers.put("Authorization", "APPCODE " + prefs.getAppcode());
                call = api.get(prefs.buildGetUrl(isbn), headers);
                break;
            }
            default: {
                // 无鉴权：GET
                call = api.get(prefs.buildGetUrl(isbn), headers);
                break;
            }
        }

        call.enqueue(new retrofit2.Callback<JsonObject>() {
            @Override
            public void onResponse(@NonNull Call<JsonObject> call, @NonNull Response<JsonObject> response) {
                if (!response.isSuccessful() || response.body() == null) {
                    callback.onResult(null, "接口返回异常（HTTP " + response.code()
                            + "），请检查 API 设置与密钥");
                    return;
                }
                Book book = IsbnBookParser.parse(isbn, response.body());
                if (book == null) {
                    callback.onResult(null, "未查到该 ISBN 的图书信息，请手动录入");
                } else {
                    callback.onResult(book, null);
                }
            }

            @Override
            public void onFailure(@NonNull Call<JsonObject> call, @NonNull Throwable t) {
                callback.onResult(null, "网络请求失败：" + t.getMessage());
            }
        });
    }
}
