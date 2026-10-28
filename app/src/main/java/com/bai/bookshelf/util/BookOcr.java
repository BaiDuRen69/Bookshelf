package com.bai.bookshelf.util;

import android.content.Context;
import android.net.Uri;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 封面/扉页文字识别（v4.51）：ML Kit 中文模型（同时识别拉丁字母），
 * 从识别出的行里猜书名 / 作者 / 出版社，结果只做预填，最终由用户确认。
 *
 * <p>模型是 bundled 版本，离线可用；native 库已确认 16 KB 页对齐
 * （p_align=0x4000，zipalign -P 16 通过）。
 */
public final class BookOcr {

    public static final class Result {
        public String title = "";
        public String author = "";
        public String publisher = "";
        public String raw = "";

        public boolean isEmpty() {
            return title.isEmpty() && author.isEmpty() && publisher.isEmpty();
        }
    }

    public interface Callback {
        void onDone(Result result);

        void onFailed(String message);
    }

    private static final Pattern P_AUTHOR_PREFIX = Pattern.compile(
            "^(作者|著者|编著|主编|译者)[:：]?\\s*(.+)$");
    private static final Pattern P_AUTHOR_SUFFIX = Pattern.compile(
            "^(.{1,20}?)\\s*(著|编著|主编|编|译)$");
    /** OCR 常把「著」认成「着/菁/箸」：只在后缀前有空格时才认，避免把「活着」当作者 */
    private static final Pattern P_AUTHOR_SUFFIX_FUZZY = Pattern.compile(
            "^(.{1,20})\\s+(着|菁|箸)$");
    private static final Pattern P_EDITION = Pattern.compile(
            "^第?[0-9一二三四五六七八九十]+(版|次印刷|刷).*$");
    private static final Pattern P_PRICE = Pattern.compile(
            "^(定价|售价|价格|促销价|特价).*$|.*[¥￥].*|.*\\d+(\\.\\d+)?元$");

    private BookOcr() {
    }

    /** 识别图片并解析；回调在主线程 */
    public static void recognize(Context ctx, Uri uri, Callback callback) {
        InputImage image;
        try {
            image = InputImage.fromFilePath(ctx, uri);
        } catch (Exception e) {
            callback.onFailed("读不出这张图片");
            return;
        }
        TextRecognizer recognizer = TextRecognition.getClient(
                new ChineseTextRecognizerOptions.Builder().build());
        recognizer.process(image)
                .addOnSuccessListener(text -> callback.onDone(parse(text)))
                .addOnFailureListener(e -> callback.onFailed(
                        e.getMessage() == null ? "文字识别失败" : e.getMessage()))
                .addOnCompleteListener(t -> recognizer.close());
    }

    /** 一行识别结果 + 行高（近似字号，用来挑封面主标题） */
    static final class LineInfo {
        final String text;
        final int height;

        LineInfo(String text, int height) {
            this.text = text;
            this.height = height;
        }
    }

    static Result parse(Text text) {
        List<LineInfo> lines = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        for (Text.TextBlock block : text.getTextBlocks()) {
            for (Text.Line line : block.getLines()) {
                String s = line.getText() == null ? "" : line.getText().trim();
                if (!s.isEmpty()) {
                    android.graphics.Rect box = line.getBoundingBox();
                    lines.add(new LineInfo(s, box == null ? 0 : box.height()));
                    raw.append(s).append('\n');
                }
            }
        }
        Result r = parseLines(lines);
        r.raw = raw.toString().trim();
        android.util.Log.d("BookOcr", "raw=" + r.raw.replace('\n', '/')
                + " => title=" + r.title + ", author=" + r.author + ", publisher=" + r.publisher);
        return r;
    }

    /** 行级启发式：先认作者/出版社，书名取「字号最大」的候选（封面主标题） */
    static Result parseLines(List<LineInfo> lines) {
        Result r = new Result();
        LineInfo bestTitle = null;
        for (LineInfo info : lines) {
            String s = info.text == null ? "" : info.text.trim();
            if (s.isEmpty()) {
                continue;
            }
            String compact = s.replaceAll("\\s+", "");
            if (compact.length() < 2 || compact.length() > 60) {
                continue;
            }
            if (compact.toUpperCase(java.util.Locale.ROOT).contains("ISBN")
                    || compact.matches("^[\\d\\-.\\s]+$")) {
                continue;
            }
            if (P_PRICE.matcher(compact).matches() || P_EDITION.matcher(compact).matches()) {
                continue;
            }
            if (compact.contains("http") || compact.contains("www.")) {
                continue;
            }

            String author = matchAuthor(s);
            if (author != null && r.author.isEmpty()) {
                r.author = author;
                continue;
            }
            if (r.publisher.isEmpty() && isPublisher(compact)) {
                r.publisher = s.replace("出版杜", "出版社");
                continue;
            }
            if (isTitleCandidate(compact)) {
                // 封面主标题通常字号最大；高度接近时保留先出现的
                if (bestTitle == null || info.height > bestTitle.height + 4) {
                    bestTitle = info;
                }
            }
        }
        r.title = bestTitle == null ? "" : bestTitle.text;
        if (r.title.equals(r.author)) {
            r.title = "";
        }
        return r;
    }

    private static String matchAuthor(String line) {
        Matcher m = P_AUTHOR_PREFIX.matcher(line);
        if (m.find()) {
            return cleanName(m.group(2));
        }
        m = P_AUTHOR_SUFFIX.matcher(line);
        if (m.find()) {
            return cleanName(m.group(1));
        }
        m = P_AUTHOR_SUFFIX_FUZZY.matcher(line);
        if (m.find()) {
            return cleanName(m.group(1));
        }
        return null;
    }

    private static String cleanName(String s) {
        return s.replaceAll("[\\[\\]（）()]", " ").trim();
    }

    private static boolean isPublisher(String compact) {
        // 「社」常被 OCR 认成「杜」，按「出版 + 机构后缀」兜底
        return compact.contains("出版社") || compact.contains("出版公司")
                || compact.contains("出版集团") || compact.contains("书局")
                || compact.matches(".*出版(社|杜|局|公司|集团).*");
    }

    private static boolean isTitleCandidate(String compact) {
        if (compact.length() < 2 || compact.length() > 40) {
            return false;
        }
        if (isPublisher(compact)) {
            return false;
        }
        if (compact.startsWith("作者") || compact.startsWith("著者")
                || compact.startsWith("译者") || compact.startsWith("主编")) {
            return false;
        }
        return !P_AUTHOR_SUFFIX.matcher(compact).matches();
    }
}
