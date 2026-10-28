package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 系列 / 套装分组（v4.53）。
 *
 * <p>书籍只存「系列名 + 卷号 + 阅读顺序」三个快照字段，系列本身不是独立表：
 * 系列列表、缺卷判断都由现有藏书即时聚合，改名/删书不需要额外同步。
 */
public final class BookSeries {

    /** 卷号里的首个数字，兼容「2」「第 2 卷」「Vol.2」「2.5」 */
    private static final Pattern VOLUME_NUMBER = Pattern.compile("(\\d+(?:\\.\\d+)?)");

    private BookSeries() {
    }

    public static String name(Book book) {
        if (book == null || book.seriesName == null) {
            return "";
        }
        return book.seriesName.trim();
    }

    public static String volume(Book book) {
        if (book == null || book.seriesVolume == null) {
            return "";
        }
        return book.seriesVolume.trim();
    }

    /** 卷号的展示文字：「2」→「第 2 卷」；「外传」原样返回；空串返回空 */
    public static String volumeLabel(Book book) {
        String volume = volume(book);
        if (volume.isEmpty()) {
            return "";
        }
        if (volume.startsWith("第") && volume.endsWith("卷")) {
            return volume;
        }
        double number = numericVolume(book);
        if (number > 0 && number == Math.floor(number)) {
            return "第 " + volume + " 卷";
        }
        return volume;
    }

    /** 卷号里的数字（解析不出返回 0） */
    public static double numericVolume(Book book) {
        Matcher m = VOLUME_NUMBER.matcher(volume(book));
        if (!m.find()) {
            return 0;
        }
        try {
            return Double.parseDouble(m.group(1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 排序键：阅读顺序优先，没填则用卷号数字；都没有则排最后 */
    private static double sortKey(Book book) {
        if (book.seriesOrder > 0) {
            return book.seriesOrder;
        }
        double volume = numericVolume(book);
        return volume > 0 ? volume : Double.MAX_VALUE;
    }

    /** 系列内排序：阅读顺序 → 卷号 → 书名 */
    public static int compare(Book a, Book b) {
        int byOrder = Double.compare(sortKey(a), sortKey(b));
        if (byOrder != 0) {
            return byOrder;
        }
        return BookText.bareTitle(a.title).compareToIgnoreCase(BookText.bareTitle(b.title));
    }

    /** 返回按系列内顺序排好的副本 */
    public static List<Book> sorted(Collection<Book> books) {
        List<Book> list = new ArrayList<>(books);
        list.sort(BookSeries::compare);
        return list;
    }

    /** 按系列名分组；每组已按系列内顺序排好，组间按藏书量降序、再按名称升序 */
    public static Map<String, List<Book>> group(Collection<Book> books) {
        Map<String, List<Book>> result = new LinkedHashMap<>();
        if (books == null) {
            return result;
        }
        Map<String, List<Book>> raw = new LinkedHashMap<>();
        for (Book book : books) {
            String name = name(book);
            if (name.isEmpty()) {
                continue;
            }
            String key = name.toLowerCase(Locale.ROOT);
            raw.computeIfAbsent(key, k -> new ArrayList<>()).add(book);
        }
        List<String> keys = new ArrayList<>(raw.keySet());
        // 组名取首次出现的原始写法，排序按藏书量
        Map<String, String> displayNames = new LinkedHashMap<>();
        for (Book book : books) {
            String key = name(book).toLowerCase(Locale.ROOT);
            if (!key.isEmpty() && !displayNames.containsKey(key)) {
                displayNames.put(key, name(book));
            }
        }
        keys.sort((a, b) -> {
            int byCount = Integer.compare(raw.get(b).size(), raw.get(a).size());
            return byCount != 0 ? byCount : displayNames.get(a).compareToIgnoreCase(displayNames.get(b));
        });
        for (String key : keys) {
            result.put(displayNames.get(key), sorted(raw.get(key)));
        }
        return result;
    }

    /** 全部系列名（按名称排序，供选择器 / 筛选使用） */
    public static List<String> names(Collection<Book> books) {
        Set<String> seen = new LinkedHashSet<>();
        List<String> names = new ArrayList<>();
        if (books == null) {
            return names;
        }
        for (Book book : books) {
            String name = name(book);
            if (!name.isEmpty() && seen.add(name.toLowerCase(Locale.ROOT))) {
                names.add(name);
            }
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }

    /**
     * 缺卷提示：只有「所有卷号都是整数」时才判断，避免「上/中/下」「外传」误报。
     * 返回缺失的卷号文本（如 ["2", "5"]）；没有缺口或无法判断返回空列表。
     */
    public static List<String> missingVolumes(Collection<Book> books) {
        List<String> missing = new ArrayList<>();
        if (books == null || books.size() < 2) {
            return missing;
        }
        Set<Integer> present = new LinkedHashSet<>();
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (Book book : books) {
            String volume = volume(book);
            if (volume.isEmpty() || !volume.matches("\\d+")) {
                return missing;
            }
            int number;
            try {
                number = Integer.parseInt(volume);
            } catch (NumberFormatException e) {
                return missing;
            }
            present.add(number);
            min = Math.min(min, number);
            max = Math.max(max, number);
        }
        if (max - min > 200) {
            return missing; // 卷号跨度异常（如 1 和 1000），不猜
        }
        for (int i = min; i <= max; i++) {
            if (!present.contains(i)) {
                missing.add(String.valueOf(i));
            }
        }
        return missing;
    }

    /** 下一卷卷号建议：现有最大整数卷号 + 1；没有整数卷号则返回空串 */
    public static String nextVolume(Collection<Book> books) {
        if (books == null) {
            return "";
        }
        int max = 0;
        for (Book book : books) {
            String volume = volume(book);
            if (!volume.matches("\\d+")) {
                continue;
            }
            try {
                max = Math.max(max, Integer.parseInt(volume));
            } catch (NumberFormatException ignored) {
            }
        }
        return max > 0 ? String.valueOf(max + 1) : "";
    }
}
