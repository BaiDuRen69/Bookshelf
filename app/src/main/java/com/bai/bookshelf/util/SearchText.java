package com.bai.bookshelf.util;

import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;

import com.bai.bookshelf.data.entity.Book;

import java.text.Normalizer;
import java.util.Locale;

/**
 * 书架搜索的文本处理（v4.41）：
 * <ul>
 *   <li><b>归一化</b>：忽略大小写、空格与标点（「红 楼」「红-楼」「978-7-115」都能命中）；</li>
 *   <li><b>拼音 / 首字母</b>：Android 10+ 用系统 ICU 的 Han-Latin 转写，
 *       「红楼梦」可被 hongloumeng / hong / hlm 搜到；低版本自动退化为原文匹配；</li>
 *   <li><b>命中高亮</b>：书名/作者里与关键词字面相同的部分套主色 + 淡底。</li>
 * </ul>
 *
 * <p>索引由书架在数据推送后异步构建（每个书一条 haystack），
 * 输入时只做一次 contains，不在按键路径上做转写。
 */
public final class SearchText {

    private static Object transliterator;
    private static boolean transliteratorReady;

    private SearchText() {
    }

    /** 大小写 / 空格 / 标点归一化：只保留字母、数字与汉字 */
    public static String normalize(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if (Character.isLetterOrDigit(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 一本书的可搜索串：归一化原文 \u0001 全拼 \u0001 首字母 */
    public static String haystack(Book b) {
        return haystack(b, "");
    }

    /**
     * v4.50：额外带上摘抄原文/批注/标签（调用方已归一化）。
     * 摘抄量大，不做转写，只参与原文匹配。
     */
    public static String haystack(Book b, String extraNormalized) {
        StringBuilder raw = new StringBuilder();
        append(raw, b.title);
        append(raw, b.subtitle);
        append(raw, b.author);
        append(raw, b.translator);
        append(raw, b.publisher);
        append(raw, b.isbn);
        append(raw, b.tags);
        append(raw, b.category);
        append(raw, b.country);
        append(raw, b.review);
        append(raw, b.seriesName);
        String text = raw.toString();
        String pinyin = latin(text);
        String extra = extraNormalized == null ? "" : extraNormalized;
        return normalize(text) + extra + '\u0001' + normalize(pinyin) + '\u0001' + initials(pinyin);
    }

    /** 索引还没建好时的兜底：现场拼串匹配（比索引慢，但只发生在启动瞬间） */
    public static boolean matchesFallback(Book b, String queryNorm) {
        if (queryNorm.isEmpty()) {
            return true;
        }
        return normalize(b.title + b.subtitle + b.author + b.translator + b.publisher
                + b.isbn + b.tags + b.category + b.country
                + (b.seriesName == null ? "" : b.seriesName)
                + (b.review == null ? "" : b.review)).contains(queryNorm);
    }

    /** 命中高亮：字面相同的部分套文字色 + 淡底（拼音命中无法在中文里标注，忽略） */
    public static CharSequence highlight(CharSequence text, String keyword,
                                         int textColor, int backgroundColor) {
        if (text == null || keyword == null) {
            return text;
        }
        String kw = keyword.trim();
        if (kw.isEmpty()) {
            return text;
        }
        String lowerText = text.toString().toLowerCase(Locale.ROOT);
        String lowerKw = kw.toLowerCase(Locale.ROOT);
        SpannableString span = null;
        int from = 0;
        while (true) {
            int index = lowerText.indexOf(lowerKw, from);
            if (index < 0) {
                break;
            }
            if (span == null) {
                span = new SpannableString(text);
            }
            int end = index + lowerKw.length();
            span.setSpan(new ForegroundColorSpan(textColor), index, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            span.setSpan(new BackgroundColorSpan(backgroundColor), index, end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = end;
        }
        return span == null ? text : span;
    }

    // ==================== 内部 ====================

    private static void append(StringBuilder sb, String value) {
        if (value != null && !value.isEmpty()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(value);
        }
    }

    /** 汉字转拼音（小写、保留空格分隔的音节）；不支持的系统版本返回空串 */
    private static String latin(String s) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || s.isEmpty()) {
            return "";
        }
        try {
            if (!transliteratorReady) {
                transliteratorReady = true;
                transliterator = android.icu.text.Transliterator
                        .getInstance("Han-Latin; Latin-ASCII");
            }
            if (transliterator == null) {
                return "";
            }
            String out = ((android.icu.text.Transliterator) transliterator).transliterate(s);
            // 兜底去掉可能残留的声调符号，再统一小写
            out = Normalizer.normalize(out, Normalizer.Form.NFD)
                    .replaceAll("\\p{M}", "");
            return out.toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 每个音节取首字母：「hong lou meng」→「hlm」 */
    private static String initials(String spaced) {
        if (spaced.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String token : spaced.split("\\s+")) {
            for (int i = 0; i < token.length(); i++) {
                char c = token.charAt(i);
                if (Character.isLetterOrDigit(c)) {
                    sb.append(c);
                    break;
                }
            }
        }
        return sb.toString();
    }
}
