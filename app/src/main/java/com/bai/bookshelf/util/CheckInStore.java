package com.bai.bookshelf.util;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.CheckIn;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 每日阅读打卡的业务规则（打卡页一期）。
 *
 * <p>纯静态、不依赖任何 Android View / Context，全部日期用 {@link DateKeys} 的
 * yyyyMMdd 整数键，因此整层可以在 JVM 单测里跑。打卡只写 check_ins，
 * 不改写 {@code Book.readStatus}、不新建或结束阅读会话。
 */
public final class CheckInStore {

    public enum Result {
        ADDED,
        REMOVED,
        REJECTED_FUTURE,
        REJECTED_BEFORE_START,
        REJECTED_NOT_READING,
        /** 调用方禁止补卡时，点过去某日 */
        REJECTED_BACKFILL
    }

    /**
     * 数据访问抽象：Room 的 AppDatabase_Impl 依赖 Android 运行时，
     * JVM 单测里无法实例化，也不能为测试引入 Mockito。生产走 {@link RoomSink}，
     * 单测走内存实现，toggle 的每条分支都能覆盖。
     */
    public interface Sink {
        /** @return true = 真新增；false = 复合主键冲突被 IGNORE */
        boolean insert(CheckIn c);

        void delete(long bookId, int dayKey);

        boolean exists(long bookId, int dayKey);
    }

    private CheckInStore() {
    }

    // ==================== 写入 ====================

    /** 生产入口：「今天」取系统当前日 */
    public static Result toggle(AppDatabase db, Book book, int dayKey, boolean allowBackfill) {
        return toggle(new RoomSink(db), book, dayKey, allowBackfill, DateKeys.todayKey());
    }

    /** 可注入实现：单测用固定 todayKey，UI 也走这里以便复用同一套规则 */
    static Result toggle(Sink sink, Book book, int dayKey, boolean allowBackfill, int todayKey) {
        if (book == null || sink == null) {
            return Result.REJECTED_NOT_READING;
        }
        // 撤销只是删一条打卡记录，不改阅读状态；书已读完、日期已过期也允许撤，
        // 所以先于范围检查处理。
        if (sink.exists(book.id, dayKey)) {
            sink.delete(book.id, dayKey);
            return Result.REMOVED;
        }
        Result reason = rejectReason(book, dayKey, todayKey);
        if (reason != null) {
            return reason;
        }
        if (!allowBackfill && dayKey < todayKey) {
            return Result.REJECTED_BACKFILL;
        }
        CheckIn c = new CheckIn();
        c.bookId = book.id;
        c.dayKey = dayKey;
        c.createdAt = System.currentTimeMillis();
        c.backfill = isBackfill(dayKey, todayKey);
        c.pages = 0; // 本期只存不用
        // 并发下若已被别人写入，IGNORE 返回 false；按幂等视作已打卡
        sink.insert(c);
        return Result.ADDED;
    }

    /**
     * 能不能在这一天打卡：不晚于今天、书处于在读、不早于当前阅读会话起始日。
     * 起始日解析不到时退化为「不晚于今天即可」。
     */
    public static boolean canCheckIn(Book book, int dayKey, int todayKey) {
        return rejectReason(book, dayKey, todayKey) == null;
    }

    private static Result rejectReason(Book book, int dayKey, int todayKey) {
        if (book == null) {
            return Result.REJECTED_NOT_READING;
        }
        if (dayKey > todayKey) {
            return Result.REJECTED_FUTURE;
        }
        if (book.readStatus != Book.READING) {
            return Result.REJECTED_NOT_READING;
        }
        int start = sessionStartKey(book);
        if (start > 0 && dayKey < start) {
            return Result.REJECTED_BEFORE_START;
        }
        return null;
    }

    /**
     * 当前阅读会话的起始日键：取 readingRecords 里状态为 READING 的最后一场。
     * 没有会话或 startDate 解析不出来时返回 0，表示不设下界。
     */
    private static int sessionStartKey(Book book) {
        List<ReadingRecords.Record> records = ReadingRecords.load(book);
        for (int i = records.size() - 1; i >= 0; i--) {
            ReadingRecords.Record r = records.get(i);
            if (r.status == Book.READING) {
                int key = DateKeys.parseKey(r.startDate);
                return key > 0 ? key : 0;
            }
        }
        return 0;
    }

