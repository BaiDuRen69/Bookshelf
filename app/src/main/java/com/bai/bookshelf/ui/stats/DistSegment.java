package com.bai.bookshelf.ui.stats;

/**
 * Top 分布卡的三个分段（v5.9.1 三榜合一）。
 *
 * <p>只负责「这一段叫什么」，榜单数据与二级页跳转仍由 StatsFragment 持有：
 * 三份 Top 8 都在同一次计算里产出，切换只是换一份列表上屏。
 */
public final class DistSegment {

    public static final int AUTHORS = 0;
    public static final int PUBLISHERS = 1;
    public static final int COUNTRIES = 2;
    /** 默认分段：作者榜通常最有信息量 */
    public static final int DEFAULT = AUTHORS;

    private DistSegment() {
    }

    /** 偏好里存过非法值（越界、旧版本残留）时回到默认分段 */
    public static int clamp(int seg) {
        return seg == PUBLISHERS || seg == COUNTRIES ? seg : DEFAULT;
    }

    /** 榜单字段名：标题、空态文案、点行搜索都用它 */
    public static String fieldLabel(int seg) {
        switch (clamp(seg)) {
            case PUBLISHERS:
                return "出版社";
            case COUNTRIES:
                return "国家/地区";
            default:
                return "作者";
        }
    }

    /** 卡片右上角入口文案：合并后目标页随分段变，要说清点进去看的是哪一份 */
    public static String moreLabel(int seg) {
        return "全部" + fieldLabel(seg);
    }
}
