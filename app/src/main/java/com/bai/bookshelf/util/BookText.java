package com.bai.bookshelf.util;

/**
 * 书名展示的统一工具：全 App 展示书名时套一层《》前，
 * 先用 {@link #bareTitle} 把书名里已有的书名号字符清干净，
 * 避免 API 返回的标题自带《》造成《《书名》》。
 */
public final class BookText {

    private BookText() {
    }

    /**
     * 书名去书名号：把标题里出现的所有《与》字符全部去掉（再清理多余空格与残留分隔符）。
     * 不能只剥首尾——API 返回的标题存在错位书名号（前半个+后半个不成对），全串替换才保险。
     */
    public static String bareTitle(String title) {
        String s = title == null ? "" : title.trim();
        s = s.replace("《", "").replace("》", "");
        // 去掉剥离后可能残留的空括号与多余空格
        s = s.replace("[]", "").replace("（）", "").replace("()", "");
        while (s.contains("  ")) {
            s = s.replace("  ", " ");
        }
        return s.trim();
    }

    /** 展示用书名：清理后统一套一层《》 */
    public static String quotedTitle(String title) {
        return "《" + bareTitle(title) + "》";
    }
}
