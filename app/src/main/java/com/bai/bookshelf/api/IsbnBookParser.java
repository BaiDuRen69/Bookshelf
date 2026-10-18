package com.bai.bookshelf.api;

import com.bai.bookshelf.data.entity.Book;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Map;

/**
 * ISBN 查询结果解析器。
 * 云市场不同供应商的返回结构差异很大（data/result/rows 嵌套、字段命名不一），
 * 这里做「容错解析」：递归找到最像图书信息的 JSON 对象，再按候选字段名逐一尝试。
 * 如果接入的实际接口字段对不上，只需改下面的候选名数组。
 */
public final class IsbnBookParser {

    // ---- 各字段的候选 key（按优先级排列，命中即止） ----
    private static final String[] TITLE = {"title", "bookName", "book_name", "name", "bookname"};
    private static final String[] SUBTITLE = {"subtitle", "subTitle", "sub_title"};
    private static final String[] AUTHOR = {"author", "authorName", "author_name", "authors"};
    private static final String[] TRANSLATOR = {"translator", "translatorName"};
    private static final String[] PUBLISHER = {"publisher", "press", "publish", "publisherName"};
    private static final String[] PUBDATE = {"publishDate", "publish_date", "pubdate", "pubDate", "publish_time", "publishTime"};
    private static final String[] PAGES = {"pages", "page", "pageCount", "page_count"};
    private static final String[] PRICE = {"price", "listPrice", "list_price", "fixedPrice"};
    private static final String[] COVER = {"cover", "coverUrl", "cover_url", "img", "image", "pic", "picture", "imgUrl"};
    private static final String[] SUMMARY = {"summary", "description", "intro", "abstract", "content", "brief", "gist"};

    private IsbnBookParser() {
    }

    /**
     * 从接口返回的 JSON 中解析出一本 Book；解析不到书名时返回 null（视为查询失败）。
     */
    public static Book parse(String isbn, JsonObject root) {
        JsonObject bookObj = findBookObject(root);
        if (bookObj == null) {
            return null;
        }
        Book b = new Book();
        b.isbn = isbn;
        b.title = getStr(bookObj, TITLE);
        if (b.title.isEmpty()) {
            return null; // 没有书名说明这不是有效的图书数据
        }
        b.subtitle = getStr(bookObj, SUBTITLE);
        b.author = getStr(bookObj, AUTHOR);
        b.translator = getStr(bookObj, TRANSLATOR);
        b.publisher = getStr(bookObj, PUBLISHER);
        b.publishDate = getStr(bookObj, PUBDATE);
        b.coverUrl = getStr(bookObj, COVER);
        // v3.6：不再导入内容简介（用户不需要，编辑页也无此输入项；DB 字段保留但恒为空）
        b.pages = (int) getNum(bookObj, PAGES);
        b.listPrice = getNum(bookObj, PRICE);
        return b;
    }

    /**
     * 递归查找最可能包含图书信息的对象：
     * 优先命中含 title/bookName 等字段的对象；兼容 data/result/rows/list 等常见包裹层。
     */
    private static JsonObject findBookObject(JsonElement el) {
        if (el == null || !el.isJsonObject()) {
            return null;
        }
        JsonObject obj = el.getAsJsonObject();
        // 当前层直接含书名字段 → 就是它
        for (String key : TITLE) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                return obj;
            }
        }
        // 否则往子层找（先对象后数组，取第一个命中的）
        for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
            JsonElement child = e.getValue();
            if (child.isJsonObject()) {
                JsonObject found = findBookObject(child);
                if (found != null) {
                    return found;
                }
            } else if (child.isJsonArray()) {
                JsonArray arr = child.getAsJsonArray();
                if (arr.size() > 0) {
                    JsonObject found = findBookObject(arr.get(0));
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    /** 按候选 key 取字符串，取不到返回空串 */
    private static String getStr(JsonObject obj, String[] keys) {
        for (String key : keys) {
            if (obj.has(key) && obj.get(key).isJsonPrimitive()) {
                String v = obj.get(key).getAsString();
                if (v != null && !v.trim().isEmpty()) {
                    return v.trim();
                }
            }
        }
        return "";
    }

    /** 按候选 key 取数字（兼容字符串型数字，自动去掉 ¥/元 等符号） */
    private static double getNum(JsonObject obj, String[] keys) {
        for (String key : keys) {
            if (!obj.has(key) || !obj.get(key).isJsonPrimitive()) {
                continue;
            }
            try {
                JsonElement el = obj.get(key);
                if (el.getAsJsonPrimitive().isNumber()) {
                    return el.getAsDouble();
                }
                String s = el.getAsString().replaceAll("[^0-9.]", "");
                if (!s.isEmpty()) {
                    return Double.parseDouble(s);
                }
            } catch (Exception ignored) {
                // 单个字段解析失败不影响整体
            }
        }
        return 0;
    }
}
