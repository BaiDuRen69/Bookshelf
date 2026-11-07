package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.Book;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 「下一本读什么」的加权与抽签（v5.8.19）：候选池、理由、同种子稳定、不同种子会换。
 */
public class NextPickTest {

    private static final int TODAY = 20261031;

    private Book unread(String title) {
        Book book = new Book();
        book.title = title;
        book.readStatus = Book.READ_NONE;
        book.stockStatus = Book.STOCK_IN;
        return book;
    }

    @Test
    public void 只有没读过且拿得到手的才进候选() {
        Book read = unread("已读的");
        read.readStatus = Book.READ_DONE;
        Book reading = unread("在读");
        reading.readStatus = Book.READING;
        Book lent = unread("借出去了");
        lent.stockStatus = Book.STOCK_LENT;
        Book lost = unread("遗失");
        lost.stockStatus = Book.STOCK_LOST;
        Book bookmark = unread("电子书签");
        bookmark.stockStatus = Book.STOCK_BOOKMARK;
        Book blank = unread("   ");
        Book ok = unread("可以读");

        List<NextPick.Pick> ranked = NextPick.rank(new ArrayList<>(java.util.Arrays.asList(
                read, reading, lent, lost, bookmark, blank, ok)), TODAY);
        assertEquals(1, ranked.size());
        assertEquals("可以读", ranked.get(0).book.title);
    }

    @Test
    public void 高分久未读短书排在前面并给出理由() {
        Book good = unread("候选甲");
        good.doubanRating = "9.2";
        good.purchaseDate = "2021-03-05";
        good.pages = 180;
        good.tags = "推理";
        Book meh = unread("候选乙");
        meh.doubanRating = "6.0";
        meh.pages = 900;

        // 自己读完的书带「推理」→ 同标签的候选有加分
        Book finished = unread("读完的推理");
        finished.readStatus = Book.READ_DONE;
        finished.tags = "推理";

        List<NextPick.Pick> ranked = NextPick.rank(
                new ArrayList<>(java.util.Arrays.asList(good, meh, finished)), TODAY);
        assertEquals("候选甲", ranked.get(0).book.title);
        String reasons = ranked.get(0).reasonLine();
        assertTrue(reasons, reasons.contains("豆瓣评分 9.2"));
        assertTrue(reasons, reasons.contains("个月没翻开"));
        assertTrue(reasons, reasons.contains("带「推理」"));
        assertTrue(reasons, reasons.contains("容易开个头"));
        assertTrue("分数要能拉开差距", ranked.get(0).score > ranked.get(1).score);
    }

    @Test
    public void 同一批候选同种子结果稳定不同种子会换() {
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            Book book = unread("书" + i);
            book.pages = 100 + i * 40;
            book.doubanRating = String.valueOf(7 + (i % 3));
            books.add(book);
        }
        List<NextPick.Pick> ranked = NextPick.rank(books, TODAY);
        assertEquals(12, ranked.size());
        assertEquals("同一种子要抽到同一本",
                NextPick.chooseFrom(ranked, 7L).book.title,
                NextPick.chooseFrom(ranked, 7L).book.title);

