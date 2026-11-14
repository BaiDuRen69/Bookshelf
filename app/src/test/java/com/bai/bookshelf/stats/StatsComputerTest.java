package com.bai.bookshelf.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Category;
import com.bai.bookshelf.data.entity.CheckIn;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.data.entity.NoteImage;
import com.bai.bookshelf.data.entity.WishBook;
import com.bai.bookshelf.util.DateKeys;
import com.bai.bookshelf.util.LibraryStats;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 四栏「关键数字」的口径网（v5.9.5 卡 3）：内存造 Book / BookCopy / BorrowRecord，
 * 直接把 {@link StatsComputer} 跑一遍，逐条钉住曾经算错过的规则。
 *
 * <p>这里没有时钟注入（{@code DateKeys.todayKey()} 与 {@code Calendar.getInstance()} 都在
 * 口径层内部），所以样本日期一律相对今天算，断言的数字也从同一批相对日期推出来——
 * 这样测试在任何一天跑都成立，不需要改天就把 155 换成 168。
 */
public class StatsComputerTest {

    private static final int TODAY = DateKeys.todayKey();

    private static final int YEAR = TODAY / 10000;

    /** 年初（yyyyMMdd） */
    private static final int YEAR_START = YEAR * 10000 + 101;

    /** 今年内、且不晚于今天的日子：取「年初 → 今天」的中点，两张同期口径的卡都能命中 */
    private static String thisYearDate() {
        int back = DateKeys.daysBetween(YEAR_START, TODAY) / 2;
        return DateKeys.formatKey(DateKeys.shiftDayKey(TODAY, -back));
    }

    private static String dayKeyText(int key) {
        return DateKeys.formatKey(key);
    }

    private static Book book(long id, int ownType, double listPrice, double paid, int quantity) {
        Book b = new Book();
        b.id = id;
        b.title = "书" + id;
        b.ownType = ownType;
        b.listPrice = listPrice;
        b.purchasePrice = paid;
        b.quantity = quantity;
        b.purchaseDate = thisYearDate();
        b.createdAt = 1000L + id;
        return b;
    }

    private static BookCopy copy(long bookId, int copyIndex, double paid) {
        BookCopy c = new BookCopy();
        c.bookId = bookId;
        c.copyIndex = copyIndex;
        c.purchasePrice = paid;
        c.stockStatus = Book.STOCK_IN;
        return c;
    }

    private static BorrowRecord returned(long id, long bookId, int dueOffset, int returnOffset) {
        BorrowRecord r = new BorrowRecord();
        r.id = id;
        r.bookId = bookId;
        r.borrower = "某人";
        r.lendDate = dayKeyText(DateKeys.shiftDayKey(TODAY, dueOffset - 30));
        r.dueDate = dayKeyText(DateKeys.shiftDayKey(TODAY, dueOffset));
        r.returnDate = dayKeyText(DateKeys.shiftDayKey(TODAY, returnOffset));
        r.status = BorrowRecord.STATUS_RETURNED;
        r.copyIndex = 1;
        return r;
    }

    private static StatsComputer.Result compute(List<Book> books, List<BookCopy> copies,
                                                List<BorrowRecord> loans) {
        StatsComputer.Source src = new StatsComputer.Source() {
            @Override
            public List<BookCopy> bookCopies() {
                return copies;
            }

            @Override
            public List<BorrowRecord> borrowRecords() {
                return loans;
            }

            @Override
            public List<CheckIn> checkIns() {
                return Collections.emptyList();
            }

            @Override
            public List<Category> categories() {
                return Collections.emptyList();
            }

            @Override
            public List<NoteImage> noteImages() {
                return Collections.emptyList();
            }

            @Override
            public List<Excerpt> excerpts() {
                return Collections.emptyList();
            }

            @Override
            public List<WishBook> wishBooks() {
                return Collections.emptyList();
            }
        };
        return new StatsComputer(src, 20261111L).compute(books);
    }

