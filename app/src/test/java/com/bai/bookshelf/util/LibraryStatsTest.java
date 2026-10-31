package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BookCopy;
import com.bai.bookshelf.data.entity.BorrowRecord;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 统计口径单测（v5.8）：把「种 vs 册、花费只算自购、中位数、小样本、同期」
 * 这几条一次定死的规则钉住，防止以后又回到「按行计数却写着册」。
 */
public class LibraryStatsTest {

    private static Book book(long id, String title) {
        Book b = new Book();
        b.id = id;
        b.title = title;
        b.author = "某作者";
        b.publisher = "某出版社";
        b.category = "小说";
        b.isbn = "978750000000" + (id % 10);
        b.coverUrl = "/files/covers/cover_" + id + ".jpg";
        b.locationId = 1L;
        b.tags = "常读";
        b.rating = 4;
        return b;
    }

    private static BookCopy copy(long bookId, int index, int stockStatus, double paid) {
        BookCopy c = new BookCopy();
        c.bookId = bookId;
        c.copyIndex = index;
        c.stockStatus = stockStatus;
        c.purchasePrice = paid;
        c.ownType = Book.OWN_BUY;
        return c;
    }

    private static double round(double v) {
        return Math.round(v * 1000.0) / 1000.0;
    }

    // ==================== 种 / 册 ====================

    @Test
    public void 种与册分开计且花费按册() {
        Book a = book(1, "一本三册");
        a.quantity = 3;
        a.listPrice = 50;
        a.purchasePrice = 30;
        a.readStatus = Book.READ_DONE;

        Book gift = book(2, "别人送的");
        gift.ownType = Book.OWN_GIFT;
        gift.listPrice = 40;

        Book bookmark = book(3, "借来读完还了");
        bookmark.ownType = Book.OWN_BORROW;
        bookmark.stockStatus = Book.STOCK_BOOKMARK;

        Book lent = book(4, "借给同学的");
        lent.stockStatus = Book.STOCK_LENT;

        Book two = book(5, "两册套装");
        two.quantity = 2;
        two.listPrice = 25;
        two.purchasePrice = 10;

        List<Book> books = Arrays.asList(a, gift, bookmark, lent, two);
        Map<Long, List<BookCopy>> copies = LibraryStats.groupCopies(
                Arrays.asList(copy(5, 2, Book.STOCK_LOST, 20)));
        LibraryStats.Holdings h = LibraryStats.holdings(books, copies);

        assertEquals(5, h.rows);
        assertEquals("已归还的借阅不算在架种数", 4, h.titles);
        assertEquals("按物理册数：3+1+1+2", 7, h.copies);
        assertEquals(1, h.copiesLent);
        assertEquals("逐本副本里丢的那一册", 1, h.copiesLost);
        assertEquals(1, h.readDone);
        assertEquals(3, h.unread);
        assertEquals(0.25, h.readRate(), 1e-9);
        assertEquals("自购按册求和：3×30 + 10 + 20", 120.0, h.paid, 1e-9);
        assertEquals(200.0, h.listValue, 1e-9);
        assertEquals("赠书只算价值不算花费", 40.0, h.giftValue, 1e-9);
        assertEquals(2, h.discountSample);
        assertEquals("省下：3×20 + 15 + 5", 80.0, h.saved, 1e-9);
    }

    @Test
    public void 册数取手填数量与副本表的较大值() {
        Book b = book(1, "录了三册但只填了两本副本");
        b.quantity = 3;
        Map<Long, List<BookCopy>> copies = LibraryStats.groupCopies(
                Arrays.asList(copy(1, 2, Book.STOCK_IN, 0), copy(1, 3, Book.STOCK_IN, 0)));
        assertEquals(3, LibraryStats.copiesOf(b, copies.get(1L)));

        Book under = book(2, "有副本但数量忘了改");
        under.quantity = 1;
        Map<Long, List<BookCopy>> two = LibraryStats.groupCopies(
                Arrays.asList(copy(2, 2, Book.STOCK_IN, 0)));
        assertEquals(2, LibraryStats.copiesOf(under, two.get(2L)));
    }

    // ==================== 中位数与样本量 ====================

    @Test
    public void 中位数与数据不足判定() {
        assertEquals(2.0, LibraryStats.median(new double[]{1, 3, 2}), 1e-9);
        assertEquals(2.5, LibraryStats.median(new double[]{1, 2, 3, 4}), 1e-9);
        assertEquals(-1, LibraryStats.median(new double[0]), 1e-9);
        assertEquals(-1, LibraryStats.median(null), 1e-9);
        assertFalse(LibraryStats.enough(LibraryStats.MIN_SAMPLE - 1));
        assertTrue(LibraryStats.enough(LibraryStats.MIN_SAMPLE));
    }

    @Test
    public void 样本不足时折扣与单位成本不给结论() {
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            Book b = book(i + 1, "书" + i);
            b.listPrice = 40;
            b.purchasePrice = 20;
            b.pages = 200;
            books.add(b);
        }
        LibraryStats.Holdings h = LibraryStats.holdings(books, null);
        LibraryStats.Spend s = LibraryStats.spend(h);
        assertEquals(-1, s.discount, 1e-9);
        assertEquals(-1, s.perPage, 1e-9);
        assertEquals("数据不足", s.discountText());

