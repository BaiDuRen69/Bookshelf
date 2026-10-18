package com.bai.bookshelf.api;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 腾讯云市场 API 网关签名工具。
 * 算法（云市场官方文档）：
 *   source    = "x-date: " + 当前 GMT 时间
 *   signature = Base64( HmacSHA1(source, SecretKey) )
 *   Authorization 头 = {"id":"SecretId","x-date":"...","signature":"..."}
 */
public final class SignUtil {

    private SignUtil() {
    }

    /** 当前时间的 GMT 格式（签名与 x-date 字段必须使用同一串） */
    public static String gmtNow() {
        SimpleDateFormat fmt = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US);
        fmt.setTimeZone(TimeZone.getTimeZone("GMT"));
        return fmt.format(new Date());
    }

    /** HmacSHA1 后 Base64（不换行） */
    public static String hmacSha1Base64(String data, String secretKey) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(raw, Base64.NO_WRAP);
        } catch (Exception e) {
            // 算法为标准内置，正常不会到这里
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    /** 生成云市场要求的 Authorization 头内容（JSON 字符串） */
    public static String tencentMarketAuth(String secretId, String secretKey) {
        String xDate = gmtNow();
        String source = "x-date: " + xDate;
        String signature = hmacSha1Base64(source, secretKey);
        return "{\"id\":\"" + secretId + "\",\"x-date\":\"" + xDate
                + "\",\"signature\":\"" + signature + "\"}";
    }
}
