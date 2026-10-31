package com.bai.bookshelf.util;

import com.bai.bookshelf.data.entity.Book;
import com.bai.bookshelf.data.entity.Excerpt;
import com.bai.bookshelf.data.entity.WishBook;

import java.util.ArrayList;
import java.util.List;

/**
 * 全局搜索（v5.8.16）：一次输入同时找 藏书 / 摘抄 / 想读 / 功能入口。
 *
 * <p>只做纯数据匹配，不碰视图：命中高亮与跳转由搜索页负责。
 * 藏书的拼音匹配沿用书架那套 haystack（归一化原文 \u0001 全拼 \u0001 首字母），
 * 索引由调用方建好传进来，避免每敲一个字重跑一遍转写。
 */
public final class GlobalSearch {

    /** 每组最多列几条，剩下的靠「查看更多」回到各自页面 */
    public static final int PER_GROUP = 6;

    /** 一条结果：id 供跳转，title/sub 供展示 */
    public static final class Hit {
        public final long id;
        public final String title;
        public final String sub;
        /** 功能入口命中时用：指向哪个目标 */
        public final Target target;

        Hit(long id, String title, String sub) {
            this(id, title, sub, null);
        }

        Hit(long id, String title, String sub, Target target) {
            this.id = id;
            this.title = title == null ? "" : title;
            this.sub = sub == null ? "" : sub;
            this.target = target;
        }
    }

    /** 可跳转的功能入口（借出记录是每本书的二级页，不在此列） */
    public enum Target {
        ADD_BOOK, SCAN, PASTE_ISBN, OCR, INVENTORY, WISH, SERIES,
        LOCATIONS, CHANNELS, CATEGORIES, TAGS, AUTHORS, PUBLISHERS, COUNTRIES,
        READING_STATS, READING_CALENDAR, YEAR_REPORT, SPLIT, INCOMPLETE,
        LIBRARY_MANAGE, API_SETTINGS, ABOUT, BACKUP, BACKUP_FILES, APP_LOCK,
        AUTO_UPDATE, EXPORT_EXCEL, REPAIR, BORROWERS
    }

    /** 一个入口：名称 + 额外关键词 + 目标 */
    public static final class Entry {
        public final String name;
        public final String keywords;
        public final Target target;

        Entry(String name, String keywords, Target target) {
            this.name = name;
            this.keywords = keywords;
            this.target = target;
        }
    }

    /** 搜索结果分组 */
    public static final class Result {
        public final List<Hit> books = new ArrayList<>();
        public final List<Hit> excerpts = new ArrayList<>();
        public final List<Hit> wishes = new ArrayList<>();
        public final List<Hit> actions = new ArrayList<>();

        public boolean isEmpty() {
            return books.isEmpty() && excerpts.isEmpty() && wishes.isEmpty() && actions.isEmpty();
        }

        public int total() {
            return books.size() + excerpts.size() + wishes.size() + actions.size();
        }
    }

    /** 功能入口清单（关键词决定「备份在哪」「怎么加锁」这类问法能不能搜到） */
    public static List<Entry> entries() {
        List<Entry> list = new ArrayList<>();
        list.add(new Entry("录入新书", "添加 新增 手工录入 书名", Target.ADD_BOOK));
        list.add(new Entry("扫码录入", "条形码 isbn 扫描 相机", Target.SCAN));
        list.add(new Entry("粘贴 ISBN 清单", "批量导入 书单 一列书号 文本", Target.PASTE_ISBN));
        list.add(new Entry("拍照识字", "ocr 封面 识别 拍照", Target.OCR));
        list.add(new Entry("盘点模式", "找书 清点 不在架 扫码核对", Target.INVENTORY));
        list.add(new Entry("想读清单", "想买 待读 愿望 书单", Target.WISH));
        list.add(new Entry("系列书架", "套装 多卷 按序 分册", Target.SERIES));
        list.add(new Entry("存放地点", "书房 客厅 书架 位置", Target.LOCATIONS));
        list.add(new Entry("来源渠道", "购买渠道 拼多多 淘宝 多抓鱼", Target.CHANNELS));
        list.add(new Entry("分类管理", "二级分类 归类 目录", Target.CATEGORIES));
        list.add(new Entry("标签管理", "打标 待读 儿童", Target.TAGS));
        list.add(new Entry("借阅人档案", "借出 借书 逾期 催还 借阅人", Target.BORROWERS));
        list.add(new Entry("作者分布", "作家 谁写得多", Target.AUTHORS));
        list.add(new Entry("出版社分布", "版本 出版方", Target.PUBLISHERS));
        list.add(new Entry("国家/地区分布", "中国 美国 日本", Target.COUNTRIES));
        list.add(new Entry("我的阅读", "月度 年度 统计 图表", Target.READING_STATS));
        list.add(new Entry("阅读日历", "打卡 每天读了什么", Target.READING_CALENDAR));
        list.add(new Entry("书房年报", "年度总结 分享", Target.YEAR_REPORT));
        list.add(new Entry("购入价分摊", "套装 拆价 按比例", Target.SPLIT));
        list.add(new Entry("信息补全", "缺封面 缺分类 缺地点", Target.INCOMPLETE));
        list.add(new Entry("书目管理", "地点 来源 标签 分类", Target.LIBRARY_MANAGE));
        list.add(new Entry("ISBN 接口设置", "api 密钥 数据源 条码库", Target.API_SETTINGS));
        list.add(new Entry("自动备份", "备份 恢复 zip 安全", Target.BACKUP));
        list.add(new Entry("备份文件管理", "历史备份 导出 删除 验证", Target.BACKUP_FILES));
        list.add(new Entry("导出 Excel", "表格 清单 分享", Target.EXPORT_EXCEL));
        list.add(new Entry("应用锁", "指纹 面容 密码 隐私 锁定", Target.APP_LOCK));
        list.add(new Entry("检查应用更新", "版本 升级 新版本", Target.AUTO_UPDATE));
        list.add(new Entry("数据库修复", "打不开 迁移失败 恢复备份", Target.REPAIR));
        list.add(new Entry("关于与免责声明", "作者 开源 反馈 邮箱", Target.ABOUT));
        return list;
    }

