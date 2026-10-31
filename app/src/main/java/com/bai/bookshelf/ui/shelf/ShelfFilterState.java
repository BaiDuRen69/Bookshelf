package com.bai.bookshelf.ui.shelf;

/**
 * 书架「更多筛选」的扩展状态（v5.6 从 ShelfFragment 抽出）：
 * 系列、价格区间、购入/录入日期区间。ShelfFilterRows 直接读写本对象，
 * 书架只负责在筛选条件变化后重新查询。
 */
final class ShelfFilterState {

    String series = "";
    Double priceMin;
    Double priceMax;
    DayRange purchaseRange;
    DayRange entryRange;

    boolean hasSeries() {
        return !series.isEmpty();
    }

    boolean hasPrice() {
        return priceMin != null || priceMax != null;
    }

    boolean hasPurchaseRange() {
        return purchaseRange != null;
    }

    boolean hasEntryRange() {
        return entryRange != null;
    }

    /** 购入日期区间 → {startKey, endKey}（未设置返回 null） */
    int[] purchaseRangeKeys() {
        return purchaseRange == null ? null
                : new int[]{purchaseRange.startKey, purchaseRange.endKey};
    }

    /** 录入日期区间 → {startKey, endKey}（未设置返回 null） */
    int[] entryRangeKeys() {
        return entryRange == null ? null
                : new int[]{entryRange.startKey, entryRange.endKey};
    }

    void setPurchaseRange(int startKey, int endKey) {
        purchaseRange = startKey == 0 && endKey == 0 ? null : new DayRange(startKey, endKey);
    }

    void setEntryRange(int startKey, int endKey) {
        entryRange = startKey == 0 && endKey == 0 ? null : new DayRange(startKey, endKey);
    }

    /** 一键清除系列 / 价格 / 两个日期区间 */
    void clear() {
        series = "";
        priceMin = null;
        priceMax = null;
        purchaseRange = null;
        entryRange = null;
    }
}
