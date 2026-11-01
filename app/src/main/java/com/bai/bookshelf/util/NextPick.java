package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 「下一本读什么」（v5.8.19）：从还没读的书里加权挑一本，并说清为什么是它。
 *
 * <p>专治「书很多、不知道读哪本」。四个信号都是库里现成的，不用用户额外配置：
 * 评分期待（豆瓣评分 / 自己标的星）、书债（买了多久没翻）、标签亲和
 * （你读完或打高分的书带哪些标签）、厚薄（短的先开个头）。
 *
 * <p>不直接取分数最高的一本——那样每次都是同一本。改成在<b>前若干名里加权随机抽</b>，
 * 传入同一个 seed 结果固定，方便测试也方便「换一本」时换种子。
 */
public final class NextPick {

    /** 参与抽签的候选数（打分后的前 N 名） */
    public static final int POOL = 8;

    /** 一本候选：书 + 总分 + 人话理由 */
    public static final class Pick {
        public final Book book;
        public double score;
        public final List<String> reasons = new ArrayList<>();

        Pick(Book book) {
            this.book = book;
        }

        public String reasonLine() {
            StringBuilder sb = new StringBuilder();
            for (String reason : reasons) {
                if (sb.length() > 0) {
                    sb.append(" · ");
                }
                sb.append(reason);
            }
            return sb.toString();
        }
    }

    private NextPick() {
    }

    /** 打分并降序排列；返回的列表可能为空（没有可读的书） */
    public static List<Pick> rank(List<Book> raw, int todayKey) {
        List<Book> books = BookSanitizer.normalizeAll(raw);
        Map<String, Integer> affinity = tagAffinity(books);
        List<Pick> picks = new ArrayList<>();
        for (Book book : books) {
            if (!isCandidate(book)) {
                continue;
            }
            Pick pick = new Pick(book);
            double douban = parseDouban(book.doubanRating);
            double own = book.ratingAvg > 0 ? book.ratingAvg : book.rating;
            if (douban >= 8.5) {
                pick.score += 35;
                pick.reasons.add("豆瓣评分 " + trim(douban));
            } else if (douban >= 7.5) {
                pick.score += 20;
                pick.reasons.add("豆瓣评分 " + trim(douban));
            }
            if (own >= 4.5) {
                pick.score += 20;
                pick.reasons.add("你给它标了 " + ReadingRecords.formatRating(own) + " 星");
            }
            int days = daysSinceArrived(book, todayKey);
            if (days >= 365) {
                pick.score += 30;
                pick.reasons.add("到手 " + Math.max(1, Math.round(days / 30.0)) + " 个月没翻开");
            } else if (days >= 90) {
                pick.score += 14;
                pick.reasons.add("到手 " + days + " 天还没读");
            }
            Affinity best = bestAffinity(book, affinity);
            if (best != null) {
                pick.score += Math.min(25, best.hits * 8);
                pick.reasons.add("你读完或打高分的书里有 " + best.hits
                        + " 本带「" + best.tag + "」");
            }
            if (book.pages > 0 && book.pages <= 220) {
                pick.score += 12;
                pick.reasons.add("只有 " + book.pages + " 页，容易开个头");
            }
            if (pick.reasons.isEmpty()) {
                pick.reasons.add("还没读过，先从它开始");
            }
            picks.add(pick);
        }
        picks.sort((x, y) -> Double.compare(y.score, x.score));
        return picks;
    }

    /** 在前 {@link #POOL} 名里按分数加权抽一本；没有候选返回 null */
    public static Pick choose(List<Book> raw, int todayKey, long seed) {
        return chooseFrom(rank(raw, todayKey), seed);
    }

    /** 从已经排好序的候选里抽（统计页缓存了 rank 结果，「换一本」不必重算） */
    public static Pick chooseFrom(List<Pick> ranked, long seed) {
        return chooseFrom(ranked, seed, -1L);
    }