    private GlobalSearch() {
    }

    /**
     * 一次查询四组结果。
     *
     * @param queryNorm 归一化后的关键词（空串返回空结果）
     * @param index     bookId → haystack（含拼音/首字母）；缺条目时退化为原文匹配
     */
    public static Result query(String queryNorm, List<Book> books,
                               java.util.Map<Long, String> index,
                               List<Excerpt> excerpts, java.util.Map<Long, String> bookTitles,
                               List<WishBook> wishes, int perGroup) {
        Result result = new Result();
        if (queryNorm == null || queryNorm.isEmpty()) {
            return result;
        }
        if (books != null && index != null) {
            for (Book book : books) {
                if (result.books.size() >= perGroup) {
                    break;
                }
                String haystack = index.get(book.id);
                boolean hit = haystack != null
                        ? haystack.contains(queryNorm)
                        : SearchText.matchesFallback(book, queryNorm);
                if (hit) {
                    result.books.add(new Hit(book.id,
                            BookText.displayTitle(book.title),
                            joinNonEmpty(book.author, book.publisher)));
                }
            }
        }
        if (excerpts != null) {
            for (Excerpt e : excerpts) {
                if (result.excerpts.size() >= perGroup) {
                    break;
                }
                String text = SearchText.normalize(e.content) + SearchText.normalize(e.note);
                String title = bookTitles == null ? "" : bookTitles.getOrDefault(e.bookId, "");
                if (text.contains(queryNorm) || SearchText.normalize(title).contains(queryNorm)) {
                    result.excerpts.add(new Hit(e.bookId, clip(e.content.isEmpty() ? title
                                    : e.content, 40),
                            title.isEmpty() ? "" : "《" + BookText.bareTitle(title) + "》"));
                }
            }
        }
        if (wishes != null) {
            for (WishBook w : wishes) {
                if (result.wishes.size() >= perGroup) {
                    break;
                }
                String hay = SearchText.normalize(w.title) + SearchText.normalize(w.author);
                if (hay.contains(queryNorm)) {
                    result.wishes.add(new Hit(w.id, BookText.displayTitle(w.title),
                            w.author == null || w.author.isEmpty() ? "未填作者" : w.author));
                }
            }
        }
        for (Entry entry : entries()) {
            if (result.actions.size() >= perGroup) {
                break;
            }
            String hay = SearchText.normalize(entry.name)
                    + SearchText.normalize(entry.keywords);
            if (hay.contains(queryNorm)) {
                result.actions.add(new Hit(0, entry.name, entry.keywords, entry.target));
            }
        }
        return result;
    }

    private static String joinNonEmpty(String a, String b) {
        StringBuilder sb = new StringBuilder();
        if (a != null && !a.trim().isEmpty()) {
            sb.append(a.trim());
        }
        if (b != null && !b.trim().isEmpty()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(b.trim());
        }
        return sb.toString();
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String t = text.trim().replaceAll("\\s+", " ");
        return t.length() <= max ? t : t.substring(0, max) + "…";
    }
}
