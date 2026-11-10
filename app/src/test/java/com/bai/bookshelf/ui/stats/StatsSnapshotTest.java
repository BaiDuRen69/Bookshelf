package com.bai.bookshelf.ui.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.BorrowRecord;
import com.bai.bookshelf.util.NextPick;
import com.bai.bookshelf.util.ReadingModeHealth;
import com.bai.bookshelf.util.StatsHealth;
import com.bai.bookshelf.util.StatsHoldings;
import com.bai.bookshelf.util.StatsLend;
import com.bai.bookshelf.util.StatsPace;
import com.bai.bookshelf.util.StatsSpend;

import org.junit.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 3.1 快照契约：字段全 final、不碰 Android、Builder 一个不漏、toBuilder 一个不丢。
 * 四栏「关键数字」的断言在 3.2 的 StatsComputerTest 里，这里只钉容器本身。
 */
public class StatsSnapshotTest {

    private static final int TODAY = 20261110;

    private static List<Field> instanceFields() {
        List<Field> out = new ArrayList<>();
        for (Field f : StatsSnapshot.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers())) {
                out.add(f);
            }
        }
        return out;
    }

    private static Book book(long id, String title) {
        Book b = new Book();
        b.id = id;
        b.title = title;
        b.author = "某作者";
        b.pages = 200;
        return b;
    }

    private static StatsHoldings.Group group(String name) {
        StatsHoldings.Group g = new StatsHoldings.Group(name);
        g.titles = 2;
        return g;
    }

    /** List 字段按字段名给样本：泛型擦除后类型分不出元素。 */
    private static Object sampleList(String name) {
        Book one = book(2L, "样本二");
        switch (name) {
            case "categoryTree":
            case "seriesGroups":
            case "channels":
            case "costByCategory":
            case "lendByCategory":
                return Collections.singletonList(group("历史"));
            case "cohorts":
                return Collections.singletonList(new StatsHoldings.Cohort());
            case "structure":
                return Collections.singletonList(new StatsHoldings.Share());
            case "periods":
                return Collections.singletonList(new StatsSpend.PeriodDelta());
            case "lenders":
                return StatsLend.lenders(Collections.<BorrowRecord>emptyList(), TODAY);
            case "nextRanked":
                return NextPick.rank(Collections.singletonList(one), TODAY);
            case "openLoans": {
                BorrowRecord r = new BorrowRecord();
                r.bookId = one.id;
                r.borrower = "同学甲";
                r.dueDate = "2026-11-01";
                return OpenLoan.build(Collections.singletonList(one),
                        Collections.singletonList(r), TODAY);
            }
            case "books":
            case "recent":
                return new ArrayList<>(Collections.singletonList(one));
            case "topAuthors":
            case "topPublishers":
            case "topCountries":
                return new ArrayList<>(Collections.singletonList(
                        (Map.Entry<String, Integer>) new AbstractMap.SimpleEntry<>("某作者", 3)));
            default:
                throw new IllegalStateException("List 字段没有样本值，请补 sampleList：" + name);
        }
    }

    /** 每个字段都给一个「非默认值」，否则 toBuilder 漏抄也测不出来。 */
    private static Object sampleValue(Class<?> type) {
        if (type == int.class) {
            return 7;
        }
        if (type == double.class) {
            return 1.5d;
        }
        if (type == boolean.class) {
            return Boolean.TRUE;
        }
        if (type == String.class) {
            return "样本";
        }
        if (type == Book.class) {
            return book(1L, "样本一");
        }
        if (type == StatsHoldings.Holdings.class) {
            StatsHoldings.Holdings v = new StatsHoldings.Holdings();
            v.titles = 3;
            return v;
        }
        if (type == StatsHoldings.Group.class) {
            return group("文学");
        }
        if (type == StatsPace.Pace.class) {
            return new StatsPace.Pace();
        }
        if (type == StatsPace.Backlog.class) {
            return new StatsPace.Backlog();
        }
        if (type == StatsPace.Habit.class) {
            return new StatsPace.Habit();
        }
        if (type == StatsPace.Stale.class) {
            return new StatsPace.Stale();
        }
        if (type == StatsPace.BuyRead.class) {
            return new StatsPace.BuyRead();
        }
        if (type == StatsPace.DropRecovery.class) {
            return new StatsPace.DropRecovery();
        }
        if (type == ReadingModeHealth.Report.class) {
            return new ReadingModeHealth.Report();
        }
        if (type == StatsSpend.Spend.class) {
            return new StatsSpend.Spend();
        }
        if (type == StatsSpend.Relation.class) {
            return new StatsSpend.Relation();
        }
        if (type == StatsSpend.Trend.class) {
            return new StatsSpend.Trend();
        }
        if (type == StatsHealth.Health.class) {
            return new StatsHealth.Health();
        }
        if (type == StatsHealth.Rating.class) {
            return new StatsHealth.Rating();
        }
        if (type == StatsHealth.Anomalies.class) {
            return new StatsHealth.Anomalies();
        }
        if (type == StatsHealth.BorrowOrphans.class) {
            return new StatsHealth.BorrowOrphans();
        }
        if (type == StatsHealth.NoteStat.class) {
            return new StatsHealth.NoteStat();
        }
        if (type == StatsHealth.WishStat.class) {
            return new StatsHealth.WishStat();
        }
        if (type == StatsLend.Lend.class) {
            return new StatsLend.Lend();
        }
        if (type == ReturnTrend.Series.class) {
            return ReturnTrend.of(Collections.<BorrowRecord>emptyList(), TODAY, ReturnTrend.MONTHS);
        }
        throw new IllegalStateException("新字段没有样本值，请补 sampleValue：" + type.getName());
    }

    private static StatsSnapshot fullyPopulated() throws Exception {
        StatsSnapshot.Builder builder = StatsSnapshot.builder();
        for (Field f : instanceFields()) {
            Object v = f.getType() == List.class ? sampleList(f.getName()) : sampleValue(f.getType());
            StatsSnapshot.Builder.class.getMethod(f.getName(), f.getType()).invoke(builder, v);
        }
        return builder.build();
    }

    @Test
    public void 所有实例字段都是publicFinal() {
        for (Field f : instanceFields()) {
            assertTrue("字段必须 final：" + f.getName(), Modifier.isFinal(f.getModifiers()));
            assertTrue("渲染层要能直读，字段必须 public：" + f.getName(), Modifier.isPublic(f.getModifiers()));
        }
    }

    @Test
    public void 快照不依赖Android类型() {
        for (Field f : instanceFields()) {
            String t = f.getType().getName();
            assertTrue("快照只装纯数据，出现 " + t + " 说明又把界面对象带进来了",
                    !t.startsWith("android.") && !t.startsWith("androidx."));
        }
    }

    @Test
    public void 每个字段都有同名Builder装配方法() throws Exception {
        for (Field f : instanceFields()) {
            try {
                Method m = StatsSnapshot.Builder.class.getMethod(f.getName(), f.getType());
                assertEquals("装配方法要链式返回 Builder", StatsSnapshot.Builder.class, m.getReturnType());
            } catch (NoSuchMethodException e) {
                fail("字段 " + f.getName() + " 缺 Builder." + f.getName()
                        + "(" + f.getType().getSimpleName() + ")");
            }
        }
    }

    @Test
    public void empty的默认值就是改造前Fragment的字段初值() {
        StatsSnapshot s = StatsSnapshot.empty();
        assertEquals(0, s.total);
        assertEquals(0, s.finishedThisYear);
        assertEquals(0, s.avgDays);
        assertEquals(0d, s.totalPrice, 0d);
        assertEquals(0, s.lendDateUnparsable);
        // 阅读栏用 -1 表示「还没算出来」，0 是已知值，两者不能混
        assertEquals(-1, s.totalReadingDays);
        assertEquals("", s.checkInLine);
        assertFalse(s.showCheckInLine);
        assertTrue(s.nextRanked.isEmpty());
        assertNull(s.hold);
        assertNull(s.spend);
        assertNull(s.pace);
        assertNull(s.lend);
        assertNull(s.books);
        assertNull(s.mostExpensive);
    }

    @Test
    public void 装配后逐字段取回同一个对象() {
        StatsHoldings.Holdings hold = new StatsHoldings.Holdings();
        hold.titles = 55;
        hold.copies = 61;
        Book expensive = book(9L, "最贵的一本");
        List<Map.Entry<String, Integer>> authors = new ArrayList<>();
        authors.add(new AbstractMap.SimpleEntry<>("测试", 28));
        StatsSnapshot s = StatsSnapshot.builder().hold(hold).total(55).mostExpensive(expensive)
                .topAuthors(authors).totalReadingDays(3).checkInLine("今年 3 天").build();

        assertSame(hold, s.hold);
        assertSame(expensive, s.mostExpensive);
        assertSame(authors, s.topAuthors);
        assertEquals(55, s.total);
        assertEquals(3, s.totalReadingDays);
        assertEquals("今年 3 天", s.checkInLine);
    }

    @Test
    public void toBuilder一个字段都不丢() throws Exception {
        StatsSnapshot s = fullyPopulated();
        StatsSnapshot again = s.toBuilder().build();
        for (Field f : instanceFields()) {
            Object before = f.get(s);
            Object after = f.get(again);
            if (f.getType().isPrimitive()) {
                assertEquals("toBuilder 漏抄了 " + f.getName(), before, after);
            } else {
                // 引用按同一个对象比：空 List 用 equals 会漏掉「没抄」这种情况
                assertSame("toBuilder 漏抄了 " + f.getName(), before, after);
            }
        }
    }

    @Test
    public void toBuilder改一个字段不影响原快照() throws Exception {
        StatsSnapshot s = fullyPopulated();
        StatsHoldings.Holdings replaced = new StatsHoldings.Holdings();
        replaced.titles = 1;
        StatsSnapshot edited = s.toBuilder().hold(replaced).build();

        assertNotSame(s.hold, edited.hold);
        assertEquals(1, edited.hold.titles);
        assertEquals(3, s.hold.titles);
    }

    /** 四栏分组清单：字段增减必须回来看这条，快照是渲染层唯一的数据来源。 */
    @Test
    public void 字段清单与四栏分组对得上() {
        List<String> mustHave = Arrays.asList(
                // 藏书
                "hold", "categoryTree", "seriesGroups", "cohorts", "structure",
                // 阅读
                "pace", "backlog", "habit", "stale", "buyRead", "dropRecovery",
                "totalReadingDays", "nextRanked", "modeReport",
                // 购书
                "spend", "relation", "trend", "periods", "channels", "costByCategory",
                "noteStat", "wishStat",
                // 借阅
                "lend", "lenders", "lendByCategory", "openLoans", "returnSeries", "lendDateUnparsable",
                // 全量输入与标量
                "books", "total", "finishedThisYear", "avgDays", "totalPrice", "totalSpent",
                "totalWords", "mostExpensive", "mostPages", "mostWords", "earliest",
                "mostPrintings", "recent", "topAuthors", "topPublishers", "topCountries",
                "health", "rating", "anomalies", "borrowOrphans", "checkInLine", "showCheckInLine");
        List<String> names = new ArrayList<>();
        for (Field f : instanceFields()) {
            names.add(f.getName());
        }
        assertEquals("字段数变了要同步改这条", mustHave.size(), names.size());
        for (String name : mustHave) {
            assertTrue("快照少了字段 " + name, names.contains(name));
        }
    }
}