    /**
     * 加权抽一本，可排除「现在这本」。
     *
     * <p>换一本若只是换种子，仍可能抽回同一本（候选少、分数悬殊时很容易），
     * 用户看到的就是「点了没反应」，所以点「换一本」时要把当前 id 传进来。
     *
     * @param excludeId 不再抽中的书 id；传负数表示不排除
     */
    public static Pick chooseFrom(List<Pick> ranked, long seed, long excludeId) {
        if (ranked == null || ranked.isEmpty()) {
            return null;
        }
        List<Pick> pool = new ArrayList<>(ranked.subList(0, Math.min(POOL, ranked.size())));
        if (excludeId >= 0 && pool.size() > 1) {
            for (int i = 0; i < pool.size(); i++) {
                if (pool.get(i).book.id == excludeId) {
                    pool.remove(i);
                    break;
                }
            }
        }
        double total = 0;
        for (Pick pick : pool) {
            total += pick.score + 1; // +1：低分的也留一点机会
        }
        double roll = new Random(seed).nextDouble() * total;
        for (Pick pick : pool) {
            roll -= pick.score + 1;
            if (roll <= 0) {
                return pick;
            }
        }
        return pool.get(0);
    }

    /** 候选池：没读过、现在能拿到手（不在借出/遗失/电子书签里） */
    private static boolean isCandidate(Book book) {
        if (book == null || book.title == null || book.title.trim().isEmpty()) {
            return false;
        }
        if (book.readStatus != Book.READ_NONE) {
            return false;
        }
        return book.stockStatus != Book.STOCK_LENT
                && book.stockStatus != Book.STOCK_LOST
                && book.stockStatus != Book.STOCK_BOOKMARK;
    }

    /** 从「读完过」或「打过 4 星以上」的书里统计标签偏好 */
    private static Map<String, Integer> tagAffinity(List<Book> books) {
        Map<String, Integer> map = new HashMap<>();
        for (Book book : books) {
            double own = book.ratingAvg > 0 ? book.ratingAvg : book.rating;
            if (book.readStatus != Book.READ_DONE && own < 4) {
                continue;
            }
            for (String tag : splitTags(book.tags)) {
                Integer old = map.get(tag);
                map.put(tag, old == null ? 1 : old + 1);
            }
        }
        return map;
    }

    /** 命中最多的一次标签（String.join / Map.entry 都是 API 26+，minSdk 24 用不了） */
    private static final class Affinity {
        final String tag;
        final int hits;

        Affinity(String tag, int hits) {
            this.tag = tag;
            this.hits = hits;
        }
    }

    private static Affinity bestAffinity(Book book, Map<String, Integer> affinity) {
        Affinity best = null;
        for (String tag : splitTags(book.tags)) {
            Integer hits = affinity.get(tag);
            if (hits != null && (best == null || hits > best.hits)) {
                best = new Affinity(tag, hits);
            }
        }
        return best;
    }

    private static List<String> splitTags(String tags) {
        List<String> out = new ArrayList<>();
        if (tags == null || tags.trim().isEmpty()) {
            return out;
        }
        for (String part : tags.split("[,，]")) {
            String tag = part.trim();
            if (!tag.isEmpty()) {
                out.add(tag);
            }
        }
        return out;
    }

    /** 到手多久了：优先购入日期，没填就用录入时间 */
    private static int daysSinceArrived(Book book, int todayKey) {
        int key = DateKeys.parseKey(book.purchaseDate);
        if (key <= 0) {
            if (book.createdAt <= 0) {
                return 0;
            }
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(book.createdAt);
            key = c.get(Calendar.YEAR) * 10000 + (c.get(Calendar.MONTH) + 1) * 100
                    + c.get(Calendar.DAY_OF_MONTH);
        }
        if (key <= 0 || key > todayKey) {
            return 0;
        }
        return BorrowReminder.daysBetween(key, todayKey);
    }

    private static double parseDouban(String value) {
        if (value == null || value.trim().isEmpty()) {
            return 0;
        }
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String trim(double value) {
        return value == Math.floor(value)
                ? String.valueOf((long) value) : String.valueOf(Math.round(value * 10) / 10.0);
    }
}
