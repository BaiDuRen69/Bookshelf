package com.bai.bookshelf.ui.stats;

import com.bai.bookshelf.data.entity.Book;

/**
 * 全部国家/地区分布二级页：展示所有国家/地区及对应藏书数量，按数量倒序。
 * 逻辑全部在 {@link DistributionListActivity}，本页只声明「统计国家/地区字段」。
 */
public class CountryActivity extends DistributionListActivity {

    @Override
    protected String fieldLabel() {
        return "国家/地区";
    }

    @Override
    protected String extractField(Book book) {
        return book.country;
    }
}
