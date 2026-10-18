package com.bai.bookshelf.ui.stats;

import com.bai.bookshelf.data.entity.Book;

/**
 * 全部出版社分布二级页：展示所有出版社及对应藏书数量，按数量倒序。
 * 逻辑全部在 {@link DistributionListActivity}，本页只声明「统计出版社字段」。
 */
public class PublisherActivity extends DistributionListActivity {

    @Override
    protected String fieldLabel() {
        return "出版社";
    }

    @Override
    protected String extractField(Book book) {
        return book.publisher;
    }
}
