package com.bai.bookshelf.util;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.bai.bookshelf.data.entity.WishBook;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** 想读清单组合排序（v5.9.8 T6）的纯 JVM 校验。 */
public class WishSortSpecTest {

    private static WishBook wish(long id, String title, int priority, double price, long createdAt) {
        WishBook w = new WishBook();
        w.id = id;
        w.title = title;
        w.priority = priority;
        w.expectedPrice = price;
        w.createdAt = createdAt;
        return w;
    }

    private static List<WishBook> sort(WishSortSpec spec, WishBook... wishes) {
        List<WishBook> list = new ArrayList<>(Arrays.asList(wishes));
        list.sort(spec.comparator());
        return list;
    }

    @Test
    public void 旧int偏好迁移映射方向正确() {
        WishSortSpec priority = WishSortSpec.fromLegacy(0, null);
        assertTrue(priority.keys.get(0).desc);
        assertEquals(WishSortSpec.K_PRIORITY, priority.keys.get(0).key);

        WishSortSpec time = WishSortSpec.fromLegacy(1, null);
        assertTrue("旧「添加时间」是新建在前 = 倒序", time.keys.get(0).desc);
        assertEquals(WishSortSpec.K_TIME, time.keys.get(0).key);

        WishSortSpec title = WishSortSpec.fromLegacy(2, null);
        assertFalse(title.keys.get(0).desc);
        assertEquals(WishSortSpec.K_TITLE, title.keys.get(0).key);

        WishSortSpec manual = WishSortSpec.fromLegacy(3, null);
        assertTrue(manual.manual);
        assertTrue(manual.keys.isEmpty());
    }

    @Test
    public void 已有新配置时迁移不生效() {
        WishSortSpec saved = new WishSortSpec();
        WishSortSpec.KeyDir k = new WishSortSpec.KeyDir();
        k.key = WishSortSpec.K_PRICE;
        saved.keys.add(k);
        WishSortSpec parsed = WishSortSpec.fromLegacy(0, saved.toJson());
        assertEquals("新配置存在时优先用新配置", WishSortSpec.K_PRICE,
                parsed.keys.get(0).key);
    }

    @Test
    public void 组合排序先优先级降序组内价格升序零价垫底() {
        WishSortSpec spec = new WishSortSpec();
        WishSortSpec.KeyDir priority = new WishSortSpec.KeyDir();
        priority.key = WishSortSpec.K_PRIORITY;
        priority.desc = true;
        WishSortSpec.KeyDir price = new WishSortSpec.KeyDir();
        price.key = WishSortSpec.K_PRICE;
        price.desc = false;
        spec.keys.add(priority);
        spec.keys.add(price);

        List<WishBook> out = sort(spec,
                wish(1, "甲", WishBook.PRIORITY_MID, 45.5, 100),
                wish(2, "乙", WishBook.PRIORITY_HIGH, 0, 200),
                wish(3, "丙", WishBook.PRIORITY_HIGH, 30.0, 300),
                wish(4, "丁", WishBook.PRIORITY_HIGH, 30.0, 400));
        assertEquals("高优先组在前；同优先级内有价升序、0 值垫底；同价按 createdAt 新的在前",
                Arrays.asList(4L, 3L, 2L, 1L),
                Arrays.asList(out.get(0).id, out.get(1).id, out.get(2).id, out.get(3).id));
    }

    @Test
    public void 全同按加入时间倒序兜底() {
        WishSortSpec spec = WishSortSpec.parse(null);
        List<WishBook> out = sort(spec,
                wish(1, "同", WishBook.PRIORITY_MID, 0, 111),
                wish(2, "同", WishBook.PRIORITY_MID, 0, 333),
                wish(3, "同", WishBook.PRIORITY_MID, 0, 222));
        assertEquals(Arrays.asList(2L, 3L, 1L),
                Arrays.asList(out.get(0).id, out.get(1).id, out.get(2).id));
    }

    @Test
    public void 标签按固定优先序拼接方向() {
        WishSortSpec spec = new WishSortSpec();
        WishSortSpec.KeyDir price = new WishSortSpec.KeyDir();
        price.key = WishSortSpec.K_PRICE;
        WishSortSpec.KeyDir priority = new WishSortSpec.KeyDir();
        priority.key = WishSortSpec.K_PRIORITY;
        priority.desc = true;
        // 勾选顺序故意反过来：标签与比较都按固定优先序
        spec.keys.add(price);
        spec.keys.add(priority);
        assertEquals("优先级↓ 价格↑", spec.label());
    }

    @Test
    public void json往返后键序与方向不丢() {
        WishSortSpec spec = new WishSortSpec();
        WishSortSpec.KeyDir k1 = new WishSortSpec.KeyDir();
        k1.key = WishSortSpec.K_TITLE;
        WishSortSpec.KeyDir k2 = new WishSortSpec.KeyDir();
        k2.key = WishSortSpec.K_TIME;
        k2.desc = true;
        spec.keys.add(k1);
        spec.keys.add(k2);
        WishSortSpec back = WishSortSpec.parse(spec.toJson());
        assertEquals(2, back.keys.size());
        assertEquals(WishSortSpec.K_TITLE, back.keys.get(0).key);
        assertFalse(back.keys.get(0).desc);
        assertEquals(WishSortSpec.K_TIME, back.keys.get(1).key);
        assertTrue(back.keys.get(1).desc);
    }

    @Test
    public void 坏json与未知键回退安全() {
        assertEquals(WishSortSpec.K_PRIORITY,
                WishSortSpec.parse("不是json{{{").keys.get(0).key);
        WishSortSpec weird = WishSortSpec.parse(
                "{\"keys\":[{\"key\":\"不存在的键\",\"desc\":true},{\"key\":\"price\"}]}");
        assertEquals("未知键要被清洗掉", 1, weird.keys.size());
        assertEquals(WishSortSpec.K_PRICE, weird.keys.get(0).key);
    }

    @Test
    public void 手动排序清空组合键() {
        WishSortSpec spec = WishSortSpec.parse(null);
        assertFalse(spec.keys.isEmpty());
        spec.switchToManual();
        assertTrue(spec.manual);
        assertTrue(spec.keys.isEmpty());
        spec.switchFromManual();
        assertFalse("切回组合时给非空默认", spec.keys.isEmpty());
        assertFalse(spec.manual);
    }
}
