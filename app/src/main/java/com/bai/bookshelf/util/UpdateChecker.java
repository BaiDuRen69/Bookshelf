package com.bai.bookshelf.util;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.widget.Toast;

import com.bai.bookshelf.BuildConfig;
import com.bai.bookshelf.R;
import com.bai.bookshelf.app.BookshelfApp;
import com.bai.bookshelf.databinding.DialogUpdateBinding;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 应用更新检查（v4.7）：以 GitHub Release 为版本源。
 * - 启动时检查：受「启动时检查」开关、「永不提示」、「忽略这个版本」三个条件控制；
 * - 手动检查（我的 → 检查应用更新）：无视记忆选项，有新版就弹窗，无新版 Toast 说明。
 * 更新动作是跳浏览器下载 Release 附件 APK，应用内不做静默下载安装。
 */
public final class UpdateChecker {

    private static final String API_URL =
            "https://api.github.com/repos/BaiDuRen69/Bookshelf/releases/latest";
    private static final String PREFS = "update_check";
    /** 「启动时检查应用更新」开关，默认开 */
    private static final String KEY_START_CHECK = "start_check";
    /** 「永不提示」：以后的任何新版本都不再启动提示 */
    private static final String KEY_NEVER = "never";
    /** 「忽略这个版本」：记录被忽略的版本号（如 "4.7"），该版本不再启动提示 */
    private static final String KEY_IGNORED = "ignored";

    private UpdateChecker() {
    }

    /** GitHub Release 的关键字段 */
    public static class Release {
        public String version;      // 去掉 v 前缀的版本号，如 "4.7"
        public String downloadUrl;  // APK 附件直链；无附件时退化为 Release 页面链接
        public String notes;        // Release 说明（Markdown 原文，弹窗里按纯文本展示）
    }

    // ======================= 偏好读写 =======================