    public static boolean isBackfill(int dayKey, int todayKey) {
        return dayKey < todayKey;
    }

    // ==================== 读取 / 聚合 ====================

    /** @return {连续天数, 最近一次打卡日}；无记录时 {0, 0} */
    public static int[] streakDays(List<CheckIn> all, int todayKey) {
        Set<Integer> days = distinctDays(all);
        if (days.isEmpty()) {
            return new int[]{0, 0};
        }
        int last = 0;
        for (int d : days) {
            if (d <= todayKey && d > last) {
                last = d;
            }
        }
        if (last == 0) {
            return new int[]{0, 0}; // 只有未来异常数据
        }
        int yesterday = DateKeys.shiftDayKey(todayKey, -1);
        // 今天没打但昨天打了：连续不清零；断到更早则清零
        if (last != todayKey && last != yesterday) {
            return new int[]{0, last};
        }
        int streak = 0;
        int cursor = last;
        while (days.contains(cursor)) {
            streak++;
            cursor = DateKeys.shiftDayKey(cursor, -1);
        }
        return new int[]{streak, last};
    }

    /** 今天还没打、但昨天打过 → 首页提示「今天待打卡」 */
    public static boolean pendingToday(List<CheckIn> all, int todayKey) {
        Set<Integer> days = distinctDays(all);
        return !days.contains(todayKey)
                && days.contains(DateKeys.shiftDayKey(todayKey, -1));
    }

    /** 每个自然日的打卡「次数」（同一天多本 = 多次），月/年视图打点用 */
    public static Map<Integer, Integer> countByDayInRange(List<CheckIn> list) {
        Map<Integer, Integer> out = new HashMap<>();
        if (list == null) {
            return out;
        }
        for (CheckIn c : list) {
            if (c == null) {
                continue;
            }
            Integer old = out.get(c.dayKey);
            out.put(c.dayKey, old == null ? 1 : old + 1);
        }
        return out;
    }

    /** @return {打卡天数, 打卡次数, 补卡次数}；同一天两本 = 1 天 2 次 */
    public static int[] monthSummary(List<CheckIn> list, int year, int month) {
        return summaryInRange(list, DateKeys.keyOf(year, month, 1),
                DateKeys.keyOf(year, month, DateKeys.daysInMonth(year, month)));
    }

    /** @return {打卡天数, 打卡次数, 补卡次数} */
    public static int[] yearSummary(List<CheckIn> list, int year) {
        return summaryInRange(list, DateKeys.keyOf(year, 1, 1), DateKeys.keyOf(year, 12, 31));
    }

    private static int[] summaryInRange(List<CheckIn> list, int start, int end) {
        Set<Integer> days = new HashSet<>();
        int times = 0;
        int backfills = 0;
        if (list != null) {
            for (CheckIn c : list) {
                if (c == null || c.dayKey < start || c.dayKey > end) {
                    continue;
                }
                days.add(c.dayKey);
                times++;
                if (c.backfill) {
                    backfills++;
                }
            }
        }
        return new int[]{days.size(), times, backfills};
    }

    private static Set<Integer> distinctDays(List<CheckIn> all) {
        Set<Integer> out = new HashSet<>();
        if (all == null) {
            return out;
        }
        for (CheckIn c : all) {
            if (c != null) {
                out.add(c.dayKey);
            }
        }
        return out;
    }

    // ==================== Room 实现 ====================

    private static final class RoomSink implements Sink {
        private final AppDatabase db;

        RoomSink(AppDatabase db) {
            this.db = db;
        }

        @Override
        public boolean insert(CheckIn c) {
            return db.checkInDao().insert(c) != -1;
        }

        @Override
        public void delete(long bookId, int dayKey) {
            CheckIn c = new CheckIn();
            c.bookId = bookId;
            c.dayKey = dayKey;
            db.checkInDao().delete(c); // @Delete 只按复合主键匹配
        }

        @Override
        public boolean exists(long bookId, int dayKey) {
            for (CheckIn c : db.checkInDao().getByDay(dayKey)) {
                if (c.bookId == bookId) {
                    return true;
                }
            }
            return false;
        }
    }
}
