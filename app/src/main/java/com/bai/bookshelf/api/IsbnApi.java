package com.bai.bookshelf.api;

import com.google.gson.JsonObject;

import java.util.Map;

import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.HeaderMap;
import retrofit2.http.POST;
import retrofit2.http.Url;

/**
 * ISBN 查询接口定义。
 * 使用 @Url 动态全路径 + @HeaderMap 动态鉴权头，
 * 请求方式与凭证完全由「API 设置」里的配置决定，便于更换数据源。
 */
public interface IsbnApi {

    /** POST 表单（腾讯云市场聚美智数 34136 即此方式） */
    @FormUrlEncoded
    @POST
    Call<JsonObject> postForm(@Url String url,
                              @Field("isbn") String isbn,
                              @HeaderMap Map<String, String> headers);

    /** GET（APPCODE / 无鉴权方式） */
    @GET
    Call<JsonObject> get(@Url String url, @HeaderMap Map<String, String> headers);
}
