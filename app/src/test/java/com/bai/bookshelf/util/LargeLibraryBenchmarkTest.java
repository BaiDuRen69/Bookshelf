package com.bai.bookshelf.util;

import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.data.entity.Excerpt;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 1000 本压测（v5.7 收口 #7）：把书架一次刷新真正会跑的纯计算路径各测一遍，
 * 给出可回归的时间预算。
 *
 * <p>说明：拼音转写走 {@code android.icu}，JVM 单测里 {@code Build.VERSION.SDK_INT} 为 0
 * 会被短路，所以本测试量到的是「不含 ICU 转写」的下界；真机上 haystack 还要多一次
 * 每本一次的转写，那一头靠把正则预编译等手法压住（见 {@link SearchText}）。
 */
public class LargeLibraryBenchmarkTest {

    private static final int N = 1000;
    /** 「一次刷新」总预算：清洗 + 建索引 + 智能计数 + 查询排序 */
    private static final long REFRESH_BUDGET_MS = 1200;

    private static final String[] AUTHORS = {"刘震云", "乔治·伽莫夫", "余华", "东野圭吾", "迟子建"};
    private static final String[] PUBS = {"花城出版社", "人民文学出版社", "天津人民出版社", "上海译文出版社"};

    private static List<Book> library() {
        List<Book> list = new ArrayList<>(N);
        for (int i = 0; i < N; i++) {
            Book b = new Book();
            b.id = i + 1;
            b.title = "藏书第" + (i + 1) + "本";
            b.author = AUTHORS[i % AUTHORS.length];
            b.publisher = PUBS[i % PUBS.length];
            b.category = i % 3 == 0 ? "小说" : "历史";
            b.tags = "学校,常读";
            b.isbn = "97875" + String.format("%08d", i);
            b.country = "中国";
            b.review = i % 7 == 0 ? "值得再读一遍" : "";
            b.purchaseDate = String.format("2026-%02d-%02d", i % 12 + 1, i % 28 + 1);
            b.readStatus = i % 4 == 0 ? Book.READING : (i % 3 == 0 ? Book.READ_DONE : Book.READ_NONE);
            b.stockStatus = i % 11 == 0 ? Book.STOCK_LENT : Book.STOCK_IN;
            b.ownType = i % 5 == 0 ? Book.OWN_BORROW : Book.OWN_BUY;
            b.sourceChannel = i % 5 == 0 ? "学校图书馆" : "多抓鱼";
            b.coverUrl = i % 6 == 0 ? "" : "/data/user/0/com.bai.bookshelf/files/covers/cover_" + i + ".jpg";
            b.locationId = i % 9 == 0 ? null : (long) (i % 2 + 1);
            b.rating = i % 5;
            b.ratingAvg = i % 5;
            b.listPrice = 30 + i % 40;
            b.purchasePrice = 18 + i % 30;
            b.pages = 180 + i % 400;
            b.wordCount = (20 + i % 20) + "万字";
            b.publishDate = "20" + (10 + i % 15) + "-" + (i % 12 + 1) + "|1";
            if (i % 3 == 0) {
                b.readingRecords = "[{\"seq\":1,\"startDate\":\"2026-0" + (i % 9 + 1) + "-03\","
                        + "\"endDate\":\"2026-0" + (i % 9 + 1) + "-21\",\"rating\":4.5},"
                        + "{\"seq\":2,\"startDate\":\"2026-0" + (i % 9 + 1) + "-25\","
                        + "\"endDate\":\"\",\"rating\":0}]";
            }
            if (i % 5 == 0) {
                b.borrowRecords = "[{\"seq\":1,\"source\":\"学校图书馆\",\"quantity\":1,"
                        + "\"startDate\":\"2026-03-01\",\"dueDate\":\"2026-04-01\",\"returnDate\":\"\"}]";
            }
            list.add(b);
        }
        return list;
    }