    /**
     * ① 花费按册（{@code purchasePrice × max(quantity, 1)}），借阅与赠书一分不算。
     *
     * <p>自购 ¥30×3 册 + 自购 ¥40×1 册 + 借阅 ¥99 + 赠书 ¥77：花费只有 90+40=130。
     * 同一批书的「总花费」平铺字段（{@code snapshot.totalSpent}）走的是老口径——按种、
     * 且赠书也算钱——它是 147，这里一并钉住，防止两口径在无人察觉时互相漂移。
     */
    @Test
    public void 花费只算自购且按册累加() {
        List<Book> books = Arrays.asList(
                book(1, Book.OWN_BUY, 50, 30, 3),
                book(2, Book.OWN_BUY, 60, 40, 1),
                book(3, Book.OWN_BORROW, 99, 99, 1),
                book(4, Book.OWN_GIFT, 77, 77, 1));
        StatsComputer.Result r = compute(books, Collections.emptyList(), Collections.emptyList());

        assertEquals(130.0, r.snapshot.hold.paid, 1e-9);
        assertEquals(130.0, r.snapshot.spend.paid, 1e-9);
        // 定价按册：50×3 + 60×1 = 210；赠书只进「赠书价值」，不进定价合计
        assertEquals(210.0, r.snapshot.hold.listValue, 1e-9);
        assertEquals(77.0, r.snapshot.hold.giftValue, 1e-9);
        // 平铺的「总花费」：按种 + 含赠书（30 + 40 + 77），与按册口径不同源
        assertEquals(147.0, r.snapshot.totalSpent, 1e-9);
        assertEquals(187.0, r.snapshot.totalPrice, 1e-9);
    }

    /**
     * ② 同一句「今年花销」，洞察卡（同比行的 ¥）与趋势卡必须一个数。
     *
     * <p>曾经 ¥366 对 ¥381：一处按种求和、一处按册求和。这里 90+40=130，
     * 按种会得到 70，两个都必须落在 130。
     */
    @Test
    public void 今年花销在洞察卡与趋势卡是同一个数() {
        List<Book> books = Arrays.asList(
                book(1, Book.OWN_BUY, 50, 30, 3),
                book(2, Book.OWN_BUY, 60, 40, 1),
                book(3, Book.OWN_BORROW, 99, 99, 1),
                book(4, Book.OWN_GIFT, 77, 77, 1));
        StatsComputer.Result r = compute(books, Collections.emptyList(), Collections.emptyList());

        double insight = r.snapshot.periods.get(0).nowSpent;
        double trend = r.snapshot.trend.yearSpent;
        assertEquals(130.0, insight, 1e-9);
        assertEquals("洞察卡与趋势卡的「今年花销」不是同一个数", insight, trend, 1e-9);
        assertEquals("按种求和的 70 又冒出来了", 130.0, trend, 1e-9);
        // 张数各自的口径：同比行含赠书（3 种），趋势只算自购（2 种）
        assertEquals(3, r.snapshot.periods.get(0).nowBought);
        assertEquals(2, r.snapshot.trend.yearBought);
    }

    /**
     * ③ 按时归还率 = {@code onTimeCount / onTimeSample}，不是逐笔 0/1 的中位数。
     *
     * <p>6 笔按时、4 笔迟还：真值 0.6；中位数会把 [0,0,0,0,1,1,1,1,1,1] 判成 1.0，
     * 于是「全部按时归还」的假象上了屏。
     */
    @Test
    public void 按时归还率是笔数比而不是中位数() {
        List<BorrowRecord> loans = new ArrayList<>();
        double[] zeroOne = new double[10];
        for (int i = 0; i < 10; i++) {
            boolean onTime = i < 6;
            // 按时：归还日早于应还日 1 天；迟还：晚于应还日 3 天
            loans.add(returned(i + 1, 1, -100, onTime ? -101 : -97));
            zeroOne[i] = onTime ? 1.0 : 0.0;
        }
        StatsComputer.Result r = compute(
                Collections.singletonList(book(1, Book.OWN_BUY, 50, 30, 1)),
                Collections.emptyList(), loans);

        assertEquals(10, r.snapshot.lend.onTimeSample);
        assertEquals(6, r.snapshot.lend.onTimeCount);
        assertEquals(0.6, r.snapshot.lend.onTimeRate, 1e-9);
        // 同一批 0/1 序列取中位数会得到 1.0 的「全部按时」假象
        assertEquals(1.0, LibraryStats.median(zeroOne), 1e-9);
    }