        Set<String> seen = new HashSet<>();
        // 注意：new Random(0)/Random(1)… 的首个 nextDouble() 几乎一样（小种子只动低位），
        // 所以种子要由一个随机源发出来，不能直接写 0..N
        java.util.Random seeds = new java.util.Random(2026);
        for (int i = 0; i < 60; i++) {
            seen.add(NextPick.chooseFrom(ranked, seeds.nextLong()).book.title);
        }
        assertTrue("换 60 次种子只见到 " + seen.size() + " 本，抽签没起作用", seen.size() > 1);
        assertTrue("不该跳出前 " + NextPick.POOL + " 名的候选池", seen.size() <= NextPick.POOL);
    }

    @Test
    public void 换一本不会再抽到当前这本() {
        List<Book> books = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            Book book = unread("样本" + i);
            book.id = 100 + i;                    // 排除是按 id 认的，夹具必须各不相同
            book.pages = 200;
            books.add(book);
        }
        List<NextPick.Pick> ranked = NextPick.rank(books, TODAY);
        ranked.get(0).score = 999;                 // 分数悬殊：不排除时几乎总抽到同一本
        final long topId = ranked.get(0).book.id;

        java.util.Random seeds = new java.util.Random(7);
        Set<String> others = new HashSet<>();
        for (int i = 0; i < 30; i++) {
            NextPick.Pick pick = NextPick.chooseFrom(ranked, seeds.nextLong(), topId);
            assertNotEquals("点了「换一本」还给出同一本", topId, pick.book.id);
            others.add(pick.book.title);
        }
        assertTrue("排除高分那本后应当在其余候选里换着抽，实测只有 " + others.size() + " 本",
                others.size() > 1);
        // 候选只剩一本：不能为了排除把自己排空
        List<NextPick.Pick> single = ranked.subList(0, 1);
        assertEquals("只剩一本时仍要给出结果",
                single.get(0).book.title,
                NextPick.chooseFrom(single, 3L, single.get(0).book.id).book.title);
    }

    @Test
    public void 没有待读的书时返回空() {
        Book done = unread("都读完了");
        done.readStatus = Book.READ_DONE;
        List<Book> books = new ArrayList<>();
        books.add(done);
        assertNull(NextPick.choose(books, TODAY, 1L));
        assertEquals(0, NextPick.rank(books, TODAY).size());
        assertNull(NextPick.choose(new ArrayList<>(), TODAY, 1L));
    }

    @Test
    public void 没有任何信号时也要有一句理由() {
        Book plain = unread("素书");
        List<NextPick.Pick> ranked = NextPick.rank(
                new ArrayList<>(java.util.List.of(plain)), TODAY);
        assertEquals(1, ranked.size());
        assertNotNull(ranked.get(0).reasonLine());
        assertTrue(ranked.get(0).reasonLine(), ranked.get(0).reasons.size() >= 1);
        assertTrue(ranked.get(0).reasonLine().contains("还没读过"));
    }

    @Test
    public void 精确轮按最后一次打卡算模糊轮仍按到手时间() {
        // 同一本「未读」候选：购入很久，但最后一轮精确且上周刚打过卡 → 不再加「久未翻」的分
        Book precise = unread("刚打过卡");
        precise.id = 7;
        precise.purchaseDate = "2021-03-05";
        ReadingRecords.Record r = new ReadingRecords.Record(1);
        r.status = Book.READ_NONE;
        r.mode = ReadingRecords.MODE_PRECISE;
        r.startDate = "2026-10-20";
        precise.readingRecords = ReadingRecords.toJson(
                new ArrayList<>(java.util.List.of(r)));
        java.util.Map<Integer, List<Integer>> bySeq = new java.util.HashMap<>();
        bySeq.put(1, java.util.Arrays.asList(20261025, 20261026));
        java.util.Map<Long, java.util.Map<Integer, List<Integer>>> preciseByBook =
                new java.util.HashMap<>();
        preciseByBook.put(7L, bySeq);

        NextPick.Pick withCheckIn = NextPick.rank(
                new ArrayList<>(java.util.List.of(precise)), TODAY, preciseByBook).get(0);
        assertTrue(withCheckIn.reasonLine(), !withCheckIn.reasonLine().contains("个月没翻开"));
        assertEquals("距最后打卡只有 5 天，不该拿久未翻的 30 分", 0.0, withCheckIn.score, 0.001);

        // 同一个调用点不传日集合（模糊轮/老入口）：沿用购入时间，仍拿久未翻的分
        NextPick.Pick without = NextPick.rank(
                new ArrayList<>(java.util.List.of(precise)), TODAY).get(0);
        assertTrue(without.reasonLine(), without.reasonLine().contains("个月没翻开"));
        assertEquals(30.0, without.score, 0.001);
    }
}
