package com.bai.bookshelf.ui.stats;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.util.StatsHoldings;

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
 * 3.1 快照契约：字段全 final、不碰 Android、Builder 一个不漏、List 一律「空只读拷贝」。
 * 四栏「关键数字」的断言在 {@link com.bai.bookshelf.stats.StatsComputerTest}，
 * 这里只钉容器本身。
 * toBuilder 已删：主代码零调用方，留着只会被测试自己喂自己（坑 58）。
 */
public class StatsSnapshotTest {

    /** Builder 里 List 字段的应有个数（16 个，见 {@link #没set的List字段是空只读而不是null}）。 */
    private static final int LIST_FIELD_COUNT = 16;

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
        assertNull(s.mostExpensive);
        // List 字段不再给 null：Fragment 那几个字段本来就是 new ArrayList<>()
        assertTrue(s.books.isEmpty());
    }

    @Test
    public void 装配后对象字段同一List只给只读拷贝() {
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
        assertEquals(authors, s.topAuthors);
        assertNotSame("List 必须是拷贝，不能把外面那个引用直接交出去", authors, s.topAuthors);
        assertEquals(55, s.total);
        assertEquals(3, s.totalReadingDays);
        assertEquals("今年 3 天", s.checkInLine);
    }

    /** 卡 1：只 set 一个字段，其余 16 个 List 都得是「非 null 的空只读列表」。 */
    @Test
    public void 没set的List字段是空只读而不是null() throws Exception {
        StatsSnapshot s = StatsSnapshot.builder().total(3).build();
        int lists = 0;
        for (Field f : instanceFields()) {
            if (f.getType() != List.class) {
                continue;
            }
            lists++;
            Object value = f.get(s);
            assertNotNull("List 字段不该是 null：" + f.getName(), value);
            assertEquals("没 set 的 List 应该是空列表：" + f.getName(), 0, ((List<?>) value).size());
            try {
                ((List<Object>) value).add("往里塞一个");
                fail("快照交出去的 List 必须只读，" + f.getName() + " 竟然能 add");
            } catch (UnsupportedOperationException expected) {
                // 只读包装生效
            }
        }
        assertEquals("List 字段个数变了要同步改这条", LIST_FIELD_COUNT, lists);
    }

    /** 卡 1：build 之后再改传进来的列表，快照不受影响（防御性拷贝）。 */
    @Test
    public void 装配用的列表事后再改不影响快照() {
        List<Book> src = new ArrayList<>();
        src.add(book(1L, "第一本"));
        StatsSnapshot s = StatsSnapshot.builder().books(src).recent(src).build();
        src.add(book(2L, "第二本"));
        assertEquals(1, s.books.size());
        assertEquals(1, s.recent.size());
    }

    /** 卡 1：显式 set null 也按「没有」处理，拿到空只读列表而不是 NPE。 */
    @Test
    public void 显式setnull也回落到空只读() {
        StatsSnapshot s = StatsSnapshot.builder()
                .openLoans(null)
                .topCountries(Collections.<Map.Entry<String, Integer>>emptyList())
                .build();
        assertTrue(s.openLoans.isEmpty());
        assertTrue(s.topCountries.isEmpty());
        try {
            s.openLoans.add(null);
            fail("openLoans 必须只读");
        } catch (UnsupportedOperationException expected) {
            // 只读包装生效
        }
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