    /**
     * ④ 派生指标按目标年截断：一本「在读、没填结束日期」的书，阅读日历会从开始日
     * 一路铺到今天，但习惯指标只准看当年。
     *
     * <p>开读日 = 今天往前 500 天（必定跨到去年）：日历上 501 天都在，
     * 「今年读了几天 / 当前连续 / 最长连续」只到年初，且任何一个月都不许超过 31 天
     * ——否则就是当年那句「7 月读了 155 天」（五个七月）和「连着 1627 天」。
     */
    @Test
    public void 习惯指标按目标年截断() {
        Book open = book(1, Book.OWN_BUY, 50, 30, 1);
        open.readStatus = Book.READING;
        open.readingStartDate = dayKeyText(DateKeys.shiftDayKey(TODAY, -500));
        open.readingFinishDate = "";
        StatsComputer.Result r = compute(Collections.singletonList(open),
                Collections.emptyList(), Collections.emptyList());

        int spread = 501;
        int thisYearDays = DateKeys.daysBetweenInclusive(YEAR_START, TODAY);
        assertEquals(spread, r.snapshot.totalReadingDays);
        assertTrue("样本必须跨年，否则这条测试测不到截断", thisYearDays < spread);
        assertEquals(thisYearDays, r.snapshot.habit.daysReadThisYear);
        // 当前连续按日键回退（DateKeys.shiftDayKey），跨年与跨月都正确
        assertEquals(thisYearDays, r.snapshot.habit.currentStreak);
        // 最长连续：本卡要钉的是「不许跨年」——绝不能出现 1627 这种五个七月撑出来的数
        assertTrue(r.snapshot.habit.longestStreak <= thisYearDays);
        // 最长连续与当前连续同源：落在当年的那一段本来就是连续的，跨月不该断
        // （曾按 yyyyMMdd 整数 +1 判相邻，1 月 31 日 +1 = 20260132 ≠ 20260201，被压死在单月内）
        assertEquals(thisYearDays, r.snapshot.habit.longestStreak);
        assertTrue("单月天数被跨年区间撑爆", r.snapshot.habit.bestMonthDays <= 31);
        assertFalse("没有打卡时习惯指标应回退到阅读区间", r.snapshot.habit.fromCheckIns);
    }

    /**
     * ⑤ 哨兵值不当累加器：无数据是 -1，一旦被 {@code +=} 吃掉就会把平均分、
     * 折扣、每页成本全拖成负数或凭空多一笔。
     */
    @Test
    public void 无数据时一律给哨兵而不是零或负数() {
        List<Book> empty = Arrays.asList(
                book(1, Book.OWN_BUY, 50, 30, 1),
                book(2, Book.OWN_BUY, 60, 40, 1),
                book(3, Book.OWN_BUY, 70, 50, 1));
        StatsComputer.Result r = compute(empty, Collections.emptyList(), Collections.emptyList());

        assertEquals(0, r.snapshot.rating.rated);
        assertEquals(-1.0, r.snapshot.rating.average, 1e-9);
        assertEquals(-1.0, r.snapshot.rating.bestCategoryAvg, 1e-9);
        assertEquals(1.0, r.snapshot.rating.unratedRate(), 1e-9);
        assertEquals(-1.0, r.snapshot.spend.discount, 1e-9);
        assertEquals(-1.0, r.snapshot.spend.perPage, 1e-9);
        assertEquals(-1.0, r.snapshot.spend.per10kWords, 1e-9);
        assertEquals("数据不足", r.snapshot.spend.discountText());
        assertEquals(-1.0, r.snapshot.lend.onTimeRate, 1e-9);
        assertEquals(-1.0, r.snapshot.lend.avgDaysOut, 1e-9);
        assertEquals(-1.0, r.snapshot.lend.avgOverdueDays, 1e-9);
        assertEquals(0, r.snapshot.avgDays);
        assertEquals(0, r.snapshot.habit.bestMonth);
        assertEquals(0, r.snapshot.habit.longestStreak);

        // 2 本评过分（4 星、5 星）：平均 4.5。若把未评分的 -1 也累进来会变成 2.67
        Book a = book(1, Book.OWN_BUY, 50, 30, 1);
        a.rating = 4;
        Book b = book(2, Book.OWN_BUY, 60, 40, 1);
        b.rating = 5;
        Book c = book(3, Book.OWN_BUY, 70, 50, 1);
        StatsComputer.Result scored = compute(Arrays.asList(a, b, c),
                Collections.emptyList(), Collections.emptyList());
        assertEquals(2, scored.snapshot.rating.rated);
        assertEquals(3, scored.snapshot.rating.total);
        assertEquals(4.5, scored.snapshot.rating.average, 1e-9);
        assertEquals(1.0 / 3.0, scored.snapshot.rating.unratedRate(), 1e-9);
    }

