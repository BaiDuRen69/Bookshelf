package com.bai.bookshelf.util;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.CheckIn;

import java.util.ArrayList;
import java.util.Collection;
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
        REJECTED_BACKFILL,
        /** 该日已记在同一本书的另一轮：不能直接抢过来，UI 给「移到本轮」 */
        REJECTED_OTHER_SEQ
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

        /** 该日当前记在哪一轮；0 = 没记过。跨轮判定靠它，不能只看 exists */
        int seqOf(long bookId, int dayKey);
    }

    private CheckInStore() {
    }

    // ==================== 写入 ====================

    /** 生产入口：「今天」取系统当前日；打卡落库后顺手把精确轮的派生起止同步掉 */
    public static Result toggle(AppDatabase db, Book book, int dayKey, boolean allowBackfill) {
        return toggle(db, book, dayKey, allowBackfill, currentSeq(book));
    }

    /**
     * 指定轮次的打卡/撤销（v5.9.1 第六步）。UI 在精确轮下传入该轮 seq，
     * 老入口不传时归到「当前在读的那一轮」，与历史行为一致。
     */
    public static Result toggle(AppDatabase db, Book book, int dayKey, boolean allowBackfill,
                                int seq) {
        if (db == null || book == null) {
            return Result.REJECTED_NOT_READING;
        }
        if (seq < 1) {
            seq = currentSeq(book);
        }
        int recorded = db.checkInDao().getDayKeys(book.id, seq).size();
        Result result = toggle(new RoomSink(db), book, dayKey, allowBackfill,
                DateKeys.todayKey(), seq, recorded);
        if (result == Result.ADDED || result == Result.REMOVED) {
            resyncPreciseRounds(db, book);
        }
        return result;
    }

    /**
     * 日集合变了（打卡/撤销/选择器确定/跨轮移动）之后，统一在这里重算派生起止并回写书籍。
     *
     * <p>只碰精确轮：有日期的模糊轮一个字不改（历史零改写）。精确轮的起止本来就是派生值，
     * 所以每一场都按它自己那轮的日集合重算；日集合没变的轮次算出来还是原值，幂等。
     * 日集合为空时 deriveRange 不写，开始日保留新会话落下的今天锚点。
     *
     * <p>无日期、无起止的模糊轮是历史遗留的「只知道在读」：一旦它有了打卡记录，
     * 打卡日就是唯一的阅读证据，一次性提升成精确轮（v5.9.3）。有日期的模糊轮不动——
     * 用户填过的区间仍按区间算，要换口径走编辑页的模式切换。
     */
    static void resyncPreciseRounds(AppDatabase db, Book book) {
        if (db == null || book == null) {
            return;
        }
        List<ReadingRecords.Record> records = ReadingRecords.load(book);
        boolean touched = false;
        // syncTo 的评分只取已读会话：在读轮（含提升出来的和撤销到 0 天的）会把它清零，
        // 先把用户打的分存下来，算完再按需还原
        final double ratingAvgBefore = book.ratingAvg;
        final int ratingBefore = book.rating;
        for (ReadingRecords.Record r : records) {
            List<Integer> keys = db.checkInDao().getDayKeys(book.id, r.seq);
            if (r.mode != ReadingRecords.MODE_PRECISE) {
                boolean undated = r.startDate.isEmpty() && r.endDate.isEmpty();
                if (!(r.mode == ReadingRecords.MODE_FUZZY && undated && !keys.isEmpty())) {
                    continue;
                }
                r.mode = ReadingRecords.MODE_PRECISE;
            }
            ReadingRecords.deriveRange(r, keys);
            touched = true;
        }
        if (!touched) {
            return;
        }
        ReadingRecords.syncTo(book, records);
        if (book.ratingAvg == 0 && (ratingAvgBefore > 0 || ratingBefore > 0)) {
            book.ratingAvg = ratingAvgBefore;
            book.rating = ratingBefore;
        }
        book.updatedAt = System.currentTimeMillis();
        book.dirty = true;
        db.bookDao().update(book);
    }

    /** 把若干天从它们现在的轮次挪到 toSeq（「移到本轮」动作）；返回真的挪了几条 */
    public static int moveToSeq(AppDatabase db, long bookId, List<Integer> dayKeys, int toSeq) {
        if (db == null || dayKeys == null || dayKeys.isEmpty()) {
            return 0;
        }
        List<Integer> movable = new ArrayList<>();
        for (Integer key : dayKeys) {
            if (key == null || key <= 0) {
                continue;
            }
            int owned = seqOf(db, bookId, key);
            if (owned != 0 && owned != toSeq) {
                movable.add(key);
            }
        }
        if (movable.isEmpty()) {
            return 0;
        }
        db.checkInDao().updateSeq(bookId, movable, toSeq);
        Book book = db.bookDao().getById(bookId);
        if (book != null) {
            resyncPreciseRounds(db, book);
        }
        return movable.size();
    }

    /** applyDayKeys 的结果计数：让 UI 能说清「加了几天、删了几天、几天没进去、为什么」 */
    public static final class BatchResult {
        public int added;
        public int removed;
        public int rejectedFuture;
        public int rejectedOtherSeq;

        public boolean changed() {
            return added > 0 || removed > 0;
        }

        public boolean rejected() {
            return rejectedFuture > 0 || rejectedOtherSeq > 0;
        }
    }

    /**
     * 把某一轮的日集合对齐到 targetKeys：差集最小改动——只插新增、只删移除，
     * 已存在且仍需要的行一个字不动（createdAt / backfill 都保留）。
     *
     * <p>新增里超过今天的记 rejectedFuture、已记在别轮的记 rejectedOtherSeq，都不写库；
     * 结束后统一 deriveRange + syncTo，调用方不许自己写起止。
     */
    public static BatchResult applyDayKeys(AppDatabase db, Book book, int seq,
                                           List<Integer> targetKeys, int todayKey) {
        BatchResult out = new BatchResult();
        if (db == null || book == null) {
            return out;
        }
        Set<Integer> target = new HashSet<>();
        if (targetKeys != null) {
            for (Integer key : targetKeys) {
                if (key != null && key > 0) {
                    target.add(key);
                }
            }
        }
        Set<Integer> current = new HashSet<>(db.checkInDao().getDayKeys(book.id, seq));
        long now = System.currentTimeMillis();
        for (int key : current) {
            if (!target.contains(key)) {
                CheckIn pk = new CheckIn();
                pk.bookId = book.id;
                pk.dayKey = key;
                db.checkInDao().delete(pk);
                out.removed++;
            }
        }
        List<Integer> addedKeys = new ArrayList<>(target);
        java.util.Collections.sort(addedKeys);
        for (int key : addedKeys) {
            if (current.contains(key)) {
                continue;
            }
            if (key > todayKey) {
                out.rejectedFuture++;
                continue;
            }
            int owned = seqOf(db, book.id, key);
            if (owned != 0 && owned != seq) {
                out.rejectedOtherSeq++;
                continue;
            }
            CheckIn c = new CheckIn();
            c.bookId = book.id;
            c.dayKey = key;
            c.createdAt = now;
            c.backfill = isBackfill(key, todayKey);
            c.seq = seq;
            if (db.checkInDao().insert(c) != -1) {
                out.added++;
            }
        }
        if (out.changed()) {
            resyncPreciseRounds(db, book);
        }
        return out;
    }

    /** 单日批量打卡的结果：UI 只按计数说话，不猜哪些书为什么没进去 */
    public static final class DayBatchResult {
        public int added;
        public int skippedOtherSeq;
        public int skippedBeforeStart;
        public int skippedFuture;
        public int skippedNotReading;
        public final List<Long> addedBookIds = new ArrayList<>();

        public boolean changed() {
            return added > 0;
        }

        public int skipped() {
            return skippedOtherSeq + skippedBeforeStart + skippedFuture + skippedNotReading;
        }
    }

    /**
     * 同一天一次给多本书打卡：整批一个事务，要么都写、要么都不写。
     *
     * <p>只处理「还没记过这一天」的书；已记在本轮的直接跳过，已记在别轮的
     * 记 skippedOtherSeq（不抢）。写完统一重算每本书的精确轮起止。
     */
    public static DayBatchResult addDayForBooks(AppDatabase db, List<Book> books,
                                                int dayKey, int todayKey) {
        DayBatchResult out = new DayBatchResult();
        if (db == null || books == null || books.isEmpty()) {
            return out;
        }
        if (dayKey > todayKey) {
            out.skippedFuture = books.size();
            return out;
        }
        final Set<Long> seen = new HashSet<>();
        final List<Book> added = new ArrayList<>();
        db.runInTransaction(() -> {
            for (Book input : books) {
                if (input == null || !seen.add(input.id)) {
                    continue;
                }
                Book book = db.bookDao().getById(input.id);
                if (book == null) {
                    continue;
                }
                if (book.readStatus != Book.READING) {
                    out.skippedNotReading++;
                    continue;
                }
                int seq = currentSeq(book);
                int owned = seqOf(db, book.id, dayKey);
                if (owned != 0) {
                    // 已打卡（同轮）视为完成；别轮交给调用方的「移到本轮」
                    if (owned != seq) {
                        out.skippedOtherSeq++;
                    }
                    continue;
                }
                int recorded = db.checkInDao().getDayKeys(book.id, seq).size();
                Result r = toggle(new RoomSink(db), book, dayKey, true, todayKey, seq, recorded);
                if (r == Result.ADDED) {
                    out.added++;
                    added.add(book);
                } else if (r == Result.REJECTED_OTHER_SEQ) {
                    out.skippedOtherSeq++;
                } else if (r == Result.REJECTED_FUTURE) {
                    out.skippedFuture++;
                } else if (r != Result.REMOVED) {
                    out.skippedBeforeStart++;
                }
            }
            for (Book book : added) {
                resyncPreciseRounds(db, book);
            }
        });
        for (Book book : added) {
            out.addedBookIds.add(book.id);
        }
        return out;
    }

    /** 撤销一次单日批量：只删这些书这一天的行，返回真的删掉几条 */
    public static int removeDayForBooks(AppDatabase db, List<Long> bookIds, int dayKey) {
        if (db == null || bookIds == null || bookIds.isEmpty()) {
            return 0;
        }
        final int[] removed = {0};
        db.runInTransaction(() -> {
            Set<Long> existing = new HashSet<>();
            for (CheckIn c : db.checkInDao().getByDay(dayKey)) {
                existing.add(c.bookId);
            }
            Set<Long> touched = new HashSet<>();
            for (Long id : bookIds) {
                if (id == null || !touched.add(id) || !existing.contains(id)) {
                    continue;
                }
                CheckIn pk = new CheckIn();
                pk.bookId = id;
                pk.dayKey = dayKey;
                db.checkInDao().delete(pk);
                removed[0]++;
            }
            for (long id : touched) {
                Book book = db.bookDao().getById(id);
                if (book != null) {
                    resyncPreciseRounds(db, book);
                }
            }
        });
        return removed[0];
    }

    /** 该日现在记在哪一轮；0 = 没记过 */
    private static int seqOf(AppDatabase db, long bookId, int dayKey) {
        for (CheckIn c : db.checkInDao().getByDay(dayKey)) {
            if (c.bookId == bookId) {
                return c.seq;
            }
        }
        return 0;
    }

    /** 当前进行中会话的轮次；没有在 reading 的就归到第一轮（与历史行默认值一致） */
    public static int currentSeq(Book book) {
        ReadingRecords.Record ongoing = ReadingRecords.ongoingReading(ReadingRecords.load(book));
        return ongoing == null ? 1 : ongoing.seq;
    }

    /** 可注入实现：单测用固定 todayKey，UI 也走这里以便复用同一套规则 */
    static Result toggle(Sink sink, Book book, int dayKey, boolean allowBackfill, int todayKey) {
        return toggle(sink, book, dayKey, allowBackfill, todayKey, currentSeq(book), 0);
    }

    static Result toggle(Sink sink, Book book, int dayKey, boolean allowBackfill, int todayKey,
                         int seq) {
        return toggle(sink, book, dayKey, allowBackfill, todayKey, seq, 0);
    }

    /**
     * @param recordedDays 该轮已记天数；0 表示调用方拿不到日集合（纯规则单测），
     *                     精确轮按「第一次补历史」处理，仍退到拥有日
     */
    static Result toggle(Sink sink, Book book, int dayKey, boolean allowBackfill, int todayKey,
                         int seq, int recordedDays) {
        if (book == null || sink == null) {
            return Result.REJECTED_NOT_READING;
        }
        if (seq < 1) {
            seq = currentSeq(book);
        }
        // 撤销只是删一条打卡记录，不改阅读状态；书已读完、日期已过期也允许撤，
        // 所以先于范围检查处理。但只能撤本轮的：别轮的日子要「移到本轮」而不是抢。
        int owned = sink.seqOf(book.id, dayKey);
        if (owned == seq) {
            sink.delete(book.id, dayKey);
            return Result.REMOVED;
        }
        if (owned != 0) {
            return Result.REJECTED_OTHER_SEQ;
        }
        Result reason = rejectReason(book, dayKey, todayKey, seq, recordedDays);
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
        // 归到当前在读的那一轮；精确轮靠 (bookId, seq) 取日集合，归错轮天数就错了
        c.seq = seq;
        // 并发下若已被别人写入，IGNORE 返回 false；按幂等视作已打卡
        sink.insert(c);
        return Result.ADDED;
    }

    /**
     * 能不能在这一天打卡：不晚于今天、书处于在读、不早于下界。
     *
     * <p>下界分模式（v5.9.1 修订）：模糊轮沿用会话起始日；精确轮的意义就是补历史，
     * 卡在会话起始日毫无用处，所以下界退到「这本书什么时候拥有的」——
     * 拥有日 → 录入当天 → 都没有就不设下界。上界始终是今天。
     */
    public static boolean canCheckIn(Book book, int dayKey, int todayKey) {
        return rejectReason(book, dayKey, todayKey) == null;
    }

    private static Result rejectReason(Book book, int dayKey, int todayKey) {
        return rejectReason(book, dayKey, todayKey, currentSeq(book), 0);
    }

    private static Result rejectReason(Book book, int dayKey, int todayKey, int seq,
                                       int recordedDays) {
        if (book == null) {
            return Result.REJECTED_NOT_READING;
        }
        if (dayKey > todayKey) {
            return Result.REJECTED_FUTURE;
        }
        if (book.readStatus != Book.READING) {
            return Result.REJECTED_NOT_READING;
        }
        int start = earliestCheckInKey(book, seq, recordedDays);
        if (start > 0 && dayKey < start) {
            return Result.REJECTED_BEFORE_START;
        }
        return null;
    }

    /**
     * 这一轮允许打卡的最早一天（0 = 不设下界）。
     *
     * <p>模糊轮仍看当前 READING 会话的起始日。精确轮的意义是补历史：还没记过日子时
     * 退到拥有日 / 录入当天；一旦本轮已有日集合，就收到该轮起点，避免「先记今天、
     * 再无限往前补」把会话起点悄悄挪走。
     */
    public static int earliestCheckInKey(Book book, int recordedDays) {
        return earliestCheckInKey(book, currentSeq(book), recordedDays);
    }

    /** 指定轮的最早可打卡日（撤销后行是否还该显示，UI 也要按同一口径判） */
    public static int earliestCheckInKey(Book book, int seq, int recordedDays) {
        if (book == null) {
            return 0;
        }
        List<ReadingRecords.Record> records = ReadingRecords.load(book);
        ReadingRecords.Record round = null;
        for (ReadingRecords.Record r : records) {
            if (r.seq == seq) {
                round = r;
                break;
            }
        }
        if (round == null) {
            // 指定轮还没写进记录（历史/测试构造）时按当前会话判模式，保旧行为
            round = ReadingRecords.ongoingReading(records);
        }
        boolean precise = round != null && round.mode == ReadingRecords.MODE_PRECISE;
        if (!precise) {
            return sessionStartKey(book);
        }
        if (recordedDays > 0) {
            int start = DateKeys.parseKey(round.startDate);
            if (start > 0) {
                return start;
            }
        }
        return ownershipStartKey(book);
    }

    /** 某一轮已记几天；只认这一轮自己的行 */
    public static int recordedDays(List<CheckIn> all, long bookId, int seq) {
        if (all == null || seq < 1) {
            return 0;
        }
        int days = 0;
        for (CheckIn c : all) {
            if (c != null && c.bookId == bookId && c.seq == seq) {
                days++;
            }
        }
        return days;
    }

    /**
     * 精确轮的下界：拥有日（purchaseDate，部分日期展开到区间起点）→ 录入当天 → 0（不设下界）。
     * 书还没拥有就读不了，这条比会话起始日更讲得通。
     */
    private static int ownershipStartKey(Book book) {
        int owned = DateKeys.parseStartKey(book.purchaseDate);
        if (owned > 0) {
            return owned;
        }
        if (book.createdAt > 0) {
            return DateKeys.dayKey(book.createdAt);
        }
        return 0;
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
        return streakDaysOf(distinctDays(all), todayKey);
    }

    /**
     * 同上，但直接吃「已去重的自然日」：打卡页现在只需 SQL 的 DISTINCT dayKey，
     * 不必把整张表读成 CheckIn 行再逐行聚合。
     *
     * <p>不叫 streakDays 重载：{@code streakDays(null, ...)} / 空列表字面量会在
     * List&lt;CheckIn&gt; 与 Collection&lt;Integer&gt; 之间产生歧义，旧测试因此编不过。
     */
    public static int[] streakDaysOf(Collection<Integer> days, int todayKey) {
        if (days == null || days.isEmpty()) {
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

    /**
     * 选中日属于某本书的某一轮时，这一轮已经记了几天；不属于返回 -1。
     *
     * <p>打卡页摘要行「本轮已记 N 天」用它（v5.9.1 第六步）：只认日集合里的日子，
     * 没记过的日子没有「这一轮」可指，猜区间会把模糊口径又搬回来。
     */
    public static int roundDaysAt(List<CheckIn> all, long bookId, int seq, int dayKey) {
        if (all == null || seq < 1) {
            return -1;
        }
        int days = 0;
        boolean hit = false;
        for (CheckIn c : all) {
            if (c == null || c.bookId != bookId || c.seq != seq) {
                continue;
            }
            days++;
            if (c.dayKey == dayKey) {
                hit = true;
            }
        }
        return hit ? days : -1;
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
            return seqOf(bookId, dayKey) != 0;
        }

        @Override
        public int seqOf(long bookId, int dayKey) {
            for (CheckIn c : db.checkInDao().getByDay(dayKey)) {
                if (c.bookId == bookId) {
                    return c.seq;
                }
            }
            return 0;
        }
    }
}
