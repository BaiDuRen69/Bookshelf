package com.bai.bookshelf.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 腾讯云 API 3.0「查询使用计划使用量」（v4.11）。
 * 文档：https://cloud.tencent.com/document/product/306/57443
 * 域名 market.tencentcloudapi.com，Action=GetUsagePlanUsageAmount，Version=2019-10-10。
 *
 * 注意：这是腾讯云 API 3.0 接口，必须用 TC3-HMAC-SHA256 签名，
 * 与云市场网关 ISBN 接口的 x-date/HmacSHA1 签名（SignUtil）是两套算法，互不通用。
 * 凭证同样是云市场已购服务的 SecretId/SecretKey，InstanceId 为已购资源的实例 ID。
 */
public final class MarketUsage {

    private MarketUsage() {
    }

    /** 用量查询结果 */
    public static class Usage {
        public int max;        // 最大调用量
        public int used;       // 已调用量
        public int remaining;  // 剩余调用量
    }

    /**
     * 查询实例的使用计划用量。
     *
     * @throws Exception 网络/签名/业务错误（含腾讯云返回的 Code/Message）
     */
    public static Usage query(String secretId, String secretKey, String instanceId) throws Exception {
        String host = "market.tencentcloudapi.com";
        String service = "market";
        String action = "GetUsagePlanUsageAmount";
        String version = "2019-10-10";
        String body = "{\"InstanceId\":\"" + instanceId + "\"}";

        long timestamp = System.currentTimeMillis() / 1000;
        SimpleDateFormat utcDate = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        utcDate.setTimeZone(TimeZone.getTimeZone("GMT"));
        String date = utcDate.format(new Date(timestamp * 1000));

        // ---------- 1. 拼接规范请求串 CanonicalRequest ----------
        // POST / + 空查询串 + 规范头（content-type/host/x-tc-action，均小写、按序）+ 签名头列表 + payload 哈希
        String canonicalHeaders = "content-type:application/json; charset=utf-8\n"
                + "host:" + host + "\n"
                + "x-tc-action:" + action.toLowerCase(Locale.US) + "\n";
        String signedHeaders = "content-type;host;x-tc-action";
        String hashedPayload = sha256Hex(body);
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders
                + signedHeaders + "\n" + hashedPayload;

        // ---------- 2. 拼接待签名字符串 StringToSign ----------
        String credentialScope = date + "/" + service + "/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n"
                + credentialScope + "\n" + sha256Hex(canonicalRequest);

        // ---------- 3. 计算签名（派生密钥链） ----------
        byte[] kDate = hmacSha256(("TC3" + secretKey).getBytes(StandardCharsets.UTF_8), date);
        byte[] kService = hmacSha256(kDate, service);
        byte[] kSigning = hmacSha256(kService, "tc3_request");
        String signature = hex(hmacSha256(kSigning, stringToSign));

        // ---------- 4. 拼装 Authorization 并发请求 ----------
        String authorization = "TC3-HMAC-SHA256 "
                + "Credential=" + secretId + "/" + credentialScope + ", "
                + "SignedHeaders=" + signedHeaders + ", "
                + "Signature=" + signature;

        HttpURLConnection conn = (HttpURLConnection) new URL("https://" + host).openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setDoOutput(true);
        conn.setRequestProperty("Host", host);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        conn.setRequestProperty("X-TC-Action", action);
        conn.setRequestProperty("X-TC-Version", version);
        conn.setRequestProperty("X-TC-Timestamp", String.valueOf(timestamp));
        conn.setRequestProperty("Authorization", authorization);
        try (OutputStream os = conn.getOutputStream()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int code = conn.getResponseCode();
        InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }

        // ---------- 5. 解析响应（业务错误统一带上 Code/Message 抛出） ----------
        JsonObject root = JsonParser.parseString(sb.toString()).getAsJsonObject();
        JsonObject resp = root.has("Response") ? root.getAsJsonObject("Response") : null;
        if (resp == null) {
            throw new Exception("响应格式异常（HTTP " + code + "）");
        }
        if (resp.has("Error")) {
            JsonObject err = resp.getAsJsonObject("Error");
            throw new Exception(err.has("Message") && !err.get("Message").isJsonNull()
                    ? err.get("Message").getAsString()
                    : (err.has("Code") ? err.get("Code").getAsString() : "未知错误"));
        }
        if (!resp.has("MaxRequestNum")) {
            throw new Exception("响应缺少用量字段（HTTP " + code + "）");
        }
        Usage usage = new Usage();
        usage.max = resp.get("MaxRequestNum").getAsInt();
        usage.used = resp.has("InUseRequestNum") ? resp.get("InUseRequestNum").getAsInt() : 0;
        usage.remaining = resp.has("RemainingRequestNum")
                ? resp.get("RemainingRequestNum").getAsInt() : usage.max - usage.used;
        return usage;
    }

    private static byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256Hex(String data) throws Exception {
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        return hex(md.digest(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
