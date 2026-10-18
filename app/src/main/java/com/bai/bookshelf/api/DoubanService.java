package com.bai.bookshelf.api;

import android.content.Context;

import com.bai.bookshelf.BuildConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 豆瓣图书评分查询（v3.8，仅测试版启用：BuildConfig.FEATURE_DOUBAN）。
 *
 * <p>豆瓣没有官方开放 API，这里走两条公开网页接口（需带浏览器 UA，否则被风控拦截）：
 * <ol>
 *   <li>图书补全接口 {@code book.douban.com/j/subject_suggest?q=书名} —— 按 ISBN 查询已失效
 *       （实测返回空数组），但按书名可用；扫码流程里聚美智数接口已返回书名，正好衔接。</li>
 *   <li>图书详情页 HTML —— 解析 {@code <strong class="ll rating_num">8.9</strong>} 取评分。
 *       无评分（评分为空串）或解析失败返回 null。</li>
 * </ol>
 *
 * <p>选条目策略：suggest 结果里 type=b 为图书；作者名能匹配上的优先（避免同名书拿错版本），
 * 否则取第一条图书。全部失败静默返回 null，绝不阻塞扫码入库主流程。
 *
 * <p>同步实现在 io 线程调用；失败抛出的异常一律内部消化为 null。
 */
public final class DoubanService {

    private DoubanService() {
    }

    /** 与浏览器一致的 UA——豆瓣对非浏览器 UA 返回 418 或空结果 */
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) "
                    + "Chrome/120.0.0.0 Safari/537.36";

    /** 评分详情页 HTML 约 100KB，超时放宽到 8 秒；suggest 5 秒足够 */
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build();

    /** 该功能是否在当前构建中启用（测试版 true / 正式版 false） */
    public static boolean enabled() {
        return BuildConfig.FEATURE_DOUBAN;
    }

    /**
     * 按书名（+作者）查豆瓣评分，返回如 "8.9"；任何一步失败返回 null。
     * 必须在 io 线程调用（内部为同步网络请求）。
     */
    public static String fetchRatingSync(String title, String author) {
        try {
            String subjectUrl = findSubjectUrl(title, author);
            if (subjectUrl == null) {
                return null;
            }
            return parseRating(getHtml(subjectUrl));
        } catch (Exception e) {
            return null; // 网络失败/风控/结构变化一律静默，不影响入库流程
        }
    }

    /** suggest 接口按书名搜条目，挑出最匹配的图书详情页地址 */
    private static String findSubjectUrl(String title, String author) throws Exception {
        if (title == null || title.trim().isEmpty()) {
            return null;
        }
        String url = "https://book.douban.com/j/subject_suggest?q="
                + URLEncoder.encode(title.trim(), "UTF-8");
        String body = getHtml(url);
        JSONArray arr = new JSONArray(body);
        if (arr.length() == 0) {
            return null;
        }
        // 作者匹配优先：suggest 的 author_name 与书籍作者互为包含即认为同一作者
        String a = normalize(author);
        String fallback = null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject item = arr.getJSONObject(i);
            if (!"b".equals(item.optString("type"))) {
                continue; // 只要图书条目（聚合搜索可能混入影剧等）
            }
            if (fallback == null) {
                fallback = item.optString("url", null);
            }
            if (!a.isEmpty()) {
                String da = normalize(item.optString("author_name"));
                if (!da.isEmpty() && (a.contains(da) || da.contains(a))) {
                    return item.optString("url", null);
                }
            }
        }
        return fallback;
    }

    /** 从详情页 HTML 提取评分（rating_num 元素），带空白容忍与取值范围校验 */
    static String parseRating(String html) {
        if (html == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("rating_num[^>]*>\\s*([\\d.]+)\\s*<").matcher(html);
        if (!m.find()) {
            return null; // 无评分（评分人数为 0 时元素为空）
        }
        try {
            double v = Double.parseDouble(m.group(1));
            if (v < 0 || v > 10) {
                return null;
            }
            return m.group(1);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 作者名归一化：去空格转小写，便于互相包含比较 */
    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("\\s+", "").toLowerCase();
    }

    private static String getHtml(String url) throws Exception {
        Request req = new Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .get()
                .build();
        try (Response resp = CLIENT.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                return null;
            }
            return resp.body().string();
        }
    }
}