        Book extra = book(99, "凑够第十本");
        extra.listPrice = 40;
        extra.purchasePrice = 20;
        extra.pages = 200;
        books.add(extra);
        LibraryStats.Spend ok = LibraryStats.spend(LibraryStats.holdings(books, null));
        assertEquals(0.5, ok.discount, 1e-9);
        assertEquals("5.0 折", ok.discountText());
        assertEquals(0.1, ok.perPage, 1e-9);
    }

    // ==================== 阅读速度与书债 ====================

    @Test
    public void 阅读速度用中位数且缺日期不计入() {
        Book p = book(1, "甲");
        p.readStatus = Book.READ_DONE;
        p.readingStartDate = "2026-01-01";
        p.readingFinishDate = "2026-01-11";
        p.pages = 220;

        Book q = book(2, "乙");
        q.readStatus = Book.READ_DONE;
        q.readingStartDate = "2026-02-01";
        q.readingFinishDate = "2026-02-05";
        q.pages = 100;

        Book r = book(3, "丙");
        r.readStatus = Book.READ_DONE;
        r.readingStartDate = "2026-03-01";
        r.readingFinishDate = "2026-03-09";
        r.pages = 360;

        Book noDates = book(4, "丁");
        noDates.pages = 600;
        noDates.purchasePrice = 5;

        Book onlyFinish = book(5, "戊");
        onlyFinish.readStatus = Book.READ_DONE;
        onlyFinish.readingFinishDate = "2026-04-01"; // 没有开始日期 ⇒ 不参与速度

        List<Book> books = Arrays.asList(p, q, r, noDates, onlyFinish);
        LibraryStats.Holdings h = LibraryStats.holdings(books, null);
        LibraryStats.Pace pace = LibraryStats.pace(books, null, h, 24);

        assertEquals(3, pace.daySample);
        assertEquals(3, pace.speedSample);
        assertEquals(9.0, pace.medianDays, 1e-9);
        assertEquals(20.0, pace.medianPagesPerDay, 1e-9);
        assertEquals(1, pace.unreadTitles);
        assertEquals(600, pace.unreadPages);
        assertEquals(5.0, pace.unreadPaid, 1e-9);
        // 20 页/天 × 每月 24/12=2 天 = 40 页/月 ⇒ 600 页要 15 个月
        assertEquals(15.0, pace.monthsToClear, 1e-6);
        assertEquals("4 本已读 / 5 种在架", 0.8, pace.finishRate, 1e-9);
        assertEquals(1.0, pace.activeFinishRate, 1e-9);
    }

    @Test
    public void 没有阅读日历就不硬估清完时间() {
        Book p = book(1, "甲");
        p.readStatus = Book.READ_DONE;
        p.readingStartDate = "2026-01-01";
        p.readingFinishDate = "2026-01-11";
        p.pages = 220;
        Book waiting = book(2, "还没读");
        waiting.pages = 300;
        List<Book> books = Arrays.asList(p, waiting);
        LibraryStats.Pace pace = LibraryStats.pace(
                books, null, LibraryStats.holdings(books, null), 0);
        assertEquals(-1.0, pace.monthsToClear, 1e-9);
        assertTrue(pace.unknownReason.contains("阅读日历"));
    }

    // ==================== 借阅 ====================

    private static BorrowRecord loan(long id, long bookId, String lend, String due,
                                     String ret, int status) {
        BorrowRecord r = new BorrowRecord();
        r.id = id;
        r.bookId = bookId;
        r.borrower = "同学";
        r.lendDate = lend;
        r.dueDate = due;
        r.returnDate = ret;
        r.status = status;
        return r;
    }

    @Test
    public void 按时归还率只算填了应还日期的归还() {
        List<BorrowRecord> list = new ArrayList<>();
        for (int i = 0; i < 8; i++) { // 8 笔按时
            list.add(loan(i + 1, 100 + i, "2026-01-0" + (i % 8 + 1),
                    "2026-03-01", "2026-02-15", BorrowRecord.STATUS_RETURNED));
        }
        for (int i = 0; i < 2; i++) { // 2 笔逾期归还
            list.add(loan(20 + i, 200 + i, "2026-01-01",
                    "2026-03-01", "2026-03-20", BorrowRecord.STATUS_RETURNED));
        }
        list.add(loan(30, 300, "2026-01-01", "", "2026-02-01", BorrowRecord.STATUS_RETURNED));
        list.add(loan(40, 400, "2026-09-01", "2026-10-01", "", BorrowRecord.STATUS_OUT));
        list.add(loan(41, 401, "2026-06-01", "2026-08-01", "", BorrowRecord.STATUS_OUT));

        LibraryStats.Lend l = LibraryStats.lend(list, 20261030);
        assertEquals(11, l.returned);
        assertEquals("没填应还日期的那笔不进样本", 10, l.onTimeSample);
        assertEquals(0.8, l.onTimeRate, 1e-9);
        assertEquals(2, l.out);
        assertEquals(2, l.overdue);
        assertTrue(l.avgOverdueDays > 0);
    }

    @Test
    public void 归还样本不足时不给归还率() {
        List<BorrowRecord> list = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            list.add(loan(i + 1, i, "2026-01-01", "2026-03-01",
                    "2026-02-01", BorrowRecord.STATUS_RETURNED));
        }
        LibraryStats.Lend l = LibraryStats.lend(list, 20261030);
        assertEquals(3, l.onTimeSample);
        assertEquals(-1.0, l.onTimeRate, 1e-9);
    }

    // ==================== 书房健康分 ====================

    @Test
    public void 健康分按权重加权并指出最该补的一项() {
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            Book b = book(i + 1, "书" + i);
            if (i >= 5) {
                b.rating = 0; // 5 本没评分
            }
            if (i >= 8) {
                b.tags = ""; // 2 本没标签
            }
            books.add(b);
        }
        LibraryStats.Health h = LibraryStats.health(books);
        assertEquals(10, h.total);
        assertEquals("20+20+15+10+10+10 + 评分 10×0.5 + 标签 5×0.8", 94, h.score);
        assertEquals("评分", h.weakest);
        assertEquals(5, h.weakestMissing);
    }

    @Test
    public void 空书架健康分为负而不是零() {
        LibraryStats.Health h = LibraryStats.health(new ArrayList<>());
        assertEquals(-1, h.score);
        assertEquals(0, h.total);
    }

    // ==================== 分组与分类树 ====================

    private static com.bai.bookshelf.data.entity.Category cat(long id, String name, Long parent) {
        com.bai.bookshelf.data.entity.Category c = new com.bai.bookshelf.data.entity.Category();
        c.id = id;
        c.name = name;
        c.parentId = parent;
        return c;
    }

    private static LibraryStats.Group find(List<LibraryStats.Group> list, String name) {
        for (LibraryStats.Group g : list) {
            if (g.name.equals(name)) {
                return g;
            }
        }
        return null;
    }

    @Test
    public void 二级分类挂回一级栏目且删掉的分类不丢书() {
        Book cn1 = book(1, "活着");
        cn1.category = "中国文学";
        cn1.quantity = 2;
        Book foreign = book(2, "局外人");
        foreign.category = "外国文学";
        Book top = book(3, "文学通史");
        top.category = "文学";
        Book none = book(4, "没分类的");
        none.category = "";
        Book stale = book(5, "分类被删了");
        stale.category = "已删掉的分类";

        List<LibraryStats.Group> tree = LibraryStats.byCategory(
                Arrays.asList(cn1, foreign, top, none, stale), null,
                Arrays.asList(cat(1, "文学", null), cat(2, "中国文学", 1L), cat(3, "外国文学", 1L)),
                "未分类");

        LibraryStats.Group wen = find(tree, "文学");
        assertEquals(3, wen.titles);
        assertEquals("中国文学那一本的 2 册要带上来", 4, wen.copies);
        assertEquals(2, wen.children.size());
        assertEquals("中国文学", wen.children.get(0).name);
        assertEquals(2, wen.children.get(0).copies);
        assertEquals("外国文学", wen.children.get(1).name);
        assertEquals(1, find(tree, "未分类").titles);
        assertEquals("分类表里查不到的名字按一级处理，书不能凭空消失",
                1, find(tree, "已删掉的分类").titles);
    }

    @Test
    public void 花费分组排除借阅但结构分组保留() {
        Book bought = book(1, "买的");
        bought.publisher = "花城出版社";
        bought.listPrice = 30;
        bought.purchasePrice = 15;
        Book gift = book(2, "送的");
        gift.publisher = "花城出版社";
        gift.ownType = Book.OWN_GIFT;
        gift.listPrice = 20;
        Book borrow = book(3, "借的");
        borrow.publisher = "中华书局";
        borrow.ownType = Book.OWN_BORROW;
        borrow.listPrice = 50;

        List<Book> books = Arrays.asList(bought, gift, borrow);
        List<LibraryStats.Group> structure = LibraryStats.groupBy(books, null,
                b -> b.publisher, "未填出版社", true);
        assertEquals(2, structure.size());
        assertEquals("结构口径下借阅也算一本", 2, find(structure, "花城出版社").titles);

        List<LibraryStats.Group> money = LibraryStats.groupBy(books, null,
                b -> b.publisher, "未填出版社", false);
        assertEquals(1, money.size());
        assertEquals("花费口径只算自购", 15.0, money.get(0).paid, 1e-9);
        assertEquals(0.5, money.get(0).discount(), 1e-9);
    }

    // ==================== 购买 → 阅读 cohort ====================

    @Test
    public void cohort按买入年份分组且窗口未关闭要标出来() {
        Book fast = book(1, "很快读完");
        fast.purchaseDate = "2023-01-01";
        fast.readStatus = Book.READ_DONE;
        fast.readingFinishDate = "2023-03-01";

        Book slow = book(2, "拖了两年半");
        slow.purchaseDate = "2023-01-01";
        slow.readStatus = Book.READ_DONE;
        slow.readingFinishDate = "2025-06-01";

        Book unread = book(3, "还没读");
        unread.purchaseDate = "2023-05-01";

        Book thisYear = book(4, "今年买的");
        thisYear.purchaseDate = "2026-02-01";
        thisYear.readStatus = Book.READ_DONE;
        thisYear.readingFinishDate = "2026-03-01";

        Book borrowed = book(5, "借来的不算买入");
        borrowed.purchaseDate = "2023-06-01";
        borrowed.ownType = Book.OWN_BORROW;

        List<LibraryStats.Cohort> cohorts = LibraryStats.cohort(
                Arrays.asList(fast, slow, unread, thisYear, borrowed), 20261030);

        assertEquals(2, cohorts.size());
        assertEquals("近 → 远", 2026, cohorts.get(0).year);
        LibraryStats.Cohort c23 = cohorts.get(1);
        assertEquals(2023, c23.year);
        assertEquals(3, c23.bought);
        assertEquals(2, c23.finished);
        assertEquals(1, c23.readIn1y);
        assertEquals(2, c23.readIn3y);
        assertTrue(c23.medianGapDays > 400);
        assertTrue(c23.windowClosed(3, 2026));
        assertFalse("2026 年谈不上三年内读完", cohorts.get(0).windowClosed(3, 2026));
    }

    @Test
    public void cohort行文案不硬算未关闭窗口与不足样本() {
        LibraryStats.Cohort c = new LibraryStats.Cohort();
        c.year = 2023;
        c.bought = 12;
        c.readIn1y = 6;
        c.readIn3y = 9;
        c.readIn5y = 10;
        c.medianGapDays = 96;
        // 2023 年买入的书到 2026 年只满三年，五年窗口不给
        assertEquals("2023 年买入 12 种 · 1 年内读完 6（50%） · 3 年内 9（75%） · 中位 96 天读完",
                c.line(2026));
        assertEquals("五年窗口满的那一年才给五年比例",
                "2023 年买入 12 种 · 1 年内读完 6（50%） · 3 年内 9（75%） · 5 年内 10（83%）"
                        + " · 中位 96 天读完", c.line(2028));

        c.year = 2025;
        assertEquals("2025 年窗口还没满三年，不给三年比例",
                "2025 年买入 12 种 · 1 年内读完 6（50%） · 中位 96 天读完", c.line(2026));

        c.year = 2026;
        assertEquals("2026 年买入 12 种 · 中位 96 天读完", c.line(2026));

        c.bought = 4;
        assertEquals("2026 年买入 4 种 · 样本不足", c.line(2026));
    }

    // ==================== 目标预测 ====================

    @Test
    public void 目标预测按当前速度并给出每月需读() {
        LibraryStats.Goal g = LibraryStats.Goal.of(6, 24, 10);
        assertEquals(7.2, g.projected, 1e-9);
        assertEquals(9.0, g.needPerMonth, 1e-9);
        assertFalse(g.reachable);
        assertEquals("今年已读 6 本，目标 24 本；按当前速度全年约 7 本，完不成；"
                + "剩下 2 个月每月要读 9 本", g.line());

        assertTrue("已经达标就不该说明天来不及", LibraryStats.Goal.of(30, 24, 10).reachable);
        assertEquals("目标 24 本已完成（已读 30 本）", LibraryStats.Goal.of(30, 24, 10).line());
    }

    // ==================== 阅读习惯 ====================

    @Test
    public void 阅读习惯算连续天数与最佳月份() {
        java.util.List<Integer> days = Arrays.asList(20260101, 20260102, 20260103,
                20260315, 20260316);
        LibraryStats.Habit h = LibraryStats.Habit.of(days, 2026, 20260316);
        assertEquals(5, h.daysReadThisYear);
        assertEquals(3, h.longestStreak);
        assertEquals("从今天往回数", 2, h.currentStreak);
        assertEquals(1, h.bestMonth);
        assertEquals(3, h.bestMonthDays);
        assertNotNull(h.bestWeekdayName);

        LibraryStats.Habit empty = LibraryStats.Habit.of(new ArrayList<>(), 2026, 20260316);
        assertEquals(0, empty.daysReadThisYear);
        assertEquals(0, empty.longestStreak);
    }

    @Test
    public void 今天没读但昨天读了仍算连着() {
        LibraryStats.Habit h = LibraryStats.Habit.of(
                Arrays.asList(20260314, 20260315), 2026, 20260316);
        assertEquals("今天还没读不算断，否则每天都得先读一本才不断", 2, h.currentStreak);
        assertEquals(2, h.longestStreak);
    }

    @Test
    public void 习惯只算当年不被跨年区间撑爆() {
        // 一本「2024 年开始在读、至今没填结束日期」的书会铺出跨年的一整段日子；
        // 习惯指标必须按年截断，否则会出现「7 月读了 155 天」这种结论
        java.util.List<Integer> days = new ArrayList<>();
        for (int y = 2024; y <= 2026; y++) {
            for (int d = 1; d <= 31; d++) {
                days.add(y * 10000 + 700 + d); // 每年 7 月 1 日到 31 日
            }
        }
        LibraryStats.Habit h = LibraryStats.Habit.of(days, 2026, 20260731);
        assertEquals("当年七月最多 31 天", 31, h.daysReadThisYear);
        assertEquals(7, h.bestMonth);
        assertTrue(h.bestMonthDays <= 31);
        assertTrue(h.longestStreak <= 31);
    }

    // ==================== 买读比 ====================

    @Test
    public void 买读比与囤书指数() {
        LibraryStats.BuyRead b = LibraryStats.BuyRead.of(18, 9, 6, 19);
        assertEquals(2.0, b.ratio, 1e-9);
        assertEquals("2.0 : 1", b.ratioText());
        assertEquals(0.3158, b.hoardIndex, 1e-4);

        LibraryStats.BuyRead none = LibraryStats.BuyRead.of(5, 0, 5, 5);
        assertEquals(-1.0, none.ratio, 1e-9);
        assertEquals("今年还没读完一本", none.ratioText());
    }

    // ==================== 该处理的书 ====================

    @Test
    public void 在读挂太久与买了一年没拆封() {
        Book stuck = book(1, "挂了四个月");
        stuck.readStatus = Book.READING;
        stuck.readingStartDate = "2026-06-01";

        Book fresh = book(2, "刚开始读");
        fresh.readStatus = Book.READING;
        fresh.readingStartDate = "2026-10-20";

        Book dusty = book(3, "买来就吃灰");
        dusty.readStatus = Book.READ_NONE;
        dusty.purchaseDate = "2024-05-01";

        LibraryStats.Stale s = LibraryStats.stale(
                Arrays.asList(stuck, fresh, dusty), 20261030, 5);
        assertEquals(1, s.readingOver30);
        assertEquals(1, s.readingOver90);
        assertEquals(1, s.readingTooLong.size());
        assertEquals("挂了四个月", s.readingTooLong.get(0)[0]);
        assertEquals(1, s.boughtUnread.size());
        assertFalse(s.isEmpty());
    }

    // ==================== 数据体检 ====================

    @Test
    public void 体检抓出重复与脏数据() {
        Book x1 = book(1, "活着");
        x1.isbn = "9787506369";
        x1.purchasePrice = 30;
        Book x2 = book(2, "活着（另一本）");
        x2.isbn = "9787506369";
        x2.purchasePrice = 30;
        Book sameTitle = book(3, "活着");
        sameTitle.purchasePrice = 200;
        Book bad = book(4, "价格录成负数");
        bad.purchasePrice = -5;
        Book future = book(5, "购买日期在未来");
        future.purchaseDate = "2027-01-01";
        Book thin = book(6, "页数录错");
        thin.pages = 2;

        LibraryStats.Anomalies a = LibraryStats.anomalies(
                Arrays.asList(x1, x2, sameTitle, bad, future, thin), 20261030);
        assertEquals(1, a.duplicateIsbn.size());
        assertEquals("9787506369", a.duplicateIsbn.get(0));
        assertEquals("同名只报一次", 1, a.duplicateTitle.size());
        assertEquals(1, a.negativePrice);
        assertEquals(1, a.futureDate);
        assertEquals(1, a.oddPages);
        assertEquals("200 超过中位数 30 的两倍", 1, a.overpriced);
        assertTrue(a.total() >= 6);
    }

    // ==================== 评分分析 ====================

    @Test
    public void 评分分布与未评分比例() {
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Book b = book(i + 1, "好书" + i);
            b.ratingAvg = 4.5;
            books.add(b);
        }
        Book low = book(9, "不合口味");
        low.ratingAvg = 2.0;
        books.add(low);
        Book unrated = book(10, "没打分");
        unrated.rating = 0;
        unrated.ratingAvg = 0;
        books.add(unrated);

        LibraryStats.Rating r = LibraryStats.ratings(books);
        assertEquals(10, r.total);
        assertEquals(9, r.rated);
        assertEquals(38.0 / 9.0, r.average, 1e-9);
        assertEquals(8, r.buckets[5]);
        assertEquals(1, r.buckets[2]);
        assertEquals(0.1, r.unratedRate(), 1e-9);
    }

    // ==================== 借阅人可靠性 ====================

    @Test
    public void 借阅人按笔数排序并各算各的按时率() {
        BorrowRecord a1 = loan(1, 1, "2026-01-01", "2026-03-01", "2026-02-01",
                BorrowRecord.STATUS_RETURNED);
        BorrowRecord a2 = loan(2, 2, "2026-01-01", "2026-03-01", "2026-04-01",
                BorrowRecord.STATUS_RETURNED);
        BorrowRecord a3 = loan(3, 3, "2026-05-01", "2026-07-01", "", BorrowRecord.STATUS_OUT);
        BorrowRecord b1 = loan(4, 4, "2026-01-01", "2026-03-01", "2026-02-01",
                BorrowRecord.STATUS_RETURNED);
        a1.borrower = "张三";
        a2.borrower = "张三";
        a3.borrower = "张三";
        b1.borrower = "李四";

        List<LibraryStats.Lender> lenders = LibraryStats.lenders(
                Arrays.asList(a1, a2, a3, b1), 20261030);
        assertEquals(2, lenders.size());
        assertEquals("借得多的排前面", "张三", lenders.get(0).name);
        assertEquals(3, lenders.get(0).loans);
        assertEquals("逾期次数含现在还在逾期那本", 2, lenders.get(0).overdue);
        assertEquals(1, lenders.get(0).outNow);
        assertEquals("按时率只看还完且有应还日期的两笔", 0.5, lenders.get(0).onTimeRate(), 1e-9);
        assertEquals(1.0, lenders.get(1).onTimeRate(), 1e-9);
    }

    @Test
    public void 遗失按册计价给损耗率() {
        Book lost = book(1, "借出去丢了");
        lost.stockStatus = Book.STOCK_LOST;
        lost.quantity = 2;
        lost.purchasePrice = 25;
        lost.listPrice = 40;
        Map<Long, List<BookCopy>> copies = LibraryStats.groupCopies(
                Arrays.asList(copy(1, 2, Book.STOCK_LOST, 18)));
        LibraryStats.Holdings h = LibraryStats.holdings(Arrays.asList(lost), copies);
        assertEquals(2, h.copiesLost);
        assertEquals("两册各自的实付都要算进损耗", 43.0, h.lostValue, 1e-9);
    }

    // ==================== 同比 / 环比 ====================

    @Test
    public void 同比只截到去年同期而不是拿全年比半年() {
        Book thisYear = book(1, "今年买的");
        thisYear.purchaseDate = "2026-03-01";
        thisYear.purchasePrice = 20;
        thisYear.readStatus = Book.READ_DONE;
        thisYear.readingFinishDate = "2026-04-01";

        Book lastYear = book(2, "去年买的");
        lastYear.purchaseDate = "2025-03-01";
        lastYear.purchasePrice = 30;
        lastYear.readStatus = Book.READ_DONE;
        lastYear.readingFinishDate = "2025-04-01";

        Book lastDecember = book(3, "去年 12 月买的");
        lastDecember.purchaseDate = "2025-12-01";
        lastDecember.purchasePrice = 99;

        List<LibraryStats.PeriodDelta> ps = LibraryStats.periods(
                Arrays.asList(thisYear, lastYear, lastDecember), 20261031);
        LibraryStats.PeriodDelta y = ps.get(0);
        assertEquals(1, y.nowBought);
        assertEquals("去年 12 月不在同期范围内", 1, y.prevBought);
        assertEquals(1, y.nowRead);
        assertEquals(1, y.prevRead);
        assertEquals(20.0, y.nowSpent, 1e-9);
        assertEquals(30.0, y.prevSpent, 1e-9);
        assertEquals("今年 vs 去年同期：买 1 / 1 种 · 读 1 / 1 种 · 花 ¥20 / ¥30", y.line());
    }

    @Test
    public void 环比的上月同期遇短月截到月末() {
        Book sep = book(1, "九月买的");
        sep.purchaseDate = "2026-09-30";
        sep.purchasePrice = 10;
        Book oct = book(2, "十月买的");
        oct.purchaseDate = "2026-10-15";
        oct.purchasePrice = 20;
        List<LibraryStats.PeriodDelta> ps = LibraryStats.periods(
                Arrays.asList(sep, oct), 20261031);
        LibraryStats.PeriodDelta m = ps.get(1);
        assertEquals(1, m.nowBought);
        assertEquals(1, m.prevBought);
    }

    @Test
    public void 借还同比按借出日和归还日各自归期() {
        Book a = book(1, "今年借出去的书");
        Book b = book(2, "去年借出去的书");
        BorrowRecord thisYear = new BorrowRecord();
        thisYear.bookId = 1;
        thisYear.borrower = "小王";
        thisYear.lendDate = "2026-05-01";
        thisYear.returnDate = "2026-06-01";
        BorrowRecord old = new BorrowRecord();
        old.bookId = 2;
        old.borrower = "小李";
        old.lendDate = "2025-05-01";
        old.returnDate = "2025-06-01";
        // 去年九月借、今年二月才还：借出算去年，归还算今年
        BorrowRecord crossYear = new BorrowRecord();
        crossYear.bookId = 2;
        crossYear.borrower = "小张";
        crossYear.lendDate = "2025-09-01";
        crossYear.returnDate = "2026-02-01";
        List<LibraryStats.PeriodDelta> ps = LibraryStats.periods(
                Arrays.asList(a, b), Arrays.asList(thisYear, old, crossYear), 20261031);
        LibraryStats.PeriodDelta y = ps.get(0);
        assertEquals(1, y.nowLent);
        assertEquals(2, y.prevLent);
        assertEquals("去年借今年还的那笔算今年归还", 2, y.nowReturned);
        assertEquals(1, y.prevReturned);
        assertTrue(y.line().endsWith("· 借出 1 / 2 · 归还 2 / 1"));
        // 没有借出记录时不该冒出「借出 0 / 0」这半句
        assertFalse(LibraryStats.periods(Arrays.asList(a, b), 20261031)
                .get(0).line().contains("借出"));
    }

    // ==================== 读的结构 vs 藏的结构 ====================

    @Test
    public void 结构比较给同一分类的两个占比() {
        Book a = book(1, "活着");
        a.category = "文学";
        a.readStatus = Book.READ_DONE;
        Book b = book(2, "我们仨");
        b.category = "文学";
        Book c = book(3, "资治通鉴");
        c.category = "历史";
        List<LibraryStats.Share> shares = LibraryStats.structureCompare(
                Arrays.asList(a, b, c), null,
                Arrays.asList(cat(1, "文学", null), cat(2, "历史", null)));
        assertEquals(2, shares.size());
        LibraryStats.Share wen = shares.get(0);
        assertEquals("文学", wen.name);
        assertEquals(67, Math.round(wen.shelfShare * 100));
        assertEquals(100, Math.round(wen.readShare * 100));
        assertEquals("文学 藏书 67% → 已读 100%", wen.line());
    }

    // ==================== 花费趋势与预测 ====================

    private static Book bought(long id, String title, String date, double price) {
        Book b = book(id, title);
        b.purchaseDate = date;
        b.purchasePrice = price;
        b.ownType = Book.OWN_BUY;
        return b;
    }

    @Test
    public void 趋势按已过月份外推并算预算剩余() {
        List<Book> books = Arrays.asList(
                bought(1, "一月买的", "2026-01-05", 100),
                bought(2, "二月买的", "2026-02-05", 50),
                bought(3, "三月买的", "2026-03-05", 150));
        LibraryStats.Trend t = LibraryStats.trend(books, 2026, 20260331);
        assertEquals(3, t.months.size());
        assertEquals(3, t.monthsElapsed);
        assertEquals(300.0, t.yearSpent, 1e-9);
        assertEquals(3, t.yearBought);
        assertEquals(1200.0, t.projectedYear, 1e-9);
        assertEquals(100.0, t.threeMonthAvg, 1e-9);
        assertTrue(t.partial);
        assertEquals("全年预计 ¥1200（按前 3 个月外推，至今）", t.projectedText());
        assertEquals("预算还剩 ¥700", t.budgetLeft(1000));
        assertNull("没设预算就不显示这一行", t.budgetLeft(0));
    }

    @Test
    public void 趋势不把赠书与借阅算进花费() {
        Book gift = book(1, "别人送的");
        gift.purchaseDate = "2026-01-05";
        gift.listPrice = 80;
        gift.purchasePrice = 80;
        gift.ownType = Book.OWN_GIFT;
        LibraryStats.Trend t = LibraryStats.trend(Arrays.asList(gift), 2026, 20260331);
        assertEquals(0.0, t.yearSpent, 1e-9);
        assertEquals(0, t.yearBought);
    }

    // ==================== 相关性 ====================

    @Test
    public void 皮尔逊系数与样本门槛() {
        java.util.List<Double> x = Arrays.asList(1.0, 2.0, 3.0, 4.0);
        java.util.List<Double> y = Arrays.asList(2.0, 4.0, 6.0, 8.0);
        assertEquals(1.0, LibraryStats.pearson(x, y), 1e-9);
        assertEquals(-1.0, LibraryStats.pearson(x, Arrays.asList(8.0, 6.0, 4.0, 2.0)), 1e-9);
        assertEquals(0.0, LibraryStats.pearson(Arrays.asList(1.0), Arrays.asList(1.0)), 1e-9);
        assertEquals("某一侧没有波动时为 0", 0.0,
                LibraryStats.pearson(x, Arrays.asList(3.0, 3.0, 3.0, 3.0)), 1e-9);

        List<Book> books = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Book b = bought(i + 1, "书" + i, "2026-01-0" + (i % 8 + 1), 10 + i * 10);
            b.pages = 100 + i * 50;
            b.ratingAvg = 5 - i * 0.2;
            books.add(b);
        }
        LibraryStats.Relation r = LibraryStats.relations(books);
        assertEquals(12, r.pagesSample);
        assertEquals(12, r.ratingSample);
        assertTrue("页数越多越贵", r.priceVsPages > 0.99);
        assertTrue("这批假数据里越贵打分越低", r.priceVsRating < -0.9);
        assertTrue(r.line().contains("r="));
    }

    @Test
    public void 趋势与同比的今年花销必须一致() {
        // 一本三册的书：按册算 ¥60，按种算 ¥20。两张卡说的是同一句话，不能给两个数
        Book multi = bought(1, "套装三册", "2026-03-05", 20);
        multi.quantity = 3;
        List<Book> books = Arrays.asList(multi);
        LibraryStats.Trend t = LibraryStats.trend(books, 2026, 20261031);
        LibraryStats.PeriodDelta y = LibraryStats.periods(books, 20261031).get(0);
        assertEquals(60.0, t.yearSpent, 1e-9);
        assertEquals(t.yearSpent, y.nowSpent, 1e-9);
        assertEquals(1, y.nowBought);
    }

    // ==================== 摘抄密度 ====================

    @Test
    public void 摘抄与短评覆盖率() {
        Book a = book(1, "有摘抄");
        a.review = "写了一句";
        Book b = book(2, "也有摘抄");
        Book c = book(3, "什么都没有");
        List<com.bai.bookshelf.data.entity.Excerpt> excerpts = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            com.bai.bookshelf.data.entity.Excerpt e = new com.bai.bookshelf.data.entity.Excerpt();
            e.id = i + 1;
            e.bookId = i < 2 ? 1 : 2;
            e.content = "句子" + i;
            excerpts.add(e);
        }
        LibraryStats.NoteStat n = LibraryStats.notes(Arrays.asList(a, b, c), excerpts);
        assertEquals(3, n.total);
        assertEquals(2, n.withExcerpt);
        assertEquals(1, n.withReview);
        assertEquals(4, n.excerpts);
        assertEquals("摘抄覆盖 67%（2 种 · 共 4 条）· 短评覆盖 33%", n.line());
    }

    // ==================== 想读转化 ====================

    private static com.bai.bookshelf.data.entity.WishBook wish(long id, String title, String author,
                                                               String isbn, double price, long createdAt) {
        com.bai.bookshelf.data.entity.WishBook w = new com.bai.bookshelf.data.entity.WishBook();
        w.id = id;
        w.title = title;
        w.author = author;
        w.isbn = isbn;
        w.expectedPrice = price;
        w.createdAt = createdAt;
        w.priority = com.bai.bookshelf.data.entity.WishBook.PRIORITY_HIGH;
        return w;
    }

    @Test
    public void 想读转化按ISBN或书名作者匹配() {
        long t0 = 1_700_000_000_000L;
        long day = 86_400_000L;
        com.bai.bookshelf.data.entity.WishBook w1 = wish(1, "活着", "余华", "978X", 30, t0);
        com.bai.bookshelf.data.entity.WishBook w2 = wish(2, "我们仨", "杨绛", "", 20, t0);
        com.bai.bookshelf.data.entity.WishBook w3 = wish(3, "一直没买", "某人", "", 10, t0);

        Book b1 = book(1, "活着");
        b1.isbn = "978X";
        b1.createdAt = t0 + 10 * day;
        Book b2 = book(2, "我们仨");
        b2.author = "杨绛";
        b2.isbn = "";
        b2.createdAt = t0 + 20 * day;

        LibraryStats.WishStat s = LibraryStats.wishes(Arrays.asList(w1, w2, w3),
                Arrays.asList(b1, b2));
        assertEquals(3, s.wanted);
        assertEquals(60.0, s.mindPrice, 1e-9);
        assertEquals(3, s.highPriority);
        assertEquals(2, s.converted);
        assertEquals(15.0, s.medianDaysToBuy, 1e-9);
        assertEquals("想读 3 本 · 心理价合计 ¥60 · 高优先 3 本 · 已转藏书 2 本 · 中位 15 天后买",
                s.line());
    }

    // ==================== 借出结构与盘点 ====================

    @Test
    public void 借出按分类聚合并给出在架与损耗() {
        Book wen1 = book(1, "活着");
        wen1.category = "文学";
        wen1.readStatus = Book.READ_DONE;
        Book wen2 = book(2, "我们仨");
        wen2.category = "文学";
        Book his = book(3, "资治通鉴");
        his.category = "历史";
        List<BorrowRecord> records = Arrays.asList(
                loan(1, 1, "2026-01-01", "2026-03-01", "", BorrowRecord.STATUS_OUT),
                loan(2, 2, "2026-01-01", "2026-03-01", "", BorrowRecord.STATUS_OUT),
                loan(3, 3, "2026-01-01", "2026-03-01", "", BorrowRecord.STATUS_OUT));
        List<LibraryStats.Group> byCat = LibraryStats.lendStructure(
                Arrays.asList(wen1, wen2, his), records, b -> b.category, "未分类");
        assertEquals(2, byCat.size());
        assertEquals("文学", byCat.get(0).name);
        assertEquals(2, byCat.get(0).titles);
        assertEquals(1, byCat.get(0).readDone);

        LibraryStats.Holdings h = LibraryStats.holdings(
                Arrays.asList(wen1, wen2, his), null);
        assertEquals(3, h.copies);
        assertEquals(0, h.copiesLent);
        assertEquals(3, h.copiesInStore());
        assertEquals(0.0, h.lossRate(), 1e-9);
    }

    @Test
    public void 系列聚合复用分组并忽略首尾空格() {
        Book a = book(1, "第一部");
        a.seriesName = " 三体 ";
        a.readStatus = Book.READ_DONE;
        Book b = book(2, "第二部");
        b.seriesName = "三体";
        Book c = book(3, "单本");
        List<LibraryStats.Group> groups = LibraryStats.groupBy(
                Arrays.asList(a, b, c), null, book -> book.seriesName, "未成系列", true);

        assertEquals(2, groups.size());
        assertEquals("首尾空格不该把同一个系列拆成两组", "三体", groups.get(0).name);
        assertEquals(2, groups.get(0).titles);
        assertEquals(1, groups.get(0).readDone);
        assertEquals("没填系列的归到兜底名，渲染时再剔除", "未成系列", groups.get(1).name);
    }

    @Test
    public void 缺卷只在数字卷号之间判断() {
        Book v1 = book(1, "三体");
        v1.seriesName = "三体";
        v1.seriesVolume = "1";
        Book v3 = book(2, "死神斐然");
        v3.seriesName = "三体";
        v3.seriesVolume = "3";
        Book bonus = book(3, "外传");
        bonus.seriesName = "三体";
        bonus.seriesVolume = "外传";
        List<Book> books = Arrays.asList(v1, v3, bonus);

        assertEquals("1 与 3 之间缺第 2 卷", 1, LibraryStats.missingVolumes(books, "三体"));
        assertEquals("别的系列不算", 0, LibraryStats.missingVolumes(books, "基地"));
        Book onlyText = book(4, "卷号都是文字");
        onlyText.seriesName = "古典";
        onlyText.seriesVolume = "上册";
        assertEquals("卷号解析不出整数就不猜缺卷", 0,
                LibraryStats.missingVolumes(Arrays.asList(onlyText), "古典"));
    }

    // ==================== 同期口径 ====================

    @Test
    public void 同比必须用去年同期() {
        assertEquals(20251030, LibraryStats.samePeriodLastYearKey(20261030));
        assertTrue(LibraryStats.isPartialPeriod(2026, 10, 2026, 10));
        assertFalse(LibraryStats.isPartialPeriod(2026, 9, 2026, 10));
        assertFalse(LibraryStats.isPartialPeriod(2025, 10, 2026, 10));
    }
}