    private static Map<Long, BorrowRecord> openLoans(List<Book> books) {
        Map<Long, BorrowRecord> map = new HashMap<>();
        for (Book b : books) {
            if (b.stockStatus == Book.STOCK_LENT) {
                BorrowRecord r = new BorrowRecord();
                r.bookId = b.id;
                r.borrower = "同学";
                r.lendDate = "2026-09-01";
                r.dueDate = "2026-09-20";
                r.status = BorrowRecord.STATUS_OUT;
                map.put(b.id, r);
            }
        }
        return map;
    }

    private static List<Excerpt> excerpts() {
        List<Excerpt> list = new ArrayList<>();
        for (int i = 0; i < N; i += 2) { // 一半的书有摘抄
            Excerpt e = new Excerpt();
            e.id = i + 1;
            e.bookId = i + 1;
            e.content = "那是" + (i % 9 + 1) + "月里的一个晴天，我读到这里停了一下，"
                    + "后来反复想起这句话，因为它说的是生活本身而不是别的什么。";
            e.note = "写得真好，回头再抄一遍给家里人看";
            e.tags = "金句,反复读";
            list.add(e);
        }
        return list;
    }

    /** 计时：先跑一次预热 JIT，再取 runs 次里的最好值（受 GC 抖动影响最小） */
    private static long time(String label, int runs, Runnable body) {
        body.run();
        long best = Long.MAX_VALUE;
        for (int i = 0; i < runs; i++) {
            long t0 = System.nanoTime();
            body.run();
            best = Math.min(best, (System.nanoTime() - t0) / 1_000_000);
        }
        System.out.println("[bench] " + label + " = " + best + " ms");
        return best;
    }

    @Test
    public void 一千本一次刷新在预算内() {
        List<Book> books = library();
        Map<Long, BorrowRecord> loans = openLoans(books);
        List<Excerpt> excerpts = excerpts();
        int todayKey = 20261030;

        long clean = time("normalizeAll(1000)", 5, () -> BookSanitizer.normalizeAll(library()));
        long index = time("SearchIndex.build(1000+500)", 3,
                () -> SearchIndex.build(books, excerpts));
        long counts = time("SmartShelf.counts(1000)", 5,
                () -> SmartShelf.counts(books, loans, todayKey));
        Map<Long, String> haystack = SearchIndex.build(books, excerpts);
        com.bai.bookshelf.ui.shelf.ShelfQuery.Criteria c =
                new com.bai.bookshelf.ui.shelf.ShelfQuery.Criteria();
        c.sortType = 1;
        long query = time("ShelfQuery.query(关键词+排序)", 5,
                () -> com.bai.bookshelf.ui.shelf.ShelfQuery.query(books, c, "藏书第1",
                        haystack, loans, todayKey));
        long calendar = time("ReadingCalendar.titlesByDay(1000)", 3,
                () -> ReadingCalendar.titlesByDay(books));
        long records = time("ReadingRecords.load(1000)", 3, () -> {
            for (Book b : books) {
                ReadingRecords.load(b);
            }
        });

        long refresh = clean + index + counts + query;
        System.out.println("[bench] 一次刷新合计（清洗+索引+计数+查询）= " + refresh + " ms");
        assertTrue("一次刷新 " + refresh + " ms 超出预算 " + REFRESH_BUDGET_MS,
                refresh < REFRESH_BUDGET_MS);
        assertTrue("阅读日历聚合过慢", calendar < 600);
        assertTrue("阅读记录解析过慢", records < 600);
    }

    /** 逐本清洗不能变成二次方：规模翻倍耗时应大致翻倍 */
    @Test
    public void 清洗随规模线性增长() {
        List<Book> half = library().subList(0, N / 2);
        List<Book> all = library();
        long t500 = time("normalizeAll(500)", 5, () -> BookSanitizer.normalizeAll(new ArrayList<>(half)));
        long t1000 = time("normalizeAll(1000)", 5, () -> BookSanitizer.normalizeAll(new ArrayList<>(all)));
        assertTrue(t500 + " → " + t1000 + "：规模翻倍耗时超 4 倍，疑似二次方",
                t1000 < Math.max(4 * t500, 40));
    }
}