    private static android.content.SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean isStartCheckEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_START_CHECK, true);
    }

    public static void setStartCheckEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_START_CHECK, enabled).apply();
    }

    // ======================= 入口 =======================

    /**
     * 启动时检查（MainActivity 冷启动调用）：开关关闭 / 永不提示 / 已忽略该版本
     * 三种情况直接静默返回；其余情况发现新版本才弹窗，无新版不打扰。
     */
    public static void checkOnLaunch(Activity activity) {
        if (!isStartCheckEnabled(activity) || prefs(activity).getBoolean(KEY_NEVER, false)) {
            return;
        }
        check(activity, true);
    }

    /**
     * 手动检查（我的 → 检查应用更新）：无视「永不提示 / 忽略此版本」，
     * 有新版弹窗；已是最新版或检查失败时用 Toast 说明结果。
     */
    public static void checkManually(Activity activity) {
        check(activity, false);
    }

    /** 共用流程：后台请求 GitHub → 主线程回调处理结果 */
    private static void check(Activity activity, boolean silent) {
        // 提前捕获上下文，避免异步回调时 Activity 已销毁导致崩溃
        final Activity act = activity;
        BookshelfApp.get().bg(() -> {
            Release release = null;
            String error = null;
            try {
                release = fetchLatest();
            } catch (Exception e) {
                error = e.getMessage();
            }
            final Release result = release;
            final String err = error;
            BookshelfApp.get().ui(() -> {
                if (act.isDestroyed() || act.isFinishing()) {
                    return;
                }
                if (result == null) {
                    if (!silent) {
                        Toast.makeText(act, "检查更新失败：" + (err == null ? "网络异常" : err),
                                Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                // cmp > 0 = 最新版比当前版新 → 有更新；cmp <= 0 = 相同或当前更新
                // （v4.12 修复：v4.7 起此处误写为 cmp >= 0，把「有更新」吞成「已是最新版」）
                int cmp = compareVersions(BuildConfig.VERSION_NAME, result.version);
                if (cmp <= 0) {
                    // 已是最新版本：启动检查静默返回，手动检查给出提示
                    if (!silent) {
                        Toast.makeText(act, "已是最新版本 v" + BuildConfig.VERSION_NAME,
                                Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                // 有新版本：启动检查需再过「忽略这个版本」一关；手动检查直接弹窗
                if (silent && result.version.equals(
                        prefs(act).getString(KEY_IGNORED, ""))) {
                    return;
                }
                showUpdateDialog(act, result);
            });
        });
    }

    // ======================= 更新弹窗 =======================

    /** 四选项弹窗：更新 / 下次再说 / 忽略这个版本 / 永不提示 */
    private static void showUpdateDialog(Activity act, Release release) {
        DialogUpdateBinding b = DialogUpdateBinding.inflate(act.getLayoutInflater());
        b.tvNewVersion.setText("最新版本 v" + release.version
                + "（当前 v" + BuildConfig.VERSION_NAME + "）");
        // Release 说明按纯文本展示；超长截断，避免弹窗拖不动
        String notes = release.notes == null ? "" : release.notes.trim();
        if (notes.length() > 800) {
            notes = notes.substring(0, 800) + "\n…（完整说明见 Release 页面）";
        }
        b.tvUpdateNotes.setText(notes.isEmpty() ? "暂无更新说明" : notes);

        AlertDialog dialog = new AlertDialog.Builder(act)
                .setTitle("发现新版本 🎉")
                .setView(b.getRoot())
                .create();
        // 点击弹窗外不关闭：四个按钮语义明确，避免误触关闭后不确定状态
        dialog.setCanceledOnTouchOutside(false);

        b.btnUpdateNow.setOnClickListener(v -> {
            dialog.dismiss();
            openDownload(act, release.downloadUrl);
        });
        b.btnNextTime.setOnClickListener(v -> dialog.dismiss()); // 不记任何状态，下次启动再问
        b.btnIgnoreVersion.setOnClickListener(v -> {
            prefs(act).edit().putString(KEY_IGNORED, release.version).apply();
            dialog.dismiss();
            Toast.makeText(act, "已忽略 v" + release.version
                    + "，该版本的启动提醒不再出现", Toast.LENGTH_SHORT).show();
        });
        b.btnNever.setOnClickListener(v -> {
            prefs(act).edit().putBoolean(KEY_NEVER, true).apply();
            dialog.dismiss();
            Toast.makeText(act, "已关闭启动更新提醒，可随时在「我的 → 检查应用更新」手动检查",
                    Toast.LENGTH_LONG).show();
        });
        dialog.show();
    }

    /** 浏览器打开 APK 下载链接（无附件时为 Release 页）；无浏览器时提示 */
    private static void openDownload(Activity act, String url) {
        if (TextUtils.isEmpty(url)) {
            Toast.makeText(act, "获取下载链接失败，请到 GitHub Release 页面下载", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            act.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(act, "未找到浏览器，请手动访问：\n" + url, Toast.LENGTH_LONG).show();
        }
    }

    // ======================= 网络与版本比较 =======================

    /** 请求 GitHub releases/latest，解析出最新版本号、下载链接与更新说明 */
    private static Release fetchLatest() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(API_URL).openConnection();
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(8000);
        conn.setRequestMethod("GET");
        // GitHub API 强制要求 User-Agent；Accept 走稳定版格式
        conn.setRequestProperty("User-Agent", "XiaoShuFang-App");
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        int code = conn.getResponseCode();
        if (code != 200) {
            throw new Exception("GitHub 返回 " + code + (code == 403 ? "（请求过于频繁，稍后再试）" : ""));
        }
        StringBuilder sb = new StringBuilder();
        try (InputStream in = conn.getInputStream();
             BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line);
            }
        }
        JsonObject root = JsonParser.parseString(sb.toString()).getAsJsonObject();

        Release release = new Release();
        String tag = root.has("tag_name") ? root.get("tag_name").getAsString() : "";
        release.version = tag.startsWith("v") ? tag.substring(1) : tag;
        release.notes = root.has("body") && !root.get("body").isJsonNull()
                ? root.get("body").getAsString() : "";

        // 优先取 .apk 附件直链，其次退化为 Release 页面链接
        release.downloadUrl = root.has("html_url") && !root.get("html_url").isJsonNull()
                ? root.get("html_url").getAsString() : "";
        if (root.has("assets") && root.get("assets").isJsonArray()) {
            JsonArray assets = root.getAsJsonArray("assets");
            for (int i = 0; i < assets.size(); i++) {
                JsonObject a = assets.get(i).getAsJsonObject();
                String name = a.has("name") ? a.get("name").getAsString() : "";
                if (name.toLowerCase().endsWith(".apk")) {
                    release.downloadUrl = a.get("browser_download_url").getAsString();
                    break;
                }
            }
        }
        if (release.version.isEmpty()) {
            throw new Exception("版本信息解析失败");
        }
        return release;
    }

    /**
     * 按数字分段比较版本号（忽略 v 前缀与非数字字符），latest 较新时返回正数。
     * 例：4.6 vs 4.10 → 4.10 更新；4.6 vs 4.6 → 相等；段数不同按 0 补齐。
     */
    static int compareVersions(String current, String latest) {
        int[] a = parseSegments(current);
        int[] b = parseSegments(latest);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return y - x;
            }
        }
        return 0;
    }

    /** "v4.6" / "4.10.1" → [4, 6] / [4, 10, 1]，非数字字符按段分隔 */
    private static int[] parseSegments(String version) {
        String[] parts = (version == null ? "" : version.replaceFirst("^[vV]", "")).split("[^0-9]+");
        java.util.List<Integer> list = new java.util.ArrayList<>();
        for (String p : parts) {
            if (!p.isEmpty()) {
                try {
                    list.add(Integer.parseInt(p));
                } catch (NumberFormatException ignored) {
                }
            }
        }
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = list.get(i);
        }
        return out;
    }
}
