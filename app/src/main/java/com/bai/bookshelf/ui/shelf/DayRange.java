package com.bai.bookshelf.ui.shelf;

/** 书架日期区间（yyyyMMdd 整数键），v5.7 从 ShelfFragment 抽出。 */
final class DayRange {
    final int startKey;
    final int endKey;

    DayRange(int startKey, int endKey) {
        this.startKey = startKey;
        this.endKey = endKey;
    }
}
