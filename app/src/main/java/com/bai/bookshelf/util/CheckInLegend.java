package com.bai.bookshelf.util;

/**
 * 打卡页图例的数据（纯计算，JVM 可测）：符号、文案、用哪个语义色。
 *
 * <p>颜色留给 View 侧解析（?attr 需要 Context），这里只给档位常量，
 * 保证月视图与年视图的图例文案有一处唯一的出处。
 */
public final class CheckInLegend {

    /** 主色：已打卡点、含补卡点、选中空心环 */
    public static final int TONE_PRIMARY = 0;
    /** 选中态的描边色（= 主色），与日历里那道空心环同一套 */
    public static final int TONE_OUTLINE = 1;
    /** 「今天」的语义色：日号上方那颗小圆点 */
    public static final int TONE_TODAY = 2;

    public static final class Item {
        public final String glyph;
        public final String label;
        public final int tone;

        Item(String glyph, String label, int tone) {
            this.glyph = glyph;
            this.label = label;
            this.tone = tone;
        }
    }

    private CheckInLegend() {
    }

    /** 月视图网格下方四项 */
    public static Item[] monthItems() {
        return new Item[]{
                new Item("●", "今天", TONE_TODAY),
                new Item("●", "已打卡", TONE_PRIMARY),
                new Item("◐", "含补卡", TONE_PRIMARY),
                new Item("○", "已选中", TONE_OUTLINE),
        };
    }

    /** 年视图底部一项 */
    public static Item[] yearItems() {
        return new Item[]{new Item("●", "该月有打卡", TONE_PRIMARY)};
    }

    /** 月视图图例下面那句：点从哪来（v5.9.1 第六步，精确轮只画真读过的日子） */
    public static String monthNote() {
        return "点 = 实际读过的日子";
    }
}