    /**
     * ⑥ 样本 &lt; 10 走「数据不足」：9 种不给折扣，10 种才给。
     *
     * <p>折扣一旦在 9 种样本上出数，用户看到的「平均 6 折」其实由一两本套装撑起来。
     */
    @Test
    public void 样本不足十种不给折扣结论() {
        StatsComputer.Result nine = compute(ten(9), Collections.emptyList(), Collections.emptyList());
        assertEquals(9, nine.snapshot.hold.discountSample);
        assertEquals(-1.0, nine.snapshot.spend.discount, 1e-9);
        assertEquals("数据不足", nine.snapshot.spend.discountText());
        assertEquals(360.0, nine.snapshot.hold.saved, 1e-9);

        StatsComputer.Result ten = compute(ten(10), Collections.emptyList(), Collections.emptyList());
        assertEquals(10, ten.snapshot.hold.discountSample);
        assertEquals(0.6, ten.snapshot.spend.discount, 1e-9);
        assertEquals("6.0 折", ten.snapshot.spend.discountText());
    }

    /** n 本自购：定价 ¥100、实付 ¥60（省 ¥40/本） */
    private static List<Book> ten(int n) {
        List<Book> list = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            list.add(book(100 + i, Book.OWN_BUY, 100, 60, 1));
        }
        return list;
    }

    /**
     * ⑦ 种与册分别计数：标签写「册」的地方算的必须是物理册数。
     *
     * <p>书 1 手填 3 册、书 2 只填 1 册但录了 2 条逐本副本（实付 ¥45 / ¥50）
     * → 2 种 6 册；书 3 是借阅来源且已归还（电子书签），不进藏书但进列表行数。
     */
    @Test
    public void 种与册分别计数() {
        Book three = book(1, Book.OWN_BUY, 50, 30, 3);
        three.readStatus = Book.READ_DONE;
        Book withCopies = book(2, Book.OWN_BUY, 60, 40, 1);
        withCopies.readStatus = Book.READING;
        Book bookmark = book(3, Book.OWN_BORROW, 99, 99, 1);
        bookmark.stockStatus = Book.STOCK_BOOKMARK;

        List<BookCopy> copies = Arrays.asList(copy(2, 2, 45), copy(2, 3, 50));
        StatsComputer.Result r = compute(Arrays.asList(three, withCopies, bookmark),
                copies, Collections.emptyList());

        assertEquals(2, r.snapshot.hold.titles);
        assertEquals(6, r.snapshot.hold.copies);
        assertEquals(3, r.snapshot.hold.rows);
        assertEquals(2, r.snapshot.total);
        assertEquals(1, r.snapshot.hold.readDone);
        assertEquals(1, r.snapshot.hold.reading);
        assertEquals(0, r.snapshot.hold.unread);
        assertEquals(0.5, r.snapshot.hold.readRate(), 1e-9);
        // 逐本实付：书 1 三册各 ¥30，书 2 主记录 ¥40 + 副本 ¥45 + ¥50
        assertEquals(225.0, r.snapshot.hold.paid, 1e-9);
    }
}
