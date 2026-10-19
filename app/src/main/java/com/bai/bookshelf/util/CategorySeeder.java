package com.bai.bookshelf.util;

import com.bai.bookshelf.data.AppDatabase;
import com.bai.bookshelf.data.entity.Category;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 默认分类树播种器（v4.8，v4.9 增强）：应用启动与 zip 恢复后调用，幂等补种推荐的一级/二级分类。
 *
 * 规则（保证不破坏存量数据、不产生重名、树保持干净）：
 * - 一级栏目名已存在（含老版本遗留的平铺分类）→ 直接收编为该组的一级，不再新建；
 * - 二级栏目名已存在：
 *   · 是一级（老平铺分类，如旧书数据里的「哲学」「心理学」）→ 收编为该组二级（parentId 改指一级，
 *     书籍仍按名字匹配，无需改任何书）；
 *   · 已是别组的二级 → 跳过；
 * - 「别名表」里的常见旧分类名（如「文学经典」「教育」）收编到指定一级下成为二级；
 * - 其余不认识的老分类保持一级不动，用户可自行增删。
 *
 * Book.category 只存名称字符串，所以补种的树里任何名字都必须全库唯一。
 */
public final class CategorySeeder {

    private CategorySeeder() {
    }

    /** 推荐分类体系：每组第一个是一级栏目，其余是二级栏目（见 README / 需求表 A-J） */
    private static final String[][] TAXONOMY = {
            {"文学", "小说", "散文", "诗歌", "戏剧", "随笔", "文学评论"},
            {"历史与社科", "历史", "政治", "社会", "经济", "法律", "军事", "地理", "人类学"},
            {"哲学与心理", "哲学", "宗教", "伦理", "思想", "心理学"},
            {"自然科学与技术", "数理化生", "天文地理", "医学", "工程", "计算机", "科普"},
            {"商业与实用", "管理", "投资理财", "营销", "职场", "效率方法"},
            {"生活与健康", "烹饪", "家居", "园艺", "健身", "养生", "旅行", "手工"},
            {"艺术与设计", "美术", "音乐", "摄影", "影视", "设计", "建筑", "书法"},
            {"教育与语言", "教材", "考试", "语言学习", "学习方法", "育儿教育"},
            {"工具与参考", "字典", "地图", "年鉴", "手册", "百科", "杂志"},
            {"待分类/其他", "新书", "暂时不确定", "跨类书"},
    };

    /** 常见旧平铺分类名 → 收编目标一级（v4.9：这些名字不占一级位，归入对应组作二级） */
    private static final Map<String, String> LEGACY_ALIAS = new HashMap<>();

    static {
        LEGACY_ALIAS.put("文学经典", "文学");
        LEGACY_ALIAS.put("教育", "教育与语言");
        LEGACY_ALIAS.put("科普读物", "自然科学与技术");
        LEGACY_ALIAS.put("心理", "哲学与心理");
    }

    /** 幂等补种：树里缺的节点补上，老的一级同名分类收编进树 */
    public static void seedIfMissing(AppDatabase db) {
        List<Category> all = db.categoryDao().getAll();
        Map<String, Category> byName = new HashMap<>();
        for (Category c : all) {
            byName.put(c.name, c);
        }
        boolean dirty = false;
        int sort = 100; // 预置树排在一组，与老数据/用户新建项的 sort 空间错开
        for (String[] group : TAXONOMY) {
            // 一级：已存在则收编（无论是老平铺分类还是上次已播的种），否则新建
            Category parent = byName.get(group[0]);
            if (parent == null) {
                parent = new Category();
                parent.name = group[0];
                parent.sort = sort;
                parent.parentId = null;
                parent.id = db.categoryDao().insert(parent);
                byName.put(parent.name, parent);
                dirty = true;
            } else if (parent.parentId != null) {
                // 该名字已被某个二级占用（异常冲突，不动用户数据），整组跳过
                sort += 10;
                continue;
            }
            // 二级：不存在则新建；存在但为一级（老平铺分类）→ 收编进本组；已是二级 → 跳过
            for (int i = 1; i < group.length; i++) {
                String childName = group[i];
                Category child = byName.get(childName);
                if (child == null) {
                    child = new Category();
                    child.name = childName;
                    child.sort = i;
                    child.parentId = parent.id;
                    child.id = db.categoryDao().insert(child);
                    byName.put(childName, child);
                    dirty = true;
                } else if (child.parentId == null) {
                    child.parentId = parent.id;
                    db.categoryDao().update(child);
                    dirty = true;
                }
                // else：已是某组的二级，保持原状
            }
            sort += 10;
        }
        // 别名收编：老平铺分类挂到指定一级下（保留原名成为二级，书籍无需改动）
        for (Map.Entry<String, String> e : LEGACY_ALIAS.entrySet()) {
            Category legacy = byName.get(e.getKey());
            Category target = byName.get(e.getValue());
            if (legacy == null || target == null || target.parentId != null) {
                continue;
            }
            if (legacy.parentId == null) {
                legacy.parentId = target.id;
                db.categoryDao().update(legacy);
                dirty = true;
            }
        }
        if (dirty) {
            // 让后续读取立即看到新树（Room 缓存失效由 InvalidationTracker 自动处理）
            db.categoryDao().getAll();
        }
    }
}
